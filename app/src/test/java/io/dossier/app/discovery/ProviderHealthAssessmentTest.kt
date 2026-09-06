package io.dossier.app.discovery

import io.dossier.app.data.platform.ProviderCatalogV2
import io.dossier.app.domain.discovery.PersistentProviderHealthStore
import io.dossier.app.domain.discovery.ProviderHealthAssessmentRules
import io.dossier.app.domain.discovery.ProviderHealthDataQuality
import io.dossier.app.domain.discovery.ProviderHealthSample
import io.dossier.app.domain.discovery.ProviderHealthStatus
import io.dossier.app.domain.discovery.WhatsMyNameCatalog
import io.dossier.app.domain.discovery.WhatsMyNameCatalogState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class ProviderHealthAssessmentTest {
    private val now = Instant.parse("2026-08-25T12:00:00Z")

    @Test
    fun notFoundIsAUsableValidationOutcome() {
        val assessment = ProviderHealthAssessmentRules.assess(
            sample = sample(attempts = 4, notFound = 4),
            now = now
        )

        assertEquals(ProviderHealthStatus.Healthy, assessment.status)
        assertEquals(4L, assessment.usableResponses)
        assertEquals(1.0, assessment.usableResponseRate, 0.0001)
        assertEquals(0.0, assessment.failureRate, 0.0001)
    }

    @Test
    fun insufficientAndMaterialFailuresAreDistinguishedFromUnavailable() {
        val insufficient = ProviderHealthAssessmentRules.assess(
            sample = sample(attempts = 1, successes = 1),
            now = now
        )
        val degraded = ProviderHealthAssessmentRules.assess(
            sample = sample(attempts = 4, successes = 2, timeouts = 2),
            now = now
        )
        val unavailable = ProviderHealthAssessmentRules.assess(
            sample = sample(attempts = 4, timeouts = 4),
            now = now
        )

        assertEquals(ProviderHealthStatus.Degraded, insufficient.status)
        assertEquals(ProviderHealthStatus.Degraded, degraded.status)
        assertEquals(ProviderHealthStatus.Unavailable, unavailable.status)
    }

    @Test
    fun staleAndUnvalidatedStatesAreExplicit() {
        val stale = ProviderHealthAssessmentRules.assess(
            sample = sample(attempts = 3, successes = 3, at = now.minus(Duration.ofDays(31))),
            now = now
        )
        val unvalidated = ProviderHealthAssessmentRules.assess(
            sample = sample(attempts = 0, at = null),
            now = now
        )

        assertEquals(ProviderHealthStatus.Stale, stale.status)
        assertEquals(ProviderHealthStatus.Unvalidated, unvalidated.status)
    }

    @Test
    fun inconsistentAggregateCountersCannotAppearHealthy() {
        val inconsistent = ProviderHealthAssessmentRules.assess(
            sample = sample(attempts = 3, successes = 3, timeouts = 1),
            now = now
        )
        val future = ProviderHealthAssessmentRules.assess(
            sample = sample(attempts = 3, successes = 3, at = now.plusSeconds(60)),
            now = now
        )

        assertEquals(ProviderHealthStatus.Unavailable, inconsistent.status)
        assertEquals(ProviderHealthDataQuality.Invalid, inconsistent.dataQuality)
        assertTrue(inconsistent.dataQualityMessage.orEmpty().contains("do not equal", ignoreCase = true))
        assertEquals(ProviderHealthStatus.Unavailable, future.status)
        assertEquals(ProviderHealthDataQuality.Invalid, future.dataQuality)
        assertTrue(future.dataQualityMessage.orEmpty().contains("future", ignoreCase = true))
    }

    @Test
    fun reportUsesCatalogIdsAndCannotBeInflatedByOrphanRecords() {
        val report = ProviderCatalogV2.healthReport(
            samples = listOf(
                sample(providerId = "GitHub", attempts = 3, successes = 3),
                sample(providerId = "orphan-provider", attempts = 30, successes = 30)
            ),
            now = now
        )

        assertEquals(ProviderCatalogV2.definitions.size, report.knownProviderCount)
        assertEquals(1, report.observedProviderCount)
        assertEquals(1, report.healthyCount)
        assertTrue(report.unvalidatedCount > 0)
        assertFalse(report.assessments.any { it.providerId == "orphan-provider" })
    }

    @Test
    fun persistedRecordConversionKeepsAggregateOnlyFieldsAndTimestamp() {
        val record = PersistentProviderHealthStore.Record(
            providerId = "github",
            attempts = 4,
            successes = 2,
            notFound = 1,
            timeouts = 1,
            latencyEwmaMs = 123.9,
            lastValidatedAtUtc = now.toString()
        )

        val sample = record.toHealthSample()

        assertEquals("github", sample.providerId)
        assertEquals(4L, sample.attempts)
        assertEquals(2L, sample.successes)
        assertEquals(1L, sample.notFound)
        assertEquals(123L, sample.latencyMs)
        assertEquals(now, sample.lastValidatedAt)
    }

    @Test
    fun pinnedUsernameCatalogHealthReportUsesExecutableSitesOnly() {
        val asset = listOf(
            java.io.File("src/main/assets/providers/whatsmyname/wmn-data.json"),
            java.io.File("app/src/main/assets/providers/whatsmyname/wmn-data.json")
        ).firstOrNull(java.io.File::isFile)
            ?: throw AssertionError("Pinned catalog asset is missing from the test checkout")
        val bytes = asset.readBytes()
        val ready = WhatsMyNameCatalog.parse(bytes) as? WhatsMyNameCatalogState.Ready
            ?: throw AssertionError("Pinned catalog did not parse as ready")
        val firstSite = ready.sites.first()

        val report = ready.healthReport(
            samples = listOf(
                sample(providerId = firstSite.id, attempts = 3, notFound = 3),
                sample(providerId = "not-in-catalog", attempts = 3, successes = 3)
            ),
            now = now
        )

        assertEquals(ready.executableCount, report.knownProviderCount)
        assertEquals(1, report.observedProviderCount)
        assertEquals(1, report.healthyCount)
        assertFalse(report.assessments.any { it.providerId == "not-in-catalog" })
    }

    private fun sample(
        providerId: String = "test-provider",
        attempts: Long,
        successes: Long = 0,
        notFound: Long = 0,
        softNotFound: Long = 0,
        timeouts: Long = 0,
        rateLimited: Long = 0,
        authenticationRequired: Long = 0,
        unsupportedAutomation: Long = 0,
        providerChanged: Long = 0,
        parseFailures: Long = 0,
        networkFailures: Long = 0,
        at: Instant? = now.minus(Duration.ofHours(1))
    ) = ProviderHealthSample(
        providerId = providerId,
        attempts = attempts,
        successes = successes,
        notFound = notFound,
        softNotFound = softNotFound,
        timeouts = timeouts,
        rateLimited = rateLimited,
        authenticationRequired = authenticationRequired,
        unsupportedAutomation = unsupportedAutomation,
        providerChanged = providerChanged,
        parseFailures = parseFailures,
        networkFailures = networkFailures,
        latencyMs = 100,
        lastValidatedAt = at
    )
}
