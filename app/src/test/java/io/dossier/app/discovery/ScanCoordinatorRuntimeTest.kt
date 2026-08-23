package io.dossier.app.domain.discovery

import io.dossier.app.domain.scanner.BackgroundScanWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScanCoordinatorRuntimeTest {
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

        assertEquals(ScanRunState.Cancelled, cancelled.state)
        assertNull(cancelled.failureCode)
        assertEquals(ScanRunState.Cancelled, restoredCancellation.state)
        assertNull(restoredCancellation.failureCode)
        assertEquals(ScanRunState.Completed, completed.state)
        assertNull(completed.failureCode)
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
