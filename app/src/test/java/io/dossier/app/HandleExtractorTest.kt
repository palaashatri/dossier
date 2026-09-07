package io.dossier.app

import io.dossier.app.domain.model.Platform
import io.dossier.app.domain.scanner.HandleExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for public-profile pivot extraction and admission. */
class HandleExtractorTest {

    private fun extract(
        text: String,
        links: List<String> = emptyList(),
        sourceUrl: String = "https://github.com/janedoe",
        scanned: Set<String> = emptySet(),
        sourceLabel: String = "GitHub"
    ) = HandleExtractor.extract(text, links, sourceUrl, scanned, sourceLabel)

    @Test
    fun extractsTwitchHandleFromExplicitPublicUrl() {
        val results = extract(
            text = "Hi, I'm Jane. Catch my streams at https://www.twitch.tv/samplecaster",
            links = listOf("https://www.twitch.tv/samplecaster")
        )
        val twitch = results.firstOrNull { it.candidate.platform == Platform.Twitch }
        assertTrue("Should discover the Twitch handle", twitch != null)
        assertEquals("samplecaster", twitch!!.candidate.username)
        assertEquals("https://www.twitch.tv/samplecaster", twitch.candidate.url)
        assertTrue(twitch.provenance.contains("GitHub"))
        assertTrue(twitch.admissionExplanation.contains("cross-link", ignoreCase = true))
    }

    @Test
    fun extractsMultiplePlatformHandlesFromLinks() {
        val results = extract(
            text = "",
            links = listOf(
                "https://www.reddit.com/user/sampleuser42",
                "https://gitlab.com/jdoe",
                "https://x.com/janedoe"
            )
        )
        val platforms = results.map { it.candidate.platform }
        assertTrue(platforms.contains(Platform.Reddit))
        assertTrue(platforms.contains(Platform.GitLab))
    }

    @Test
    fun extractsPlatformMentionWhenHandleIsNotGeneric() {
        val results = extract(text = "I stream games, also on twitch as samplecaster on weekends.")
        val twitch = results.firstOrNull { it.candidate.platform == Platform.Twitch }
        assertTrue(twitch != null)
        assertEquals("samplecaster", twitch!!.candidate.username)
        assertTrue(twitch.admissionExplanation.contains("mention", ignoreCase = true))
    }

    @Test
    fun rejectsGenericHandleFromRecursiveMention() {
        val results = extract(text = "For help see twitch: @support")
        assertTrue("Generic handles must not recursively expand from one weak mention", results.isEmpty())
    }

    @Test
    fun extractsPlatformColonAtHandlePhrase() {
        val results = extract(
            text = "Find me elsewhere — reddit: @sampleuser42, instagram: @jane.doe"
        )
        val reddit = results.firstOrNull { it.candidate.platform == Platform.Reddit }
        val insta = results.firstOrNull { it.candidate.platform == Platform.Instagram }
        assertTrue(reddit != null)
        assertTrue(insta != null)
    }

    @Test
    fun excludesSourceProfileSelfMention() {
        val results = extract(
            text = "My GitHub is https://github.com/janedoe and twitch https://www.twitch.tv/samplecaster",
            links = listOf("https://github.com/janedoe"),
            sourceUrl = "https://github.com/janedoe"
        )
        val github = results.firstOrNull { it.candidate.platform == Platform.GitHub }
        assertFalse(github != null)
    }

    @Test
    fun excludesAlreadyScannedUrls() {
        val results = extract(
            text = "https://www.twitch.tv/samplecaster",
            links = listOf("https://www.twitch.tv/samplecaster"),
            scanned = setOf("https://www.twitch.tv/samplecaster")
        )
        assertTrue(results.isEmpty())
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
        assertTrue(results.isEmpty())
    }

    @Test
    fun filtersNoreplyGithubEmailHost() {
        val results = extract(
            text = "Contact: jane@users.noreply.github.com",
            links = emptyList()
        )
        val github = results.firstOrNull { it.candidate.platform == Platform.GitHub }
        assertFalse(github != null)
    }

    @Test
    fun dedupesByUrlAcrossLinksAndText() {
        val results = extract(
            text = "stream at https://www.twitch.tv/samplecaster see you there",
            links = listOf("https://www.twitch.tv/samplecaster")
        )
        val twitch = results.filter { it.candidate.platform == Platform.Twitch }
        assertEquals(1, twitch.size)
    }

    @Test
    fun provenanceRecordsSourceAndAdmissionReason() {
        val results = extract(
            text = "https://www.twitch.tv/samplecaster",
            sourceUrl = "https://x.com/janedoe",
            sourceLabel = "X"
        )
        val twitch = results.first()
        assertTrue(twitch.provenance.startsWith("discovered via X profile"))
        assertTrue(twitch.admissionExplanation.isNotBlank())
    }
}
