package io.dossier.app

import io.dossier.app.domain.model.Platform
import io.dossier.app.domain.scanner.HandleExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [HandleExtractor] — the pivot-discovery core. These lock in the
 * behavior that lets the app find handles like "samplecaster" that don't derive
 * from a name, by reading self-disclosed links/mentions in confirmed profiles.
 */
class HandleExtractorTest {

    private fun extract(
        text: String,
        links: List<String> = emptyList(),
        sourceUrl: String = "https://github.com/janedoe",
        scanned: Set<String> = emptySet(),
        sourceLabel: String = "GitHub"
    ) = HandleExtractor.extract(text, links, sourceUrl, scanned, sourceLabel)

    @Test
    fun extractsTwitchHandleFromSelfDisclosedUrl() {
        // The samplecaster case: a Twitch link mentioned in a confirmed profile.
        val results = extract(
            text = "Hi, I'm Jane. Catch my streams at https://www.twitch.tv/samplecaster",
            links = listOf("https://www.twitch.tv/samplecaster")
        )
        val twitch = results.firstOrNull { it.candidate.platform == Platform.Twitch }
        assertTrue("Should discover the Twitch handle", twitch != null)
        assertEquals("samplecaster", twitch!!.candidate.username)
        assertEquals("https://www.twitch.tv/samplecaster", twitch.candidate.url)
        assertTrue("Provenance should cite the source profile", twitch.provenance.contains("GitHub"))
    }

    @Test
    fun extractsMultiplePlatformHandlesFromLinks() {
        val results = extract(
            text = "",
            links = listOf(
                "https://www.reddit.com/user/sampleuser",
                "https://gitlab.com/jdoe",
                "https://x.com/janedoe"
            )
        )
        val platforms = results.map { it.candidate.platform }
        assertTrue("Reddit handle extracted", platforms.contains(Platform.Reddit))
        assertTrue("GitLab handle extracted", platforms.contains(Platform.GitLab))
    }

    @Test
    fun extractsAtMentionHandlesWithPlatformContext() {
        // "also on twitch as samplecaster" phrase.
        val results = extract(
            text = "I stream games, also on twitch as samplecaster on weekends."
        )
        val twitch = results.firstOrNull { it.candidate.platform == Platform.Twitch }
        assertTrue("Should catch bare handle via mention phrase", twitch != null)
        assertEquals("samplecaster", twitch!!.candidate.username)
    }

    @Test
    fun extractsPlatformColonAtHandlePhrase() {
        val results = extract(
            text = "Find me elsewhere — reddit: @sampleuser, instagram: @jane.doe"
        )
        val reddit = results.firstOrNull { it.candidate.platform == Platform.Reddit }
        val insta = results.firstOrNull { it.candidate.platform == Platform.Instagram }
        assertTrue("Reddit via colon phrase", reddit != null)
        assertTrue("Instagram via colon phrase", insta != null)
    }

    @Test
    fun excludesSourceProfileSelfMention() {
        // The audited profile is GitHub/janedoe — a self-link to it shouldn't
        // re-surface as a new candidate.
        val results = extract(
            text = "My GitHub is https://github.com/janedoe and twitch https://www.twitch.tv/samplecaster",
            links = listOf("https://github.com/janedoe"),
            sourceUrl = "https://github.com/janedoe"
        )
        val github = results.firstOrNull { it.candidate.platform == Platform.GitHub }
        assertFalse("Must not re-discover the source profile itself", github != null)
    }

    @Test
    fun excludesAlreadyScannedUrls() {
        val results = extract(
            text = "https://www.twitch.tv/samplecaster",
            links = listOf("https://www.twitch.tv/samplecaster"),
            scanned = setOf("https://www.twitch.tv/samplecaster")
        )
        assertTrue("Must not re-scan already-checked URLs", results.isEmpty())
    }

    @Test
    fun filtersNonProfileDestinations() {
        val results = extract(
            text = "",
            links = listOf(
                "https://www.twitch.tv/home",
                "https://x.com/login",
                "https://www.twitch.tv/directory",
                "https://www.reddit.com/search"
            )
        )
        assertTrue("Home/login/directory/search URLs must be filtered out", results.isEmpty())
    }

    @Test
    fun filtersNoreplyGithubEmailHost() {
        val results = extract(
            text = "Contact: jane@users.noreply.github.com",
            links = emptyList()
        )
        // The noreply host fragment shouldn't become a handle.
        val github = results.firstOrNull { it.candidate.platform == Platform.GitHub }
        assertFalse("Must not treat noreply.github.com as a handle", github != null)
    }

    @Test
    fun dedupesByUrlAcrossLinksAndText() {
        val results = extract(
            text = "stream at https://www.twitch.tv/samplecaster see you there",
            links = listOf("https://www.twitch.tv/samplecaster")
        )
        val twitch = results.filter { it.candidate.platform == Platform.Twitch }
        assertEquals("Should dedupe the same URL found in text and links", 1, twitch.size)
    }

    @Test
    fun provenanceRecordsSourcePlatformLabel() {
        val results = extract(
            text = "https://www.twitch.tv/samplecaster",
            sourceUrl = "https://x.com/janedoe",
            sourceLabel = "X"
        )
        val twitch = results.first()
        assertEquals("discovered via X profile", twitch.provenance)
    }
}
