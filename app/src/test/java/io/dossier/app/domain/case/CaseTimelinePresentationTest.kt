package io.dossier.app.domain.case

import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.EvidenceReliability
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.BreachDigest
import io.dossier.app.domain.model.ReverseImageLookupResult
import io.dossier.app.domain.place.MediaIntelligenceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaseTimelinePresentationTest {

    @Test
    fun `current and historical capture evidence stay grouped without a false change`() {
        val dossierCase = DossierCase(
            createdAt = "2026-08-24 12:00",
            subjectName = "Authorized subject",
            input = IdentityInput(fullName = "Authorized subject"),
            evidenceRecords = listOf(
                Evidence(
                    id = "current-profile",
                    kind = EvidenceKind.Profile,
                    value = "https://social.example/current",
                    sourceUrl = "https://social.example/current",
                    providerId = "social-example",
                    observedAtEpochMillis = 2_000L,
                    retrievedAtEpochMillis = 2_500L,
                    state = EvidenceState.Verified,
                    reliability = EvidenceReliability.DirectPublicProfile
                ),
                Evidence(
                    id = "historical-profile",
                    kind = EvidenceKind.Profile,
                    value = "https://web.archive.org/web/1000/https://social.example/current",
                    sourceUrl = "https://web.archive.org/web/1000/https://social.example/current",
                    providerId = "wayback-snapshot",
                    observedAtEpochMillis = 1_000L,
                    state = EvidenceState.Verified,
                    reliability = EvidenceReliability.ArchiveSnapshot,
                    historical = true
                )
            )
        )

        val presentation = CaseTimelineBuilder.presentation(dossierCase)
        val profile = presentation.groups.single { it.key == EvidenceKind.Profile.name }

        assertTrue(profile.hasCurrentEvidence)
        assertTrue(profile.hasHistoricalEvidence)
        assertFalse(profile.changed)
        assertEquals(1, profile.distinctValueCount)
        assertEquals(2, presentation.availability.currentObservationCount + presentation.availability.historicalObservationCount)
        assertTrue(profile.events.any { it.providerId == "wayback-snapshot" && it.historical })
        assertTrue(profile.events.any { it.evidenceState == EvidenceState.Verified })
    }

    @Test
    fun `undated evidence and unavailable archive state remain explicit`() {
        val dossierCase = DossierCase(
            createdAt = "2026-08-24 12:00",
            subjectName = "Authorized subject",
            input = IdentityInput(fullName = "Authorized subject"),
            evidenceRecords = listOf(
                Evidence(
                    id = "undated",
                    kind = EvidenceKind.Username,
                    value = "handle"
                ),
                Evidence(
                    id = "archive-unavailable",
                    kind = EvidenceKind.Profile,
                    value = "https://archive.example/unavailable",
                    state = EvidenceState.Unavailable,
                    reliability = EvidenceReliability.ArchiveSnapshot,
                    historical = true
                )
            )
        )

        val availability = CaseTimelineBuilder.presentation(dossierCase).availability

        assertFalse(availability.hasTimestampedEvidence)
        assertEquals(2, availability.undatedEvidenceCount)
        assertEquals(1, availability.archiveUnavailableCount)
        assertEquals(0, availability.historicalObservationCount)
    }

    @Test
    fun `scan activity remains visible without pretending it is evidence`() {
        val dossierCase = DossierCase(
            createdAt = "2026-08-24 12:00",
            subjectName = "Authorized subject",
            input = IdentityInput(fullName = "Authorized subject"),
            scanHistory = listOf(
                CaseScanHistoryEntry(
                    scanId = "scan-1",
                    startedAtUtc = "2026-08-24T10:00:00Z",
                    completedAtUtc = "2026-08-24T10:01:00Z"
                )
            )
        )

        val activity = CaseTimelineBuilder.presentation(dossierCase).groups
            .single { it.key == "activity" }

        assertEquals("Assessment activity", activity.label)
        assertTrue(activity.events.isNotEmpty())
        assertFalse(activity.hasCurrentEvidence)
        assertFalse(activity.hasHistoricalEvidence)
    }

    @Test
    fun `filters expose semantic categories and uncapped availability`() {
        val current = (0 until 4).map { index ->
            Evidence(
                id = "current-$index",
                kind = EvidenceKind.Profile,
                value = "current-$index",
                observedAtEpochMillis = 10_000L + index,
                state = EvidenceState.Verified,
                reliability = EvidenceReliability.DirectPublicProfile
            )
        }
        val historical = (0 until 4).map { index ->
            Evidence(
                id = "historical-$index",
                kind = EvidenceKind.Profile,
                value = "historical-$index",
                observedAtEpochMillis = 1_000L + index,
                historical = true,
                reliability = EvidenceReliability.ArchiveSnapshot
            )
        }
        val dossierCase = DossierCase(
            createdAt = "2026-08-24 12:00",
            subjectName = "Authorized subject",
            input = IdentityInput(fullName = "Authorized subject"),
            evidenceRecords = current + historical,
            breachDigests = listOf(BreachDigest(email = "subject@example.com", breachCount = 1)),
            mediaIntelligence = MediaIntelligenceSnapshot(
                imageResults = listOf(
                    ReverseImageLookupResult(
                        gps = null,
                        extractedText = null,
                        labels = emptyList(),
                        faceDetected = false,
                        faceWarning = null,
                        resolvedLocation = null,
                        mapsUrl = null,
                        webEvidence = emptyList(),
                        visualCandidates = listOf(
                            ReverseImageLookupResult.ImageCandidateProvenance(
                                id = "media-1",
                                title = "Public image",
                                imageUrl = "https://cdn.example/media.jpg",
                                sourcePageUrl = "https://example/media",
                                source = "test",
                                acquisitionQuery = "subject",
                                retrievedAtEpochMillis = 5_000L
                            )
                        )
                    )
                )
            ),
            scanHistory = listOf(
                CaseScanHistoryEntry(
                    scanId = "scan-1",
                    startedAtUtc = "2026-08-24T10:00:00Z",
                    completedAtUtc = "2026-08-24T10:01:00Z"
                )
            ),
            remediationRecords = listOf(
                RemediationRecord(
                    remediationId = "remediation-1",
                    findingKey = "finding",
                    action = "Request deletion",
                    createdAtUtc = "2026-08-24T09:00:00Z",
                    updatedAtUtc = "2026-08-24T09:30:00Z"
                )
            )
        )

        val all = CaseTimelineBuilder.presentation(dossierCase, limit = 3)
        assertEquals(4, all.availability.currentObservationCount)
        assertEquals(4, all.availability.historicalObservationCount)
        assertEquals(13, all.availability.totalEventCount)
        assertEquals(3, all.availability.visibleEventCount)
        assertEquals(10, all.availability.truncatedEventCount)

        assertTrue(
            CaseTimelineBuilder.presentation(dossierCase, filter = TimelineFilter.Live)
                .events.all { it.kind == TimelineEventKind.ObservedEvidence }
        )
        assertTrue(
            CaseTimelineBuilder.presentation(dossierCase, filter = TimelineFilter.Archives)
                .events.all { it.kind == TimelineEventKind.HistoricalEvidence }
        )
        assertTrue(
            CaseTimelineBuilder.presentation(dossierCase, filter = TimelineFilter.ScanActivity)
                .events.all { it.kind in setOf(TimelineEventKind.ScanStarted, TimelineEventKind.ScanCompleted) }
        )
        assertTrue(
            CaseTimelineBuilder.presentation(dossierCase, filter = TimelineFilter.Remediation)
                .events.all { it.kind == TimelineEventKind.Remediation }
        )
        assertTrue(
            CaseTimelineBuilder.presentation(dossierCase, filter = TimelineFilter.Media)
                .events.all { it.kind == TimelineEventKind.MediaCandidate }
        )
        assertEquals(
            1,
            CaseTimelineBuilder.presentation(dossierCase, filter = TimelineFilter.Breaches)
                .availability.undatedBreachCount
        )
    }

    @Test
    fun `remediation rows use only parseable stored timestamps`() {
        val dossierCase = DossierCase(
            createdAt = "2026-08-24 12:00",
            subjectName = "Authorized subject",
            input = IdentityInput(fullName = "Authorized subject"),
            remediationRecords = listOf(
                RemediationRecord(
                    remediationId = "dated",
                    findingKey = "finding",
                    action = "Request deletion",
                    createdAtUtc = "2026-08-24T09:00:00Z",
                    updatedAtUtc = "2026-08-24T09:30:00Z"
                ),
                RemediationRecord(
                    remediationId = "undated",
                    findingKey = "finding-2",
                    action = "Contact provider",
                    createdAtUtc = "not-a-timestamp",
                    updatedAtUtc = ""
                )
            )
        )

        val presentation = CaseTimelineBuilder.presentation(
            dossierCase,
            filter = TimelineFilter.Remediation
        )
        assertEquals(2, presentation.events.size)
        assertEquals(
            listOf(
                CaseTimelineBuilder.parseTimestamp("2026-08-24T09:30:00Z")!!,
                CaseTimelineBuilder.parseTimestamp("2026-08-24T09:00:00Z")!!
            ),
            presentation.events.map { it.timestampEpochMillis }
        )
        assertEquals(1, presentation.availability.undatedRemediationCount)
        assertTrue(presentation.events.all { it.kind == TimelineEventKind.Remediation })
    }

    @Test
    fun `wayback original URL joins the corroborated live profile context`() {
        val dossierCase = DossierCase(
            createdAt = "2026-08-24 12:00",
            subjectName = "Authorized subject",
            input = IdentityInput(fullName = "Authorized subject"),
            evidenceRecords = listOf(
                Evidence(
                    id = "live-profile",
                    kind = EvidenceKind.Profile,
                    value = "https://social.example/current",
                    sourceUrl = "https://social.example/current",
                    observedAtEpochMillis = 2_000L,
                    state = EvidenceState.Verified,
                    reliability = EvidenceReliability.DirectPublicProfile
                ),
                Evidence(
                    id = "wayback-profile",
                    kind = EvidenceKind.PublicSearchEvidence,
                    value = "https://web.archive.org/web/20240824id_/https://social.example/current",
                    sourceUrl = "https://web.archive.org/web/20240824id_/https://social.example/current",
                    observedAtEpochMillis = 1_000L,
                    historical = true,
                    state = EvidenceState.Verified,
                    reliability = EvidenceReliability.ArchiveSnapshot
                )
            )
        )

        val groups = CaseTimelineBuilder.presentation(dossierCase).groups
        val profile = groups.single { it.key == EvidenceKind.Profile.name }
        assertEquals(2, profile.events.size)
        assertFalse(profile.changed)
        assertEquals(1, profile.distinctValueCount)
        assertTrue(groups.none { it.key == EvidenceKind.PublicSearchEvidence.name })
    }

    @Test
    fun `candidate and imported observations do not inflate verified current`() {
        val dossierCase = DossierCase(
            createdAt = "2026-08-24 12:00",
            subjectName = "Authorized subject",
            input = IdentityInput(fullName = "Authorized subject"),
            evidenceRecords = listOf(
                Evidence(
                    id = "search-candidate",
                    kind = EvidenceKind.Profile,
                    value = "https://search.example/candidate",
                    observedAtEpochMillis = 3_000L,
                    state = EvidenceState.Candidate,
                    reliability = EvidenceReliability.SearchEngineCandidate
                ),
                Evidence(
                    id = "imported-observation",
                    kind = EvidenceKind.Profile,
                    value = "https://aggregator.example/profile",
                    observedAtEpochMillis = 2_000L,
                    state = EvidenceState.Observed,
                    reliability = EvidenceReliability.ThirdPartyAggregation
                )
            )
        )

        val presentation = CaseTimelineBuilder.presentation(dossierCase)

        assertEquals(0, presentation.availability.currentObservationCount)
        assertEquals(2, presentation.availability.otherObservationCount)
        assertTrue(presentation.events.any { it.evidenceState == EvidenceState.Candidate })
        assertTrue(presentation.groups.all { !it.hasCurrentEvidence })
        assertTrue(presentation.groups.all { !it.changed })
    }
}
