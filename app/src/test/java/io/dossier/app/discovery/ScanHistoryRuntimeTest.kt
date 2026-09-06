package io.dossier.app.domain.discovery

import io.dossier.app.domain.model.IdentityInput
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ScanHistoryRuntimeTest {
    @After
    fun reset() {
        ScanHistoryRuntime.resetForTests()
    }

    @Test
    fun terminalLifecycleIsReturnedOnlyForMatchingNormalizedSeeds() {
        val input = IdentityInput(
            fullName = " Jane Example ",
            emails = listOf("JANE@EXAMPLE.TEST"),
            usernames = listOf("sample_user", "OtherHandle"),
            organizations = listOf("Example Org"),
            selfieUri = "content://first-selfie"
        )
        val equivalent = input.copy(
            fullName = "jane example",
            emails = listOf("jane@example.test"),
            usernames = listOf("otherhandle", "SAMPLE_USER"),
            organizations = listOf("example org"),
            selfieUri = "content://different-selfie"
        )
        val different = input.copy(emails = listOf("other@example.test"))
        val scanId = ScanId("scan-123")

        ScanHistoryRuntime.scanStarted(
            scanId = scanId,
            input = input,
            mode = ScanMode.Deep,
            directProfileProviderCount = 61,
            occurredAt = Instant.parse("2026-08-08T00:00:00Z")
        )
        assertNull(ScanHistoryRuntime.latestFor(input))

        ScanHistoryRuntime.scanFinished(
            scanId = scanId,
            occurredAt = Instant.parse("2026-08-08T00:05:00Z"),
            cancelled = false,
            profileResultCount = 27,
            findingCount = 11,
            breachRecordCount = 2,
            graphEntityCount = 19,
            graphRelationshipCount = 24
        )

        val entry = ScanHistoryRuntime.latestFor(equivalent)
        assertNotNull(entry)
        requireNotNull(entry)
        assertEquals("scan-123", entry.scanId)
        assertEquals("2026-08-08T00:00:00Z", entry.startedAtUtc)
        assertEquals("2026-08-08T00:05:00Z", entry.completedAtUtc)
        assertEquals(ScanMode.Deep, entry.mode)
        assertEquals(61, entry.directProfileProviderCount)
        assertEquals(27, entry.profileResultCount)
        assertEquals(11, entry.findingCount)
        assertEquals(2, entry.breachRecordCount)
        assertEquals(19, entry.graphEntityCount)
        assertEquals(24, entry.graphRelationshipCount)
        assertFalse(entry.cancelled)
        assertNull(ScanHistoryRuntime.latestFor(different))
    }

    @Test
    fun cancelledScanKeepsPartialCountsAndIsExplicitlyMarkedCancelled() {
        val input = IdentityInput(fullName = "Jane Example", usernames = listOf("sample_user"))
        val scanId = ScanId("scan-cancelled")

        ScanHistoryRuntime.scanStarted(
            scanId,
            input,
            ScanMode.Exhaustive,
            directProfileProviderCount = 70,
            occurredAt = Instant.parse("2026-08-08T01:00:00Z")
        )
        ScanHistoryRuntime.scanFinished(
            scanId,
            occurredAt = Instant.parse("2026-08-08T01:01:00Z"),
            cancelled = true,
            profileResultCount = 8,
            findingCount = 3,
            breachRecordCount = 0,
            graphEntityCount = 5,
            graphRelationshipCount = 4
        )

        val entry = requireNotNull(ScanHistoryRuntime.latestFor(input))
        assertTrue(entry.cancelled)
        assertEquals(8, entry.profileResultCount)
        assertEquals(3, entry.findingCount)
    }

    @Test
    fun failedScanIsNotPersistedAsCompletedOrCancelled() {
        val input = IdentityInput(fullName = "Jane Example", usernames = listOf("sample_user"))
        val scanId = ScanId("scan-failed")

        ScanHistoryRuntime.scanStarted(
            scanId,
            input,
            ScanMode.Standard,
            directProfileProviderCount = 40,
            occurredAt = Instant.parse("2026-08-08T02:00:00Z")
        )
        ScanHistoryRuntime.scanFinished(
            scanId,
            occurredAt = Instant.parse("2026-08-08T02:01:00Z"),
            cancelled = false,
            failed = true,
            failureCode = "SCAN_EXECUTION_FAILED",
            profileResultCount = 2,
            findingCount = 1,
            breachRecordCount = 0,
            graphEntityCount = 1,
            graphRelationshipCount = 0
        )

        val entry = requireNotNull(ScanHistoryRuntime.latestFor(input))
        assertTrue(entry.failed)
        assertFalse(entry.cancelled)
        assertEquals("SCAN_EXECUTION_FAILED", entry.failureCode)
    }

    @Test
    fun failedHistoryNormalizesUnsafeCodeFlagsAndNegativeCounts() {
        val input = IdentityInput(fullName = "Jane Example")
        val scanId = ScanId("scan-normalized-failure")
        ScanHistoryRuntime.scanStarted(
            scanId,
            input,
            ScanMode.Standard,
            directProfileProviderCount = 1,
            occurredAt = Instant.parse("2026-08-08T03:00:00Z")
        )
        ScanHistoryRuntime.scanFinished(
            scanId,
            occurredAt = Instant.parse("2026-08-08T03:01:00Z"),
            cancelled = true,
            failed = true,
            failureCode = "TOKEN_DO_NOT_PERSIST",
            profileResultCount = -1,
            findingCount = -2,
            breachRecordCount = -3,
            graphEntityCount = -4,
            graphRelationshipCount = -5
        )

        val entry = requireNotNull(ScanHistoryRuntime.latestFor(input))
        assertTrue(entry.failed)
        assertFalse(entry.cancelled)
        assertEquals("SCAN_FAILED", entry.failureCode)
        assertEquals(0, entry.profileResultCount)
        assertEquals(0, entry.findingCount)
        assertEquals(0, entry.breachRecordCount)
        assertEquals(0, entry.graphEntityCount)
        assertEquals(0, entry.graphRelationshipCount)
    }

    @Test
    fun finishForSnapshotReturnsExactBoundTerminalAndConsumesActiveEntry() {
        val input = IdentityInput(fullName = "Snapshot Subject", primaryUsername = "snapshot")
        val scanId = ScanId("scan-snapshot")
        ScanHistoryRuntime.scanStarted(
            scanId = scanId,
            input = input,
            mode = ScanMode.Standard,
            directProfileProviderCount = 12,
            occurredAt = Instant.parse("2026-08-08T04:00:00Z")
        )

        val entry = ScanHistoryRuntime.finishForSnapshot(
            input = input,
            occurredAt = Instant.parse("2026-08-08T04:03:00Z"),
            profileResultCount = 7,
            findingCount = 4,
            breachRecordCount = 1,
            graphEntityCount = 9,
            graphRelationshipCount = 11
        )

        requireNotNull(entry)
        assertEquals("scan-snapshot", entry.scanId)
        assertEquals("2026-08-08T04:00:00Z", entry.startedAtUtc)
        assertEquals("2026-08-08T04:03:00Z", entry.completedAtUtc)
        assertEquals(12, entry.directProfileProviderCount)
        assertEquals(7, entry.profileResultCount)
        assertEquals(4, entry.findingCount)
        assertEquals(1, entry.breachRecordCount)
        assertEquals(9, entry.graphEntityCount)
        assertEquals(11, entry.graphRelationshipCount)
        assertEquals(entry, ScanHistoryRuntime.latestFor(input))

        assertEquals(
            null,
            ScanHistoryRuntime.finishForSnapshot(
                input = input,
                occurredAt = Instant.parse("2026-08-08T04:04:00Z"),
                profileResultCount = 8,
                findingCount = 5,
                breachRecordCount = 1,
                graphEntityCount = 10,
                graphRelationshipCount = 12
            )
        )
    }

    @Test
    fun durableWorkIdentityRebindsAfterProcessResetAndFinalizesOnce() {
        val input = IdentityInput(fullName = "Fresh Process Subject", primaryUsername = "fresh")
        val scanId = ScanId("11111111-1111-4111-8111-111111111111")

        assertTrue(
            ScanHistoryRuntime.ensureStarted(
                scanId = scanId,
                input = input,
                mode = ScanMode.Deep,
                directProfileProviderCount = 42,
                occurredAt = Instant.parse("2026-08-08T05:00:00Z")
            )
        )
        // A retry in the same process is idempotent and keeps the original
        // durable identity/start row.
        assertTrue(
            ScanHistoryRuntime.ensureStarted(
                scanId = scanId,
                input = input.copy(fullName = " fresh process subject "),
                mode = ScanMode.Deep,
                directProfileProviderCount = 99,
                occurredAt = Instant.parse("2026-08-08T05:01:00Z")
            )
        )

        // Simulate WorkManager recreating the worker in a fresh process-local
        // runtime while retaining only the opaque durable request id.
        ScanHistoryRuntime.resetForTests()
        assertTrue(
            ScanHistoryRuntime.ensureStarted(
                scanId = scanId,
                input = input,
                mode = ScanMode.Deep,
                directProfileProviderCount = 42,
                occurredAt = Instant.parse("2026-08-08T05:02:00Z")
            )
        )

        val completed = ScanHistoryRuntime.finishForSnapshot(
            scanId = scanId,
            input = input,
            occurredAt = Instant.parse("2026-08-08T05:03:00Z"),
            profileResultCount = 3,
            findingCount = 2,
            breachRecordCount = 1,
            graphEntityCount = 4,
            graphRelationshipCount = 5
        )

        requireNotNull(completed)
        assertEquals(scanId.value, completed.scanId)
        assertEquals(completed, ScanHistoryRuntime.latestFor(input))
        assertNull(
            ScanHistoryRuntime.finishForSnapshot(
                scanId = scanId,
                input = input,
                occurredAt = Instant.parse("2026-08-08T05:04:00Z"),
                profileResultCount = 4,
                findingCount = 3,
                breachRecordCount = 1,
                graphEntityCount = 5,
                graphRelationshipCount = 6
            )
        )
    }

    @Test
    fun fingerprintDoesNotStoreRawIdentityAndIgnoresSelfieSelection() {
        val a = IdentityInput(
            fullName = "Jane Example",
            emails = listOf("jane@example.test"),
            selfieUri = "content://photo-one"
        )
        val b = a.copy(selfieUri = "content://photo-two")
        val c = a.copy(fullName = "Different Example")

        val aFingerprint = ScanHistoryRuntime.fingerprintForTests(a)
        assertEquals(aFingerprint, ScanHistoryRuntime.fingerprintForTests(b))
        assertNotEquals(aFingerprint, ScanHistoryRuntime.fingerprintForTests(c))
        assertFalse(aFingerprint.contains("jane", ignoreCase = true))
        assertEquals(64, aFingerprint.length)
    }
}
