package io.dossier.app.ui.screens

import io.dossier.app.domain.discovery.ScanMode
import io.dossier.app.domain.discovery.ProviderHealthAssessment
import io.dossier.app.domain.discovery.ProviderHealthReport
import io.dossier.app.domain.discovery.ProviderHealthStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

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
}
