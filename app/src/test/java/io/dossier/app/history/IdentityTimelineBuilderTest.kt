package io.dossier.app.history

import io.dossier.app.domain.breach.EmailBreach
import io.dossier.app.domain.breach.EmailExposureResult
import io.dossier.app.domain.breach.HibpCoverage
import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.history.IdentityTimelineBuilder
import io.dossier.app.domain.history.TimelineEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class IdentityTimelineBuilderTest {

    @Test
    fun omitsUntimestampedEvidenceRatherThanInventingDate() {
        val events = IdentityTimelineBuilder.build(
            evidence = listOf(
                Evidence(id = "E0", kind = EvidenceKind.Profile, value = "undated")
            )
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun currentAndHistoricalEvidenceRemainDistinctAndSorted() {
        val events = IdentityTimelineBuilder.build(
            evidence = listOf(
                Evidence(
                    id = "E2",
                    kind = EvidenceKind.Profile,
                    value = "current",
                    observedAtEpochMillis = 2_000L,
                    historical = false
                ),
                Evidence(
                    id = "E1",
                    kind = EvidenceKind.Profile,
                    value = "archived",
                    observedAtEpochMillis = 1_000L,
                    historical = true,
                    providerId = "wayback"
                )
            )
        )

        assertEquals(listOf("E1", "E2"), events.map { it.evidenceIds.single() })
        assertEquals(TimelineEventType.HistoricalEvidence, events[0].type)
        assertEquals(TimelineEventType.CurrentEvidence, events[1].type)
        assertTrue(events[0].historical)
    }

    @Test
    fun breachIncidentUsesProviderDateNotRetrievalDate() {
        val breach = EmailBreach(
            name = "ExampleBreach",
            title = "Example Breach",
            domain = "example.test",
            breachDate = "2024-03-02",
            dataClasses = listOf("Email addresses"),
            verified = true,
            retrievedAtUtc = "2026-08-08T00:00:00Z"
        )
        val result = EmailExposureResult(
            email = "sample@example.test",
            breaches = listOf(breach),
            publicEvidence = emptyList(),
            hibpCoverage = HibpCoverage.ConfirmedBreaches
        )

        val event = IdentityTimelineBuilder.build(emptyList(), listOf(result)).single()
        val expected = LocalDate.parse("2024-03-02")
            .atStartOfDay()
            .toInstant(ZoneOffset.UTC)
            .toEpochMilli()
        assertEquals(expected, event.timestampEpochMillis)
        assertEquals(TimelineEventType.BreachIncident, event.type)
        assertEquals("Have I Been Pwned", event.provider)
    }

    @Test
    fun invalidDatesAreIgnored() {
        assertEquals(null, IdentityTimelineBuilder.parseDateToEpochMillis("not-a-date"))
    }
}
