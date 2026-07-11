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
}
