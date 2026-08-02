package io.dossier.app

import io.dossier.app.data.web.DiscoveryHttpPolicy
import io.dossier.app.data.web.ProviderCircuitBreaker
import io.dossier.app.data.web.PublicSearchDiscoveryService
import io.dossier.app.data.web.StableProfileApiResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryReliabilityTest {

    @Test
    fun transientStatuses_includeRateLimitsAndServerErrors() {
        assertTrue(DiscoveryHttpPolicy.isTransientHttpStatus(429))
        assertTrue(DiscoveryHttpPolicy.isTransientHttpStatus(503))
        assertTrue(DiscoveryHttpPolicy.isTransientHttpStatus(408))
        assertFalse(DiscoveryHttpPolicy.isTransientHttpStatus(404))
        assertFalse(DiscoveryHttpPolicy.isTransientHttpStatus(401))
    }

    @Test
    fun retryDelay_honorsRetryAfterSeconds() {
        assertEquals(3_000L, DiscoveryHttpPolicy.retryDelayMillis(0, "3"))
        assertTrue(DiscoveryHttpPolicy.retryDelayMillis(2, null) > DiscoveryHttpPolicy.retryDelayMillis(0, null))
    }

    @Test
    fun blockDetector_recognizesChallengePages() {
        assertTrue(DiscoveryHttpPolicy.looksBlocked("<title>Just a moment...</title> Cloudflare"))
        assertTrue(DiscoveryHttpPolicy.looksBlocked("Our systems have detected unusual traffic"))
        assertFalse(DiscoveryHttpPolicy.looksBlocked("<html><body>Jane Doe - GitHub</body></html>"))
    }

    @Test
    fun circuitBreaker_opensAndRecoversAfterCooldown() {
        var now = 1_000L
        val breaker = ProviderCircuitBreaker(
            failureThreshold = 2,
            cooldownMillis = 5_000L,
            nowMillis = { now }
        )

        assertTrue(breaker.canAttempt("Bing"))
        breaker.recordFailure("Bing")
        assertTrue(breaker.canAttempt("Bing"))
        breaker.recordFailure("Bing")
        assertFalse(breaker.canAttempt("Bing"))

        now += 5_001L
        assertTrue(breaker.canAttempt("Bing"))
    }

    @Test
    fun stableEndpoint_routesSupportedProfileUrls() {
        val github = StableProfileApiResolver.endpointFor("https://github.com/janedoe")
        assertNotNull(github)
        assertEquals(StableProfileApiResolver.Kind.GITHUB, github!!.kind)
        assertEquals("janedoe", github.username)
        assertTrue(github.apiUrl.contains("api.github.com/users/janedoe"))

        val reddit = StableProfileApiResolver.endpointFor("https://www.reddit.com/user/jane_doe")
        assertEquals(StableProfileApiResolver.Kind.REDDIT, reddit?.kind)

        val bluesky = StableProfileApiResolver.endpointFor("https://bsky.app/profile/jane.bsky.social")
        assertEquals(StableProfileApiResolver.Kind.BLUESKY, bluesky?.kind)

        val hackerNews = StableProfileApiResolver.endpointFor("https://news.ycombinator.com/user?id=janedoe")
        assertEquals(StableProfileApiResolver.Kind.HACKER_NEWS, hackerNews?.kind)

        val youtube = StableProfileApiResolver.endpointFor("https://www.youtube.com/@janedoe")
        assertEquals(StableProfileApiResolver.Kind.YOUTUBE, youtube?.kind)
    }

    @Test
    fun stableEndpoint_rejectsNonProfileRoots() {
        assertNull(StableProfileApiResolver.endpointFor("https://github.com/search"))
        assertNull(StableProfileApiResolver.endpointFor("https://github.com/login"))
        assertNull(StableProfileApiResolver.endpointFor("https://www.reddit.com/r/privacy"))
    }

    @Test
    fun bingParser_extractsProviderSpecificBlocks() {
        val html = """
            <html><body><ol>
              <li class="b_algo">
                <h2><a href="https://github.com/janedoe?utm_source=bing">Jane Doe · GitHub</a></h2>
                <div class="b_caption"><p>Android privacy and OSINT projects by janedoe.</p></div>
              </li>
            </ol></body></html>
        """.trimIndent()

        val results = PublicSearchDiscoveryService.parseSearchResults(
            source = "Bing",
            query = "\"Jane Doe\" site:github.com",
            html = html
        )

        assertEquals(1, results.size)
        assertEquals("Jane Doe · GitHub", results.single().title)
        assertTrue(results.single().snippet.contains("privacy"))
    }

    @Test
    fun canonicalUrl_removesTrackingButPreservesFunctionalQuery() {
        val canonical = PublicSearchDiscoveryService.canonicalUrlKey(
            "https://news.ycombinator.com/user?id=janedoe&utm_source=search#top"
        )
        assertTrue(canonical.contains("id=janedoe"))
        assertFalse(canonical.contains("utm_source"))
        assertFalse(canonical.contains("#top"))
    }
}
