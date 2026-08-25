package io.dossier.app.ui.screens

import io.dossier.app.domain.discovery.ScanMode
import io.dossier.app.domain.discovery.ProviderHealthAssessment
import io.dossier.app.domain.discovery.ProviderHealthReport
import io.dossier.app.domain.discovery.ProviderHealthSample
import io.dossier.app.domain.discovery.ProviderHealthStatus
import io.dossier.app.domain.discovery.ProviderCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import io.dossier.app.domain.discovery.WhatsMyNameCatalogState
import io.dossier.app.domain.discovery.WhatsMyNameSite

class UsernameDiscoveryScreenTest {
    @Test
    fun unavailableCatalogOmitsUsernameRuleCount() {
        assertEquals(
            "47 profiles",
            formatModeCounts(47, null, ScanMode.Quick)
        )
    }

    @Test
    fun readyCatalogClampsEveryModeToItsRealBudget() {
        assertEquals(
            "47 profiles • 50 username rules",
            formatModeCounts(47, 644, ScanMode.Quick)
        )
        assertEquals(
            "150 profiles • 200 username rules",
            formatModeCounts(150, 644, ScanMode.Standard)
        )
        assertEquals(
            "300 profiles • 500 username rules",
            formatModeCounts(300, 644, ScanMode.Deep)
        )
        assertEquals(
            "350 profiles • 644 username rules",
            formatModeCounts(350, 644, ScanMode.Exhaustive)
        )
        assertEquals(
            "350 profiles • 600 username rules",
            formatModeCounts(350, 600, ScanMode.Exhaustive)
        )
    }

    @Test
    fun providerHealthSummarySurfacesEveryExplicitBucket() {
        val now = Instant.parse("2026-08-25T12:00:00Z")
        val report = ProviderHealthReport(
            knownProviderCount = 5,
            observedProviderCount = 5,
            assessments = listOf(
                ProviderHealthAssessment("healthy", ProviderHealthStatus.Healthy, 3, 3, 1.0, 0.0, 100, now),
                ProviderHealthAssessment("degraded", ProviderHealthStatus.Degraded, 3, 2, 2.0 / 3.0, 1.0 / 3.0, 100, now),
                ProviderHealthAssessment("unavailable", ProviderHealthStatus.Unavailable, 3, 0, 0.0, 1.0, null, now),
                ProviderHealthAssessment("stale", ProviderHealthStatus.Stale, 3, 3, 1.0, 0.0, 100, now.minusSeconds(31 * 24 * 60 * 60)),
                ProviderHealthAssessment("unvalidated", ProviderHealthStatus.Unvalidated, 0, 0, 0.0, 0.0, null, null)
            )
        )

        assertEquals(
            "Healthy 1 · Degraded 1 · Unavailable 1 · Stale 1 · Unvalidated 1",
            providerHealthSummary(report)
        )
    }

    @Test
    fun whatsMyNameHealthReportKeepsPinnedRulesSeparateFromAuthoredSamples() {
        val now = Instant.parse("2026-08-25T12:00:00Z")
        val state = WhatsMyNameCatalogState.Ready(
            sites = listOf(
                testSite("wmn-one"),
                testSite("wmn-two")
            ),
            excluded = emptyList(),
            license = emptyList(),
            authors = emptyList(),
            categories = emptyList(),
            totalCount = 2,
            executableCount = 2,
            excludedCount = 0
        )
        val report = whatsMyNameHealthReport(
            state = state,
            samples = listOf(
                ProviderHealthSample(
                    providerId = "wmn-one",
                    attempts = 3,
                    successes = 3,
                    lastValidatedAt = now
                ),
                ProviderHealthSample(
                    providerId = "authored-github",
                    attempts = 3,
                    successes = 3,
                    lastValidatedAt = now
                )
            ),
            now = now
        )

        assertEquals(2, report?.knownProviderCount)
        assertEquals(1, report?.observedProviderCount)
        assertEquals(
            setOf("wmn-one", "wmn-two"),
            report?.assessments?.map { it.providerId }?.toSet()
        )
        assertEquals(ProviderHealthStatus.Healthy, report?.assessments?.first()?.status)
        assertEquals(ProviderHealthStatus.Unvalidated, report?.assessments?.last()?.status)
    }

    @Test
    fun whatsMyNameHealthReportIsAbsentWhenCatalogUnavailable() {
        assertNull(
            whatsMyNameHealthReport(
                state = WhatsMyNameCatalogState.Unavailable("integrity mismatch"),
                samples = emptyList(),
                now = Instant.parse("2026-08-25T12:00:00Z")
            )
        )
    }

    private fun testSite(id: String) = WhatsMyNameSite(
        id = id,
        name = id,
        category = ProviderCategory.Social,
        uriPretty = "https://$id.example/{account}",
        uriCheck = "https://$id.example/{account}",
        eCode = 200,
        eString = "found",
        mCode = 404,
        mString = "missing",
        stripBadChar = ""
    )
}
