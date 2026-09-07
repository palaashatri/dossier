package io.dossier.app.domain.case

import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.EvidenceReliability
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.model.DossierEntity
import io.dossier.app.domain.model.EntityGraph
import io.dossier.app.domain.model.EntityType
import io.dossier.app.domain.model.GraphNodeState
import io.dossier.app.domain.model.IdentityInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaseTimelineCorrectionTest {

    @Test
    fun ignoredCurrentEvidenceIsRawAuditAndExcludedFromActiveMetricsAndFilters() {
        val evidence = Evidence(
            id = "raw-current",
            kind = EvidenceKind.Profile,
            value = "https://social.example/profile",
            sourceUrl = "https://social.example/profile",
            observedAtEpochMillis = 2_000L,
            state = EvidenceState.Verified,
            reliability = EvidenceReliability.DirectPublicProfile
        )
        val dossierCase = DossierCase(
            createdAt = "2026-08-24 12:00",
            subjectName = "Authorized subject",
            input = IdentityInput(fullName = "Authorized subject"),
            evidenceRecords = listOf(evidence),
            userCorrections = listOf(
                UserCorrection(
                    correctionId = "ignore-current",
                    evidenceId = evidence.id,
                    decision = UserCorrectionDecision.IgnoreEvidence,
                    createdAtUtc = "2026-08-24T12:01:00Z"
                )
            )
        )

        val all = CaseTimelineBuilder.presentation(dossierCase)
        val audit = all.events.single { it.evidenceId == evidence.id }
        val corrections = CaseTimelineBuilder.presentation(
            dossierCase,
            filter = TimelineFilter.Corrections
        )
        val live = CaseTimelineBuilder.presentation(
            dossierCase,
            filter = TimelineFilter.Live
        )

        assertTrue(audit.rawAuditOnly)
        assertEquals("REJECTED · RAW AUDIT", audit.title.substringBefore(" · Profile"))
        assertEquals(EvidenceState.Rejected, audit.evidenceState)
        assertFalse(audit.isVerifiedCurrent)
        assertEquals(0, all.availability.currentObservationCount)
        assertEquals(0, all.availability.timestampedEvidenceCount)
        assertEquals(1, all.availability.rawAuditEvidenceCount)
        assertEquals(1, all.availability.rawAuditTimestampedEvidenceCount)
        assertEquals(0, all.availability.rawAuditUndatedEvidenceCount)
        assertTrue(live.events.isEmpty())
        assertEquals(listOf(audit), corrections.events)
        assertFalse(all.groups.single().changed)
        assertFalse(all.groups.single().hasCurrentEvidence)

        // The encrypted/raw audit record remains untouched.
        assertEquals(EvidenceState.Verified, dossierCase.evidenceRecords.single().state)
    }

    @Test
    fun rejectedEntityEvidenceIsSeparatedWithoutErasingContradictionOrHistory() {
        val live = Evidence(
            id = "live-profile",
            kind = EvidenceKind.Profile,
            value = "https://social.example/profile",
            sourceUrl = "https://social.example/profile",
            observedAtEpochMillis = 2_000L,
            state = EvidenceState.Verified,
            reliability = EvidenceReliability.DirectPublicProfile
        )
        val historical = Evidence(
            id = "historical-profile",
            kind = EvidenceKind.PublicSearchEvidence,
            value = "https://web.archive.org/web/1000/https://social.example/profile",
            sourceUrl = "https://web.archive.org/web/1000/https://social.example/profile",
            observedAtEpochMillis = 1_000L,
            state = EvidenceState.Verified,
            reliability = EvidenceReliability.ArchiveSnapshot,
            historical = true
        )
        val profile = DossierEntity(
            id = "profile:rejected",
            type = EntityType.Profile,
            label = live.value,
            sourceUrls = listOf(live.sourceUrl!!),
            evidenceIds = listOf(live.id),
            state = GraphNodeState.High
        )
        val dossierCase = DossierCase(
            createdAt = "2026-08-24 12:00",
            subjectName = "Authorized subject",
            input = IdentityInput(fullName = "Authorized subject"),
            evidenceRecords = listOf(live, historical),
            entityGraph = EntityGraph(entities = listOf(profile)),
            userCorrections = listOf(
                UserCorrection(
                    correctionId = "reject-profile",
                    entityId = profile.id,
                    decision = UserCorrectionDecision.ThisIsNotMe,
                    createdAtUtc = "2026-08-24T12:01:00Z"
                )
            )
        )

        val all = CaseTimelineBuilder.presentation(dossierCase)
        val rejected = all.events.single { it.evidenceId == live.id }
        val historicalEvent = all.events.single { it.evidenceId == historical.id }
        val profileGroup = all.groups.single { it.key == EvidenceKind.Profile.name }
        val corrections = CaseTimelineBuilder.presentation(
            dossierCase,
            filter = TimelineFilter.Corrections
        )
        val archives = CaseTimelineBuilder.presentation(
            dossierCase,
            filter = TimelineFilter.Archives
        )

        assertTrue(rejected.rawAuditOnly)
        assertTrue(rejected.detail.contains("Supports an entity rejected by user"))
        assertEquals(EvidenceState.Rejected, rejected.evidenceState)
        assertTrue(historicalEvent.historical)
        assertEquals(0, all.availability.currentObservationCount)
        assertEquals(1, all.availability.historicalObservationCount)
        assertEquals(1, all.availability.rawAuditEvidenceCount)
        assertEquals(1, corrections.events.size)
        assertEquals(live.id, corrections.events.single().evidenceId)
        assertTrue(archives.events.all { it.evidenceId != live.id })
        assertFalse(profileGroup.hasCurrentEvidence)
        assertFalse(profileGroup.changed)
        assertEquals(1, profileGroup.distinctValueCount)

        // Raw records and the conflicting graph node remain inspectable.
        assertEquals(EvidenceState.Verified, dossierCase.evidenceRecords.first().state)
        assertEquals(GraphNodeState.High, dossierCase.entityGraph.entities.single().state)
    }
}
