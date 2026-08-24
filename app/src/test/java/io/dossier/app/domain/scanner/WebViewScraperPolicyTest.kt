package io.dossier.app.domain.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewScraperPolicyTest {

    @Test
    fun userAgent_isNonImpersonatingAndDeclaresGenericDossierIdentity() {
        val userAgent = WebViewScraperPolicy.USER_AGENT

        assertTrue(userAgent.startsWith("Dossier/"))
        assertTrue(userAgent.contains("(+https://github.com/palaashatri/dossier)"))
        assertTrue(userAgent.contains("public-self-audit") || userAgent.contains("authorized"))

        // Must NOT impersonate browser or hardware devices
        assertFalse(userAgent.contains("Mozilla/5.0"))
        assertFalse(userAgent.contains("Chrome/"))
        assertFalse(userAgent.contains("Safari"))
        assertFalse(userAgent.contains("SM-S931B"))
        assertFalse(userAgent.contains("Android 14"))

        // Also accessible via WebViewScraper.USER_AGENT companion
        assertEquals(userAgent, WebViewScraper.USER_AGENT)
    }

    @Test
    fun isAllowedUrl_acceptsValidPublicHttpAndHttpsUrls() {
        assertTrue(WebViewScraperPolicy.isAllowedUrl("https://github.com/janedoe"))
        assertTrue(WebViewScraperPolicy.isAllowedUrl("http://example.com/profile"))
        assertTrue(WebViewScraperPolicy.isAllowedUrl("https://bsky.app/profile/jane.bsky.social"))
        assertTrue(WebViewScraperPolicy.isAllowedUrl("https://example.com:8443/path?query=val#fragment"))
        assertTrue(WebViewScraperPolicy.isAllowedUrl("  https://gitlab.com/janedoe  "))
    }

    @Test
    fun isAllowedUrl_failsClosedForNonHttpSchemesAndDangerousUris() {
        assertFalse(WebViewScraperPolicy.isAllowedUrl("javascript:alert(1)"))
        assertFalse(WebViewScraperPolicy.isAllowedUrl("file:///data/data/io.dossier.app/databases/case.db"))
        assertFalse(WebViewScraperPolicy.isAllowedUrl("content://media/external/images/media"))
        assertFalse(WebViewScraperPolicy.isAllowedUrl("data:text/html,<h1>Test</h1>"))
        assertFalse(WebViewScraperPolicy.isAllowedUrl("about:blank"))
        assertFalse(WebViewScraperPolicy.isAllowedUrl("blob:https://example.com/3f8b-4a21"))
        assertFalse(WebViewScraperPolicy.isAllowedUrl("intent://example.com#Intent;scheme=https;end"))
        assertFalse(WebViewScraperPolicy.isAllowedUrl("market://details?id=io.dossier.app"))
    }

    @Test
    fun isAllowedUrl_failsClosedForMalformedOrBlankUrls() {
        assertFalse(WebViewScraperPolicy.isAllowedUrl(""))
        assertFalse(WebViewScraperPolicy.isAllowedUrl("   "))
        assertFalse(WebViewScraperPolicy.isAllowedUrl("https://"))
        assertFalse(WebViewScraperPolicy.isAllowedUrl("http://"))
        assertFalse(WebViewScraperPolicy.isAllowedUrl("http:///"))
        assertFalse(WebViewScraperPolicy.isAllowedUrl("http://:8080"))
        assertFalse(WebViewScraperPolicy.isAllowedUrl("not_a_valid_url"))
    }

    @Test
    fun isAllowedNavigation_keepsTopLevelRenderOnTheOriginalPublicHost() {
        assertTrue(
            WebViewScraperPolicy.isAllowedNavigation(
                "https://example.com/profile",
                "https://example.com/consent"
            )
        )
        assertFalse(
            WebViewScraperPolicy.isAllowedNavigation(
                "https://example.com/profile",
                "https://login.example.net/start"
            )
        )
        assertFalse(
            WebViewScraperPolicy.isAllowedNavigation(
                "https://example.com/profile",
                "http://example.com/profile"
            )
        )
        assertFalse(
            WebViewScraperPolicy.isAllowedNavigation(
                "https://example.com/profile",
                "javascript:alert(1)"
            )
        )
    }

    @Test
    fun isChallenge_detectsBotWallsAndProtectionScreens() {
        assertTrue(
            WebViewScraperPolicy.isChallenge(
                html = "<html><head><title>Just a moment...</title></head><body>Cloudflare checking your browser</body></html>",
                text = "Checking your browser before accessing the site."
            )
        )
        assertTrue(
            WebViewScraperPolicy.isChallenge(
                html = "<div>Verify you are human</div>",
                text = "Verify you are human to continue"
            )
        )
        assertTrue(
            WebViewScraperPolicy.isChallenge(
                html = "<form action='/recaptcha'>Are you a robot?</form>",
                text = "Please solve the recaptcha"
            )
        )
        assertTrue(
            WebViewScraperPolicy.isChallenge(
                html = "<html><body>Please enable javascript to continue</body></html>",
                text = "Enable JavaScript"
            )
        )
        assertTrue(
            WebViewScraperPolicy.isChallenge(
                html = "<div class='authwall'>Log in to continue</div>",
                text = "Log in to continue to LinkedIn"
            )
        )
    }

    @Test
    fun isChallenge_returnsFalseForNormalProfileDom() {
        assertFalse(
            WebViewScraperPolicy.isChallenge(
                html = "<html><body><h1>Jane Doe</h1><p>Software Engineer and OSS contributor</p></body></html>",
                text = "Jane Doe\nSoftware Engineer and OSS contributor"
            )
        )
    }

    @Test
    fun classifyRendered_producesAppropriateResults() {
        val challenge = WebViewScraperPolicy.classifyRendered(
            html = "<html><title>Just a moment</title><body>cf-challenge</body></html>",
            text = "Checking your browser"
        )
        assertTrue(challenge is WebViewScraper.Result.ChallengeDetected)

        val empty = WebViewScraperPolicy.classifyRendered("", "   ")
        assertTrue(empty is WebViewScraper.Result.Failed)

        val normal = WebViewScraperPolicy.classifyRendered(
            html = "<html><body><h1>Jane</h1></body></html>",
            text = "Jane"
        )
        assertTrue(normal is WebViewScraper.Result.Rendered)
        val rendered = normal as WebViewScraper.Result.Rendered
        assertEquals("<html><body><h1>Jane</h1></body></html>", rendered.html)
        assertEquals("Jane", rendered.text)
    }

    @Test
    fun unescapeJsonString_handlesJsonEscapesProperly() {
        assertEquals("", WebViewScraperPolicy.unescapeJsonString("null"))
        assertEquals("", WebViewScraperPolicy.unescapeJsonString(""))
        assertEquals(
            "<html>&<test></html>",
            WebViewScraperPolicy.unescapeJsonString("\"\\u003Chtml\\u003E\\u0026<test>\\u003C/html\\u003E\"")
        )
        assertEquals(
            "Line 1\nLine 2\tTabbed \"quoted\"",
            WebViewScraperPolicy.unescapeJsonString("\"Line 1\\nLine 2\\tTabbed \\\"quoted\\\"\"")
        )
    }
}
