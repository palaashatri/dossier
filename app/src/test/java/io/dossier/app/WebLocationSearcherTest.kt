package io.dossier.app

import io.dossier.app.data.web.WebLocationSearcher
import io.dossier.app.domain.model.ReverseImageLookupResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure (no-network) clue→query and location-resolution
 * logic in [WebLocationSearcher]. These lock in the Reverse Image Lookup
 * behavior: the best OCR text run + non-generic scene labels become the query,
 * and the most frequent place-like phrase in evidence wins as the resolved
 * location.
 */
class WebLocationSearcherTest {

    @Test
    fun buildQuery_prefersLongestLegibleOcrRun_andCapsLabels() {
        val query = WebLocationSearcher.buildSearchQuery(
            textClues = "Welcome to 42! Jane Doe Gare du Nord platform 7",
            labelClues = listOf("Mountain", "Sky", "Monument", "Signage", "Tree")
        )
        // The longest contiguous alpha run should win as the text portion. Short
        // connector words (>= 2 chars) survive so place names stay intact.
        assertTrue("Query should include the OCR text run", query.contains("Jane Doe Gare du Nord platform"))
        // Generic labels (Sky, Tree) must be filtered out; up to 3 non-generic labels kept.
        assertTrue("Non-generic labels included", query.contains("Mountain"))
        assertTrue("Non-generic labels included", query.contains("Monument"))
        assertTrue("Non-generic labels included", query.contains("Signage"))
    }

    @Test
    fun buildQuery_returnsBlankWhenNoClues() {
        assertEquals("", WebLocationSearcher.buildSearchQuery(null, emptyList()))
        assertEquals("", WebLocationSearcher.buildSearchQuery("   ", listOf("Sky", "Tree")))
    }

    @Test
    fun buildQuery_filtersShortAndNonAlphaTokens_andGenericLabels() {
        // Single-char tokens and digit-leading tokens are dropped from the text run.
        // Generic scene labels (e.g. "Water") are filtered out.
        val query = WebLocationSearcher.buildSearchQuery("a 12 hi Tower Bridge view", listOf("Water", "Harbor"))
        assertTrue("Long alpha tokens preserved", query.contains("Tower Bridge view"))
        assertTrue("Non-generic label preserved", query.contains("Harbor"))
    }

    @Test
    fun resolveLocation_picksMostFrequentPlacePhraseFromEvidence() {
        val evidence = listOf(
            ReverseImageLookupResult.WebEvidence(
                title = "Gare du Nord — Wikipedia",
                snippet = "The Gare du Nord is a major railway station in Paris.",
                url = "https://example.com/1"
            ),
            ReverseImageLookupResult.WebEvidence(
                title = "Visiting Gare du Nord",
                snippet = "Gare du Nord connects to multiple lines.",
                url = "https://example.com/2"
            )
        )
        val resolved = WebLocationSearcher.resolveLocationFromEvidence("railway station", evidence)
        // The multi-word place name (with lowercase connector) should resolve as a unit.
        assertEquals("Gare du Nord", resolved)
    }

    @Test
    fun resolveLocation_fallsBackToQueryWhenNoPlacePhrase() {
        val evidence = listOf(
            ReverseImageLookupResult.WebEvidence(
                title = "results",
                snippet = "no location info here",
                url = "https://example.com"
            )
        )
        val resolved = WebLocationSearcher.resolveLocationFromEvidence("some random query", evidence)
        assertEquals("some random query", resolved)
    }

    @Test
    fun resolveLocation_returnsNullForBlankQuery() {
        assertEquals(null, WebLocationSearcher.resolveLocationFromEvidence("", emptyList()))
    }
}
