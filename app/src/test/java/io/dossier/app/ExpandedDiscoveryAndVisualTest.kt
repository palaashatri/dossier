package io.dossier.app

import io.dossier.app.data.image.VisualFingerprint
import io.dossier.app.data.web.PublicSearchDiscoveryService
import io.dossier.app.data.web.ReverseImageCandidateSearchService
import io.dossier.app.domain.model.IdentityInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpandedDiscoveryAndVisualTest {

    @Test
    fun yandexParserExtractsOrganicResult() {
        val html = """
            <ol><li class="serp-item"><div class="Organic">
              <h2><a class="OrganicTitle-Link" href="https://example.org/profile/janedoe">Jane Doe</a></h2>
              <div class="OrganicTextContentSpan">Jane Doe developer profile</div>
            </div></li></ol>
        """.trimIndent()

        val results = PublicSearchDiscoveryService.parseSearchResults("Yandex", "janedoe", html)
        assertEquals(1, results.size)
        assertEquals("https://example.org/profile/janedoe", results.first().url)
    }

    @Test
    fun braveParserExtractsSnippetResult() {
        val html = """
            <article class="snippet">
              <h2><a data-testid="result-header" href="https://example.net/u/jane">Jane</a></h2>
              <p class="snippet-description">Public profile for janedoe</p>
            </article>
        """.trimIndent()

        val results = PublicSearchDiscoveryService.parseSearchResults("Brave", "janedoe", html)
        assertEquals(1, results.size)
        assertTrue(results.first().snippet.contains("janedoe"))
    }

    @Test
    fun mojeekParserExtractsIndependentIndexResult() {
        val html = """
            <ul class="results-standard"><li>
              <h2><a class="title" href="https://example.com/janedoe">Jane Doe</a></h2>
              <p class="s">Independent-index result</p>
            </li></ul>
        """.trimIndent()

        val results = PublicSearchDiscoveryService.parseSearchResults("Mojeek", "janedoe", html)
        assertEquals(1, results.size)
    }

    @Test
    fun reverseImageQueriesUseOcrLabelsAndIdentityWithoutUploadingImage() {
        val queries = ReverseImageCandidateSearchService.buildQueries(
            extractedText = "Conference 2026\nExample Labs",
            labels = listOf("stage", "auditorium", "conference"),
            identity = IdentityInput(fullName = "Jane Doe", primaryUsername = "janedoe"),
            deepResearch = false
        )

        assertTrue(queries.any { it.contains("Conference 2026") })
        assertTrue(queries.any { it.contains("stage") })
        assertTrue(queries.any { it.contains("Jane Doe") && it.contains("avatar") })
        assertTrue(queries.any { it.contains("janedoe") && it.contains("profile photo") })
    }

    @Test
    fun perceptualHashSimilarityUsesHammingDistance() {
        assertEquals(1f, VisualFingerprint.hashSimilarity(0L, 0L), 0.0001f)
        assertEquals(0f, VisualFingerprint.hashSimilarity(0L, -1L), 0.0001f)
        assertEquals(63f / 64f, VisualFingerprint.hashSimilarity(0L, 1L), 0.0001f)
    }

    @Test
    fun histogramIntersectionIsNormalized() {
        val first = floatArrayOf(0.5f, 0.5f)
        val second = floatArrayOf(0.25f, 0.75f)
        assertEquals(0.75f, VisualFingerprint.histogramIntersection(first, second), 0.0001f)
    }
}
