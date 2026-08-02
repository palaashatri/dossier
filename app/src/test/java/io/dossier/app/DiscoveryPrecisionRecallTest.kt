package io.dossier.app

import io.dossier.app.data.web.DiscoveryBenchmark
import io.dossier.app.data.web.PublicPageVerifier
import io.dossier.app.data.web.PublicSearchDiscoveryService
import io.dossier.app.data.web.StableProfileApiResolver
import io.dossier.app.domain.model.IdentityInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryPrecisionRecallTest {

    @Test
    fun searchQueries_prioritizeHighEntropySignalsBeforeBroadNameFanout() {
        val input = IdentityInput(
            fullName = "Jane Doe",
            primaryUsername = "janedoe",
            emails = listOf("jane.doe@example.com"),
            phones = listOf("+1 415 555 2671"),
            organizations = listOf("Example Labs")
        )

        val queries = PublicSearchDiscoveryService.buildSearchQueries(input)
        val emailIndex = queries.indexOf("\"jane.doe@example.com\"")
        val phoneIndex = queries.indexOf("\"14155552671\"")
        val handleIndex = queries.indexOf("\"janedoe\"")
        val broadNameIndex = queries.indexOfFirst { it == "\"Jane Doe\"" }

        assertTrue(emailIndex >= 0)
        assertTrue(phoneIndex >= 0)
        assertTrue(handleIndex >= 0)
        assertTrue(broadNameIndex >= 0)
        assertTrue(emailIndex < broadNameIndex)
        assertTrue(phoneIndex < broadNameIndex)
        assertTrue(handleIndex < broadNameIndex)
    }

    @Test
    fun directVerification_nameOnlyMatchCannotBecomeHighConfidence() {
        val assessment = PublicPageVerifier.assessIdentitySignals(
            input = IdentityInput(fullName = "Jane Doe"),
            url = "https://example.com/team/jane-doe",
            pageText = "Jane Doe writes about software."
        )

        assertTrue(assessment.directScore > 0f)
        assertTrue(assessment.confidenceCeiling <= 0.60f)
    }

    @Test
    fun directVerification_exactHandlePlusContextCanBecomeStrong() {
        val assessment = PublicPageVerifier.assessIdentitySignals(
            input = IdentityInput(
                fullName = "Jane Doe",
                primaryUsername = "janedoe",
                organizations = listOf("Example Labs")
            ),
            url = "https://github.com/janedoe",
            pageText = "Jane Doe — engineer at Example Labs — janedoe"
        )

        assertTrue(assessment.directScore >= 0.90f)
        assertTrue(assessment.confidenceCeiling >= 0.95f)
        assertTrue(assessment.signals.size >= 3)
    }

    @Test
    fun directVerification_exactEmailGetsHighestEvidenceTier() {
        val assessment = PublicPageVerifier.assessIdentitySignals(
            input = IdentityInput(
                fullName = "Jane Doe",
                emails = listOf("jane.doe@example.com")
            ),
            url = "https://example.com/contact",
            pageText = "Contact Jane Doe at jane.doe@example.com"
        )

        assertTrue(assessment.directScore >= 0.95f)
        assertEquals(0.97f, assessment.confidenceCeiling)
    }

    @Test
    fun providerConsensusRaisesIndexedConfidence() {
        val input = IdentityInput(fullName = "Jane Doe", primaryUsername = "janedoe")
        val oneProvider = PublicSearchDiscoveryService.PublicSearchResult(
            title = "Jane Doe - GitHub",
            snippet = "janedoe open source profile",
            url = "https://github.com/janedoe",
            query = "\"janedoe\" site:github.com",
            source = "DuckDuckGo",
            providerCount = 1
        )
        val twoProviders = oneProvider.copy(source = "DuckDuckGo+Bing", providerCount = 2)

        assertTrue(
            PublicSearchDiscoveryService.scoreResult(input, twoProviders) >
                PublicSearchDiscoveryService.scoreResult(input, oneProvider)
        )
    }

    @Test
    fun structuredResolver_supportsStackOverflowKeybaseAndFederatedProfiles() {
        val stack = StableProfileApiResolver.endpointFor(
            "https://stackoverflow.com/users/12345/jane-doe"
        )
        val keybase = StableProfileApiResolver.endpointFor("https://keybase.io/janedoe")
        val fediverse = StableProfileApiResolver.endpointFor("https://fosstodon.org/@janedoe")

        assertEquals(StableProfileApiResolver.Kind.STACK_OVERFLOW, stack?.kind)
        assertEquals(StableProfileApiResolver.Kind.KEYBASE, keybase?.kind)
        assertEquals(StableProfileApiResolver.Kind.FEDIVERSE, fediverse?.kind)
        assertEquals(false, fediverse?.authoritativeNotFound)
    }

    @Test
    fun benchmarkComputesPrecisionRecallAndUnverifiableAccuracy() {
        val metrics = DiscoveryBenchmark.evaluate(
            listOf(
                DiscoveryBenchmark.Observation("owned-1", DiscoveryBenchmark.Expected.BELONGS, DiscoveryBenchmark.Actual.VERIFIED),
                DiscoveryBenchmark.Observation("owned-2", DiscoveryBenchmark.Expected.BELONGS, DiscoveryBenchmark.Actual.NOT_FOUND),
                DiscoveryBenchmark.Observation("other-1", DiscoveryBenchmark.Expected.DOES_NOT_BELONG, DiscoveryBenchmark.Actual.NOT_FOUND),
                DiscoveryBenchmark.Observation("other-2", DiscoveryBenchmark.Expected.DOES_NOT_BELONG, DiscoveryBenchmark.Actual.VERIFIED),
                DiscoveryBenchmark.Observation("wall-1", DiscoveryBenchmark.Expected.UNVERIFIABLE, DiscoveryBenchmark.Actual.UNVERIFIABLE)
            )
        )

        assertEquals(0.5, metrics.precision, 0.0001)
        assertEquals(0.5, metrics.recall, 0.0001)
        assertEquals(0.5, metrics.specificity, 0.0001)
        assertEquals(0.2, metrics.unverifiableAccuracy, 0.0001)
    }
}
