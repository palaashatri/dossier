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
}
