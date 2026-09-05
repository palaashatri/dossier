package io.dossier.app

import io.dossier.app.data.web.PublicSearchDiscoveryService
import io.dossier.app.domain.model.IdentityInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicSearchDiscoveryServiceTest {

    @Test
    fun buildQueries_includesProfileAndPublicForumSources() {
        val input = IdentityInput(
            fullName = "Jane Doe",
            primaryUsername = "janedoe",
            usernames = listOf("jane_doe")
        )

        val queries = PublicSearchDiscoveryService.buildSearchQueries(input)

        assertTrue("Should search GitHub profile indexes", queries.any { it.contains("site:github.com") })
        assertTrue("Should search LinkedIn profile indexes", queries.any { it.contains("site:linkedin.com/in") })
        assertTrue("Should search Reddit evidence", queries.any { it.contains("site:reddit.com") })
        assertTrue("Should search 4chan evidence", queries.any { it.contains("site:4chan.org") })
        assertTrue("Should include exact handle search", queries.any { it == "\"janedoe\"" })
    }

    @Test
    fun normalizeSearchUrl_unwrapsDuckDuckGoRedirects() {
        val raw = "/l/?kh=-1&uddg=https%3A%2F%2Fgithub.com%2Fjanedoe%3Ftab%3Drepositories"

        val normalized = PublicSearchDiscoveryService.normalizeSearchUrl(raw)

        assertEquals("https://github.com/janedoe?tab=repositories", normalized)
    }

    @Test
    fun parseSearchResults_extractsGenericResultBlocks() {
        val html = """
            <html><body>
              <div class="result">
                <a class="result__a" href="/l/?uddg=https%3A%2F%2Fgithub.com%2Fjanedoe">Jane Doe - GitHub</a>
                <a class="result__snippet">Jane Doe builds Android privacy tools.</a>
              </div>
            </body></html>
        """.trimIndent()

        val results = PublicSearchDiscoveryService.parseSearchResults("DuckDuckGo", "\"Jane Doe\"", html)

        assertEquals(1, results.size)
        assertEquals("https://github.com/janedoe", results.first().url)
        assertTrue(results.first().snippet.contains("privacy tools"))
    }

    @Test
    fun scoreResult_boostsNameHandleAndKnownProfileUrl() {
        val input = IdentityInput(
            fullName = "Jane Doe",
            primaryUsername = "janedoe"
        )
        val result = PublicSearchDiscoveryService.PublicSearchResult(
            title = "Jane Doe - GitHub",
            snippet = "Follow janedoe's open source work.",
            url = "https://github.com/janedoe",
            query = "\"Jane Doe\" site:github.com",
            source = "DuckDuckGo"
        )

        val score = PublicSearchDiscoveryService.scoreResult(input, result)

        assertTrue("High-signal profile result should score strongly", score >= 0.80f)
        assertNotNull(score)
    }

    @Test
    fun buildQueries_includesPhoneDigitsAndEmailLocalPart() {
        val input = IdentityInput(
            fullName = "Jane Doe",
            primaryUsername = "janedoe",
            emails = listOf("jane.doe@example.com"),
            phones = listOf("+1 (415) 555-2671")
        )

        val queries = PublicSearchDiscoveryService.buildSearchQueries(input)

        assertTrue(
            "Should include digits-only phone query",
            queries.any { it.contains("4155552671") }
        )
        assertTrue(
            "Should include email local-part as handle-like query",
            queries.any { it == "\"jane.doe\"" }
        )
        assertTrue(
            "Exact email still searched",
            queries.any { it.contains("jane.doe@example.com") }
        )
    }

    @Test
    fun buildQueries_deepResearchAddsEmailSiteProbes() {
        val input = IdentityInput(
            fullName = "Jane Doe",
            emails = listOf("jane.doe@example.com")
        )

        val defaultQueries = PublicSearchDiscoveryService.buildSearchQueries(input, deepResearch = false)
        val deepQueries = PublicSearchDiscoveryService.buildSearchQueries(input, deepResearch = true)

        assertTrue(
            "Default mode should not include pastebin email site probe",
            defaultQueries.none { it.contains("site:pastebin.com") }
        )
        assertTrue(
            "Deep research should probe pastebin for email",
            deepQueries.any { it.contains("site:pastebin.com") && it.contains("jane.doe@example.com") }
        )
        assertTrue(
            "Deep research should probe github for email",
            deepQueries.any { it.contains("site:github.com") && it.contains("jane.doe@example.com") }
        )
    }

    @Test
    fun buildQueries_includesSafeTypedSeedsWithinLimits() {
        val input = IdentityInput(fullName = "Jane Doe")
        val safeSeeds = listOf(
            io.dossier.app.domain.discovery.TypedSeed(
                kind = io.dossier.app.domain.discovery.TypedSeedKind.Url,
                value = "https://safe.example.test/profile",
                exactValue = "https://safe.example.test/profile",
                isVerified = true,
                depth = 1,
                evidenceState = io.dossier.app.domain.evidence.EvidenceState.Verified,
                origin = io.dossier.app.domain.discovery.TypedSeedOrigin.Evidence,
                sourceClassification = io.dossier.app.domain.evidence.ExposureSourceClassification.PUBLIC_PROFILE,
                evidenceIds = listOf("ev1"),
                sourceUrl = "https://profile.example.test/jane"
            ),
            io.dossier.app.domain.discovery.TypedSeed(
                kind = io.dossier.app.domain.discovery.TypedSeedKind.Domain,
                value = "safe.example.test",
                exactValue = "safe.example.test",
                isVerified = true,
                depth = 1,
                evidenceState = io.dossier.app.domain.evidence.EvidenceState.Verified,
                origin = io.dossier.app.domain.discovery.TypedSeedOrigin.Evidence,
                sourceClassification = io.dossier.app.domain.evidence.ExposureSourceClassification.PUBLIC_WEB,
                evidenceIds = listOf("ev2"),
                sourceUrl = "https://profile.example.test/jane"
            )
        )

        val queries = PublicSearchDiscoveryService.buildSearchQueries(input, typedSeeds = safeSeeds)

        assertTrue("Emits quoted URL", queries.contains("\"https://safe.example.test/profile\""))
        assertTrue("Emits quoted domain", queries.contains("\"safe.example.test\""))
        assertTrue("Emits site:domain for domains", queries.contains("site:safe.example.test"))
    }

    @Test
    fun buildQueries_rejectsUnsafeTypedSeeds() {
        val input = IdentityInput(fullName = "Jane Doe")
        val unsafeSeeds = listOf(
            // Unverified candidate
            io.dossier.app.domain.discovery.TypedSeed(
                kind = io.dossier.app.domain.discovery.TypedSeedKind.Url,
                value = "https://candidate.example.test",
                exactValue = "https://candidate.example.test",
                depth = 1,
                evidenceState = io.dossier.app.domain.evidence.EvidenceState.Candidate,
                origin = io.dossier.app.domain.discovery.TypedSeedOrigin.Candidate,
                sourceClassification = io.dossier.app.domain.evidence.ExposureSourceClassification.PUBLIC_WEB
            ),
            // Breach index
            io.dossier.app.domain.discovery.TypedSeed(
                kind = io.dossier.app.domain.discovery.TypedSeedKind.Domain,
                value = "breach.example.test",
                exactValue = "breach.example.test",
                depth = 1,
                evidenceState = io.dossier.app.domain.evidence.EvidenceState.Observed,
                origin = io.dossier.app.domain.discovery.TypedSeedOrigin.Evidence,
                sourceClassification = io.dossier.app.domain.evidence.ExposureSourceClassification.BREACH_INDEX
            ),
            // Image (not in allowed kinds)
            io.dossier.app.domain.discovery.TypedSeed(
                kind = io.dossier.app.domain.discovery.TypedSeedKind.Image,
                value = "https://image.example.test/img.png",
                exactValue = "https://image.example.test/img.png",
                isVerified = true,
                depth = 1,
                evidenceState = io.dossier.app.domain.evidence.EvidenceState.Verified,
                origin = io.dossier.app.domain.discovery.TypedSeedOrigin.Evidence,
                sourceClassification = io.dossier.app.domain.evidence.ExposureSourceClassification.PUBLIC_WEB
            )
        )

        val queries = PublicSearchDiscoveryService.buildSearchQueries(input, typedSeeds = unsafeSeeds)

        assertTrue(queries.none { it.contains("candidate.example.test") })
        assertTrue(queries.none { it.contains("breach.example.test") })
        assertTrue(queries.none { it.contains("image.example.test") })
    }

    @Test
    fun buildSearchQueryPlan_includesSafeEmailAndPhoneSeedsBeforeOriginalTerms() {
        val input = IdentityInput(fullName = "Jane Doe", emails = listOf("original@example.test"))
        val safeSeeds = listOf(
            io.dossier.app.domain.discovery.TypedSeed(
                kind = io.dossier.app.domain.discovery.TypedSeedKind.Email,
                value = "safe@example.test",
                exactValue = "safe@example.test",
                normalizedValue = "safe@example.test",
                isVerified = true,
                depth = 1,
                evidenceState = io.dossier.app.domain.evidence.EvidenceState.Verified,
                origin = io.dossier.app.domain.discovery.TypedSeedOrigin.Evidence,
                sourceClassification = io.dossier.app.domain.evidence.ExposureSourceClassification.PUBLIC_PROFILE,
                evidenceIds = listOf("ev-email"),
                sourceUrl = "https://profile.example.test/jane"
            ),
            io.dossier.app.domain.discovery.TypedSeed(
                kind = io.dossier.app.domain.discovery.TypedSeedKind.Phone,
                value = "15551234567",
                exactValue = "+1 (555) 123-4567",
                normalizedValue = "15551234567",
                isVerified = true,
                depth = 1,
                evidenceState = io.dossier.app.domain.evidence.EvidenceState.Verified,
                origin = io.dossier.app.domain.discovery.TypedSeedOrigin.UserInput,
                sourceClassification = io.dossier.app.domain.evidence.ExposureSourceClassification.USER_IMPORTED,
                evidenceIds = emptyList()
            )
        )

        val plan = PublicSearchDiscoveryService.buildSearchQueryPlan(input, typedSeeds = safeSeeds)
        val queries = plan.map { it.query }

        assertTrue("Emits quoted email", queries.contains("\"safe@example.test\""))
        assertTrue("Emits quoted exact phone", queries.contains("\"+1 (555) 123-4567\""))
        assertTrue("Emits quoted normalized phone", queries.contains("\"15551234567\""))

        val emailIndex = queries.indexOf("\"safe@example.test\"")
        val originalEmailIndex = queries.indexOf("\"original@example.test\"")
        assertTrue("Safe seeds should appear before original term exact queries", emailIndex < originalEmailIndex)
    }

    @Test
    fun buildSearchQueryPlan_rejectsCandidateBreachImportOffSourceSeeds() {
        val input = IdentityInput(fullName = "Jane Doe")
        val unsafeSeeds = listOf(
            // Unverified candidate Email
            io.dossier.app.domain.discovery.TypedSeed(
                kind = io.dossier.app.domain.discovery.TypedSeedKind.Email,
                value = "candidate@example.test",
                exactValue = "candidate@example.test",
                normalizedValue = "candidate@example.test",
                depth = 1,
                evidenceState = io.dossier.app.domain.evidence.EvidenceState.Candidate,
                origin = io.dossier.app.domain.discovery.TypedSeedOrigin.Candidate,
                sourceClassification = io.dossier.app.domain.evidence.ExposureSourceClassification.PUBLIC_WEB
            ),
            // Breach index Phone
            io.dossier.app.domain.discovery.TypedSeed(
                kind = io.dossier.app.domain.discovery.TypedSeedKind.Phone,
                value = "5550001111",
                exactValue = "5550001111",
                normalizedValue = "5550001111",
                depth = 1,
                evidenceState = io.dossier.app.domain.evidence.EvidenceState.Observed,
                origin = io.dossier.app.domain.discovery.TypedSeedOrigin.Evidence,
                sourceClassification = io.dossier.app.domain.evidence.ExposureSourceClassification.BREACH_INDEX
            ),
            // Evidence Email with invalid sourceUrl
            io.dossier.app.domain.discovery.TypedSeed(
                kind = io.dossier.app.domain.discovery.TypedSeedKind.Email,
                value = "badurl@example.test",
                exactValue = "badurl@example.test",
                normalizedValue = "badurl@example.test",
                isVerified = true,
                depth = 1,
                evidenceState = io.dossier.app.domain.evidence.EvidenceState.Verified,
                origin = io.dossier.app.domain.discovery.TypedSeedOrigin.Evidence,
                sourceClassification = io.dossier.app.domain.evidence.ExposureSourceClassification.PUBLIC_WEB,
                evidenceIds = listOf("ev"),
                sourceUrl = "javascript:alert()"
            )
        )

        val plan = PublicSearchDiscoveryService.buildSearchQueryPlan(input, typedSeeds = unsafeSeeds)
        val queries = plan.map { it.query }

        assertTrue(queries.none { it.contains("candidate@example.test") })
        assertTrue(queries.none { it.contains("5550001111") })
        assertTrue(queries.none { it.contains("badurl@example.test") })
    }

    @Test
    fun buildSearchQueryPlan_preservesMetadataInEntries() {
        val input = IdentityInput(fullName = "Jane Doe")
        val safeSeeds = listOf(
            io.dossier.app.domain.discovery.TypedSeed(
                kind = io.dossier.app.domain.discovery.TypedSeedKind.Email,
                value = "meta@example.test",
                exactValue = "meta@example.test",
                normalizedValue = "meta@example.test",
                isVerified = true,
                depth = 2,
                evidenceState = io.dossier.app.domain.evidence.EvidenceState.Verified,
                origin = io.dossier.app.domain.discovery.TypedSeedOrigin.Evidence,
                sourceClassification = io.dossier.app.domain.evidence.ExposureSourceClassification.PUBLIC_WEB,
                evidenceIds = listOf("ev123"),
                sourceUrl = "https://source.example.test",
                discoveryPath = listOf("https://path.example.test")
            )
        )

        val plan = PublicSearchDiscoveryService.buildSearchQueryPlan(input, typedSeeds = safeSeeds)
        val entry = plan.find { it.query == "\"meta@example.test\"" }

        assertNotNull(entry)
        assertEquals("typed-seed-exact", entry?.stage)
        assertEquals(io.dossier.app.domain.discovery.TypedSeedKind.Email, entry?.pivotSeedKind)
        assertEquals("meta@example.test", entry?.pivotExactValue)
        assertEquals("meta@example.test", entry?.pivotNormalizedValue)
        assertEquals(listOf("ev123"), entry?.pivotEvidenceIds)
        assertEquals("https://source.example.test", entry?.pivotSourceUrl)
        assertEquals(listOf("https://path.example.test"), entry?.pivotDiscoveryPath)
    }

    @Test
    fun mergeProviderEvidence_retainsTypedMetadataForDuplicateMerge() {
        // We use Reflection to call the private method
        val method = PublicSearchDiscoveryService.Companion::class.java.getDeclaredMethod("mergeProviderEvidence", List::class.java)
        method.isAccessible = true

        val r1 = PublicSearchDiscoveryService.PublicSearchResult(
            title = "Result", snippet = "Snip", url = "https://example.test/profile", query = "query", source = "Google",
            pivotSeedKind = io.dossier.app.domain.discovery.TypedSeedKind.Email,
            pivotExactValue = "user@example.test",
            pivotEvidenceIds = listOf("ev1")
        )
        val r2 = PublicSearchDiscoveryService.PublicSearchResult(
            title = "Result better", snippet = "Snip better", url = "https://example.test/profile", query = "query", source = "Bing",
            pivotSeedKind = null // No pivot metadata
        )

        @Suppress("UNCHECKED_CAST")
        val merged = method.invoke(PublicSearchDiscoveryService.Companion, listOf(r1, r2)) as List<PublicSearchDiscoveryService.PublicSearchResult>

        assertEquals(1, merged.size)
        val best = merged.first()
        assertEquals("Result", best.title) // r1 wins because it has pivotSeedKind != null
        assertEquals("Google+Bing", best.source)
        assertEquals(2, best.providerCount)
        assertEquals(io.dossier.app.domain.discovery.TypedSeedKind.Email, best.pivotSeedKind)
        assertEquals("user@example.test", best.pivotExactValue)
        assertEquals(listOf("ev1"), best.pivotEvidenceIds)
    }
}
