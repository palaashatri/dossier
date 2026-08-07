package io.dossier.app.discovery

import io.dossier.app.data.platform.PLATFORMS
import io.dossier.app.data.platform.ProviderCatalogV2
import io.dossier.app.domain.discovery.ExistenceRules
import io.dossier.app.domain.discovery.ProviderCategory
import io.dossier.app.domain.discovery.ProviderDefinition
import io.dossier.app.domain.discovery.ProviderDefinitionValidator
import io.dossier.app.domain.discovery.ProviderHealthTracker
import io.dossier.app.domain.discovery.ProviderOutcome
import io.dossier.app.domain.discovery.ProviderRequestPolicy
import io.dossier.app.domain.discovery.QueryCapability
import io.dossier.app.domain.discovery.ScanMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCatalogV2Test {

    @Test
    fun catalogIsSchemaValidAndLargeEnoughToProveScalablePath() {
        assertTrue(
            "Provider catalog issues: ${ProviderCatalogV2.schemaIssues.joinToString { it.message }}",
            ProviderCatalogV2.schemaIssues.isEmpty()
        )
        assertTrue("Do not regress the declarative catalog back to a tiny hand-written list", ProviderCatalogV2.definitions.size >= 70)
        assertEquals(ProviderCatalogV2.definitions.size, ProviderCatalogV2.schemaValidCount())
    }

    @Test
    fun scanModesUseActualScheduledProviderCounts() {
        val quick = ProviderCatalogV2.plan(ScanMode.Quick)
        val standard = ProviderCatalogV2.plan(ScanMode.Standard)
        val deep = ProviderCatalogV2.plan(ScanMode.Deep)
        val exhaustive = ProviderCatalogV2.plan(ScanMode.Exhaustive)

        assertEquals(quick.providers.size, quick.scheduledProviderCount)
        assertEquals(standard.providers.size, standard.scheduledProviderCount)
        assertEquals(deep.providers.size, deep.scheduledProviderCount)
        assertEquals(exhaustive.providers.size, exhaustive.scheduledProviderCount)

        assertTrue(quick.providers.size <= 50)
        assertTrue(standard.providers.size >= quick.providers.size)
        assertTrue(deep.providers.size >= standard.providers.size)
        assertTrue(exhaustive.providers.size >= deep.providers.size)
        assertFalse(quick.providers.any { it.category == ProviderCategory.Archive })
        assertTrue(deep.providers.any { it.category == ProviderCategory.Archive })
    }

    @Test
    fun disabledProvidersNeverEnterDefaultPlans() {
        val allPlannedIds = ScanMode.entries
            .flatMap { ProviderCatalogV2.plan(it).providers }
            .map { it.id }
            .toSet()

        assertFalse("facebook" in allPlannedIds)
        assertFalse("linkedin" in allPlannedIds)
        assertFalse("discord" in allPlannedIds)
    }

    @Test
    fun legacyAdapterConsumesV2CatalogInsteadOfSeparateList() {
        val urls = PLATFORMS.map { it.urlPattern }.toSet()
        assertTrue("https://github.com/{username}" in urls)
        assertTrue("https://codeberg.org/{username}" in urls)
        assertTrue("https://www.npmjs.com/~{username}" in urls)
        assertFalse("https://{username}.itch.io/" in urls)
    }

    @Test
    fun registryValidatorRejectsUnsafeOrContradictoryDefinitions() {
        val invalid = ProviderDefinition(
            id = "Bad Provider",
            displayName = "Bad",
            category = ProviderCategory.Social,
            profileUrlTemplate = "http://example.test/profile",
            queryCapabilities = setOf(QueryCapability.Username),
            existenceRules = ExistenceRules(requiredStatus = setOf(200, 404), notFoundStatus = setOf(404)),
            priority = 101,
            requestPolicy = ProviderRequestPolicy(maxConcurrency = 0, timeoutMs = 10)
        )

        val issues = ProviderDefinitionValidator.validate(invalid)
        assertTrue(issues.size >= 5)
    }

    @Test
    fun providerHealthTracksFailureClassesAndMedianLatency() {
        val tracker = ProviderHealthTracker()
        tracker.record("github", ProviderOutcome.Success, 100)
        tracker.record("github", ProviderOutcome.Success, 300)
        tracker.record("github", ProviderOutcome.RateLimited, 200)

        val snapshot = tracker.snapshot("github")
        assertEquals(3L, snapshot.attempts)
        assertEquals(2L, snapshot.successes)
        assertEquals(1L, snapshot.rateLimited)
        assertEquals(200L, snapshot.medianLatencyMs)
        assertEquals(2.0 / 3.0, snapshot.successRate, 0.0001)
    }
}
