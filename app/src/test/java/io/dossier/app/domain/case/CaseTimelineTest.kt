package io.dossier.app.domain.case

import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.EvidenceReliability
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.model.IdentityInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaseTimelineTest {
    @Test
    fun usesOnlyRecordedTimestampsAndSortsNewestFirst() {
        val case = DossierCase(
            createdAt = "2026-08-21 01:00",
            subjectName = "X",
            input = IdentityInput(fullName = "", primaryUsername = "x"),
            evidenceRecords = listOf(
                Evidence(
                    id = "old",
                    kind = EvidenceKind.Profile,
                    value = "https://example.test/x",
                    sourceUrl = "https://example.test/x",
                    observedAtEpochMillis = 1_000L,
                    retrievedAtEpochMillis = 3_000L,
                    state = EvidenceState.Observed,
                    reliability = EvidenceReliability.DirectPublicProfile
                ),
                Evidence(
                    id = "historical",
                    kind = EvidenceKind.PublicSearchEvidence,
                    value = "archive",
                    sourceUrl = "https://archive.example/x",
                    observedAtEpochMillis = 2_000L,
                    state = EvidenceState.Candidate,
                    reliability = EvidenceReliability.ArchiveSnapshot,
                    historical = true
                ),
                Evidence(
                    id = "undated",
                    kind = EvidenceKind.Username,
                    value = "x"
                )
            )
        )

        val events = CaseTimelineBuilder.build(case)
        assertEquals(listOf(3_000L, 2_000L, 1_000L), events.map { it.timestampEpochMillis })
        assertTrue(events.any { it.historical })
        assertTrue(events.none { it.evidenceId == "undated" })
    }

    @Test
    fun parsesRecordedScanTimesWithoutFabricatingUnknownFormats() {
        assertEquals(0L, CaseTimelineBuilder.parseTimestamp("1970-01-01T00:00:00Z"))
        assertEquals(null, CaseTimelineBuilder.parseTimestamp("definitely-not-a-time"))
    }

    @Test
    fun failedScanHasTruthfulTimelineTitle() {
        val case = DossierCase(
            createdAt = "2026-08-21 01:00",
            subjectName = "X",
            input = IdentityInput(fullName = "", primaryUsername = "x"),
            scanHistory = listOf(
                CaseScanHistoryEntry(
                    scanId = "failed-scan",
                    startedAtUtc = "2026-08-21T01:00:00Z",
                    completedAtUtc = "2026-08-21T01:01:00Z",
                    failed = true,
                    failureCode = "SCAN_EXECUTION_FAILED"
                )
            )
        )

        val terminal = CaseTimelineBuilder.build(case)
            .single { it.evidenceId == "scan:failed-scan:complete" }
        assertEquals(TimelineEventKind.ScanFailed, terminal.kind)
        assertEquals("Assessment failed", terminal.title)
        assertTrue(terminal.detail.contains("SCAN_EXECUTION_FAILED"))
    }

    @Test
    fun cancelledScanHasDistinctTimelineKind() {
        val case = DossierCase(
            createdAt = "2026-08-21 01:00",
            subjectName = "X",
            input = IdentityInput(fullName = "", primaryUsername = "x"),
            scanHistory = listOf(
                CaseScanHistoryEntry(
                    scanId = "cancelled-scan",
                    startedAtUtc = "2026-08-21T01:00:00Z",
                    completedAtUtc = "2026-08-21T01:01:00Z",
                    cancelled = true
                )
            )
        )

        val terminal = CaseTimelineBuilder.build(case)
            .single { it.evidenceId == "scan:cancelled-scan:complete" }
        assertEquals(TimelineEventKind.ScanCancelled, terminal.kind)
        assertEquals("Assessment cancelled", terminal.title)
    }

    @Test
    fun unsafeFailureDetailsAndNegativeCountsNeverReachTimeline() {
        val dossierCase = DossierCase(
            createdAt = "2026-08-21 01:00",
            subjectName = "X",
            input = IdentityInput(fullName = "", primaryUsername = "x"),
            scanHistory = listOf(
                CaseScanHistoryEntry(
                    scanId = "unsafe-scan",
                    startedAtUtc = "2026-08-21T01:00:00Z",
                    completedAtUtc = "2026-08-21T01:01:00Z",
                    directProfileProviderCount = -10,
                    profileResultCount = -1,
                    findingCount = -2,
                    graphEntityCount = -3,
                    failed = true,
                    failureCode = "token=do-not-render"
                )
            )
        )

        val events = CaseTimelineBuilder.build(dossierCase)
        val started = events.single { it.evidenceId == "scan:unsafe-scan:start" }
        val terminal = events.single { it.evidenceId == "scan:unsafe-scan:complete" }

        assertTrue(started.detail.contains("0 direct providers"))
        assertEquals("0 profiles · 0 findings · 0 entities · SCAN_FAILED", terminal.detail)
        assertTrue(events.none { it.detail.contains("token=do-not-render") })
    }
}
