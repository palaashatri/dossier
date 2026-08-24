package io.dossier.app.domain.discovery

import android.content.ContextWrapper
import io.dossier.app.data.platform.ProviderCatalogV2
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.scanner.BackgroundScanWorker
import io.dossier.app.domain.scanner.ScanPayloadStage
import io.dossier.app.domain.scanner.ScanPayloadSummary
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanCoordinatorRuntimeTest {
    @Test
    fun startDelegatesOnlyThroughCoordinatorAndBindsRequestedPlan() = runBlocking {
        val request = ScanRequest(
            input = IdentityInput(fullName = "Coordinator Subject", primaryUsername = "coordinator"),
            mode = ScanMode.Deep,
            deepResearch = true
        )
        var delegated: ScanRequest? = null

        ScanCoordinatorRuntime.resetForTests()
        ScanCoordinatorRuntime.scanStarter = { _, delegatedRequest ->
            delegated = delegatedRequest
        }

        try {
            val scanId = ScanCoordinatorRuntime.start(
                context = ContextWrapper(null),
                request = request
            )

            assertEquals(request, delegated)
            assertEquals(scanId, ScanCoordinatorRuntime.snapshot.value.scanId)
            assertEquals(request.mode, ScanCoordinatorRuntime.snapshot.value.mode)
            assertEquals(
                ScanPlanSummary.from(ProviderCatalogV2.plan(request.mode)),
                ScanCoordinatorRuntime.snapshot.value.plan
            )
        } finally {
            ScanCoordinatorRuntime.resetForTests()
        }
    }

    @Test
    fun pivotDiagnosticsProjectFrontierStateAndLatestDecision() = runBlocking {
        val scanId = ScanId("pivot-diagnostics")
        ScanCoordinatorRuntime.resetCounts(scanId)
        val nextEvent = async(start = CoroutineStart.UNDISPATCHED) {
            ScanCoordinatorRuntime.events.first { it is ScanEvent.PivotDiagnosticsUpdated }
        }

        ScanCoordinatorRuntime.onPivotDiagnostics(
            scanId = scanId,
            decision = PivotDecisionSummary(
                admitted = false,
                signalType = "CommonUsername",
                depth = 2,
                reason = "Common handle lacks independent corroboration"
            ),
            pendingCount = 2,
            pendingByDepth = listOf(1, 1),
            admittedCount = 3,
            rejectedCount = 4,
            visitedCount = 8,
            maxDepth = 2,
            maxTotalPivots = 15
        )

        val event = nextEvent.await() as ScanEvent.PivotDiagnosticsUpdated
        assertEquals(scanId, event.scanId)
        assertEquals(2, event.pendingCount)
        assertEquals(4, event.rejectedCount)
        assertEquals("CommonUsername", event.decision?.signalType)

        val snapshot = ScanCoordinatorRuntime.snapshot.value
        assertEquals(2, snapshot.pivotPendingCount)
        assertEquals(listOf(1, 1), snapshot.pivotPendingByDepth)
        assertEquals(3, snapshot.pivotAdmittedCount)
        assertEquals(4, snapshot.pivotRejectedCount)
        assertEquals(8, snapshot.pivotVisitedCount)
        assertEquals(2, snapshot.pivotMaxDepth)
        assertEquals(15, snapshot.pivotMaxTotalPivots)
        assertEquals("Common handle lacks independent corroboration", snapshot.pivotLastDecision?.reason)
    }

    @Test
    fun stalePivotDiagnosticsCannotMutateActiveScan() {
        val active = ScanId("active-pivot")
        val stale = ScanId("stale-pivot")
        ScanCoordinatorRuntime.resetCounts(active)

        ScanCoordinatorRuntime.onPivotDiagnostics(
            scanId = stale,
            decision = PivotDecisionSummary(true, "ExplicitProfileLink", 1, "should be ignored"),
            pendingCount = 9,
            pendingByDepth = listOf(9),
            admittedCount = 9,
            rejectedCount = 9,
            visitedCount = 9,
            maxDepth = 2,
            maxTotalPivots = 15
        )

        val snapshot = ScanCoordinatorRuntime.snapshot.value
        assertEquals(active, snapshot.scanId)
        assertEquals(0, snapshot.pivotPendingCount)
        assertEquals(0, snapshot.pivotAdmittedCount)
        assertEquals(0, snapshot.pivotRejectedCount)
        assertNull(snapshot.pivotLastDecision)
    }

    @Test
    fun pivotDiagnosticPayloadIsBoundedAndDoesNotCarryUrls() = runBlocking {
        val scanId = ScanId("sanitized-pivot")
        ScanCoordinatorRuntime.resetCounts(scanId)
        val nextEvent = async(start = CoroutineStart.UNDISPATCHED) {
            ScanCoordinatorRuntime.events.first { it is ScanEvent.PivotDiagnosticsUpdated }
        }

        ScanCoordinatorRuntime.dispatch(
            ScanEvent.PivotDiagnosticsUpdated(
                scanId = scanId,
                occurredAt = java.time.Instant.now(),
                decision = PivotDecisionSummary(
                    admitted = true,
                    signalType = "https://example.test/private/user",
                    depth = 99,
                    reason = "line one\nline two\t" + "x".repeat(400)
                ),
                pendingCount = 999,
                pendingByDepth = List(10) { 999 },
                admittedCount = 999,
                rejectedCount = 99_999,
                visitedCount = 99_999,
                maxDepth = 99,
                maxTotalPivots = 99_999
            )
        )

        val event = nextEvent.await() as ScanEvent.PivotDiagnosticsUpdated
        val decision = event.decision
        assertNotNull(decision)
        assertEquals("Unknown", decision?.signalType)
        assertEquals(4, decision?.depth)
        assertTrue(decision?.reason?.startsWith("line one line two") == true)
        assertFalse(decision?.reason?.contains('\n') == true)
        assertEquals(4, event.pendingByDepth.size)
        assertEquals(200, event.pendingCount)
        assertEquals(200, event.admittedCount)
        assertEquals(4_096, event.rejectedCount)
        assertEquals(4_096, event.visitedCount)
        assertEquals(4, event.maxDepth)
        assertEquals(200, event.maxTotalPivots)
    }

    @Test
    fun resetCountsClearsPriorPivotState() {
        val first = ScanId("first-pivot")
        val second = ScanId("second-pivot")
        ScanCoordinatorRuntime.resetCounts(first)
        ScanCoordinatorRuntime.onPivotDiagnostics(
            scanId = first,
            decision = PivotDecisionSummary(true, "ExplicitProfileLink", 1, "admitted"),
            pendingCount = 1,
            pendingByDepth = listOf(1),
            admittedCount = 1,
            rejectedCount = 0,
            visitedCount = 1,
            maxDepth = 2,
            maxTotalPivots = 15
        )

        ScanCoordinatorRuntime.resetCounts(second)
        val snapshot = ScanCoordinatorRuntime.snapshot.value
        assertEquals(second, snapshot.scanId)
        assertEquals(0, snapshot.pivotPendingCount)
        assertEquals(0, snapshot.pivotAdmittedCount)
        assertEquals(0, snapshot.pivotRejectedCount)
        assertNull(snapshot.pivotLastDecision)
    }

    @Test
    fun checkpointEventIsAllowlistedAndReflectedInLiveSnapshot() = runBlocking {
        val scanId = ScanId("checkpoint-event")
        ScanCoordinatorRuntime.resetCounts(scanId)
        val nextEvent = async(start = CoroutineStart.UNDISPATCHED) {
            ScanCoordinatorRuntime.events.first { it is ScanEvent.CheckpointUpdated }
        }
        val plan = ScanPlanSummary.from(ProviderCatalogV2.plan(ScanMode.Standard))
        val payloadSummary = ScanPayloadSummary(
            stage = ScanPayloadStage.PublicSearch,
            itemCount = 2,
            digest = "d".repeat(64),
            payloadBytes = 512
        )

        ScanCoordinatorRuntime.dispatch(
            ScanEvent.CheckpointUpdated(
                scanId = scanId,
                occurredAt = java.time.Instant.now(),
                stage = "private-token=do-not-emit",
                completedStages = listOf(
                    "DISCOVERING_USERNAMES",
                    "private-token=do-not-emit",
                    "BUILDING_ENTITY_GRAPH"
                ),
                plan = plan,
                payloadSummaries = listOf(
                    payloadSummary,
                    payloadSummary.copy(digest = "not-allowed")
                )
            )
        )

        val event = nextEvent.await() as ScanEvent.CheckpointUpdated
        assertEquals("QUEUED_BACKGROUND_SCAN", event.stage)
        assertEquals(
            listOf("DISCOVERING_USERNAMES", "BUILDING_ENTITY_GRAPH"),
            event.completedStages
        )
        assertEquals("QUEUED_BACKGROUND_SCAN", ScanCoordinatorRuntime.snapshot.value.checkpointStage)
        assertEquals(event.completedStages, ScanCoordinatorRuntime.snapshot.value.completedCheckpointStages)
        assertEquals(plan, event.plan)
        assertEquals(plan, ScanCoordinatorRuntime.snapshot.value.plan)
        assertTrue(event.payloadSummaries.isEmpty())
        assertTrue(ScanCoordinatorRuntime.snapshot.value.payloadSummaries.isEmpty())
    }

    @Test
    fun payloadSummaryIsExposedOnlyOnDiscoveryCheckpoint() = runBlocking {
        val scanId = ScanId("payload-summary-event")
        val summary = ScanPayloadSummary(
            stage = ScanPayloadStage.PublicImage,
            itemCount = 1,
            digest = "e".repeat(64),
            payloadBytes = 256
        )
        ScanCoordinatorRuntime.resetCounts(scanId)
        val nextEvent = async(start = CoroutineStart.UNDISPATCHED) {
            ScanCoordinatorRuntime.events.first { it is ScanEvent.CheckpointUpdated }
        }
        ScanCoordinatorRuntime.dispatch(
            ScanEvent.CheckpointUpdated(
                scanId = scanId,
                occurredAt = java.time.Instant.now(),
                stage = "DISCOVERING_USERNAMES",
                completedStages = listOf("DISCOVERING_USERNAMES"),
                payloadSummaries = listOf(summary)
            )
        )
        val event = nextEvent.await() as ScanEvent.CheckpointUpdated
        assertEquals(listOf(summary), event.payloadSummaries)
        assertEquals(listOf(summary), ScanCoordinatorRuntime.snapshot.value.payloadSummaries)
    }

    @Test
    fun terminalFailureIsNotReportedAsCompleted() {
        val classified = classifyTerminalStage(
            "${BackgroundScanWorker.STAGE_FAILED}: SECURE_REQUEST_RECORD_MISSING"
        )

        assertEquals(ScanRunState.Failed, classified.state)
        assertEquals("SECURE_REQUEST_RECORD_MISSING", classified.failureCode)
    }

    @Test
    fun unsafeFailureDetailIsReplacedByGenericCode() {
        val classified = classifyTerminalStage(
            "${BackgroundScanWorker.STAGE_FAILED}: token=do-not-persist"
        )

        assertEquals(ScanRunState.Failed, classified.state)
        assertEquals("SCAN_FAILED", classified.failureCode)
    }

    @Test
    fun cancellationAndCompletionRemainDistinct() {
        val cancelled = classifyTerminalStage("SCAN_CANCELLED")
        val restoredCancellation = classifyTerminalStage(BackgroundScanWorker.STAGE_CANCELLED)
        val completed = classifyTerminalStage(BackgroundScanWorker.STAGE_COMPLETE)
        val paused = classifyTerminalStage("SCAN_PAUSED")

        assertEquals(ScanRunState.Cancelled, cancelled.state)
        assertNull(cancelled.failureCode)
        assertEquals(ScanRunState.Cancelled, restoredCancellation.state)
        assertNull(restoredCancellation.failureCode)
        assertEquals(ScanRunState.Completed, completed.state)
        assertNull(completed.failureCode)
        assertEquals(ScanRunState.Paused, paused.state)
        assertNull(paused.failureCode)
    }

    @Test
    fun unknownTerminalStageFailsClosedAndRawStageIsNotEmitted() {
        val secret = "java.io.IOException token=do-not-emit"
        val classified = classifyTerminalStage(secret)

        assertEquals(ScanRunState.Failed, classified.state)
        assertEquals("SCAN_FAILED", classified.failureCode)
        assertEquals(BackgroundScanWorker.STAGE_RUNNING, safeCoordinatorStage(secret))
    }
}
