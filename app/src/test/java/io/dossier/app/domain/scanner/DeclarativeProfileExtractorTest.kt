package io.dossier.app.domain.scanner

import io.dossier.app.domain.discovery.ExtractionRules
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeclarativeProfileExtractorTest {
    @Test
    fun providerSelectorsOverrideGenericProfileFallbacks() {
        val document = Jsoup.parse(
            """
            <html><head>
              <title>Generic title</title>
              <link rel="canonical" href="https://example.test/default">
            </head><body>
              <div data-profile-name="">Wrong generic name</div>
              <span data-name>Declarative Name</span>
              <p data-bio>Declarative biography</p>
              <img data-avatar src="/avatar.png">
              <a data-link href="/about">About</a>
            </body></html>
            """.trimIndent(),
            "https://example.test/profile"
        )
        val rules = ExtractionRules(
            displayNameSelectors = listOf("[data-name]"),
            bioSelectors = listOf("[data-bio]"),
            avatarSelectors = listOf("[data-avatar]"),
            canonicalSelectors = listOf("link[rel=canonical]"),
            linkSelectors = listOf("[data-link]")
        )

        val fields = DeclarativeProfileExtractor.extract(document, rules, fallbackTitle = document.title())

        assertEquals("Declarative Name", fields.displayName)
        assertEquals("Declarative biography", fields.bio)
        assertEquals("https://example.test/avatar.png", fields.profileImageUrl)
        assertEquals(
            listOf("https://example.test/default", "https://example.test/about"),
            fields.links
        )
    }

    @Test
    fun malformedSelectorsFailClosedAndKeepSafeDefaults() {
        val document = Jsoup.parse(
            "<html><head><title>Safe title</title></head><body><p>Fallback bio</p></body></html>",
            "https://example.test/profile"
        )

        val fields = DeclarativeProfileExtractor.extract(
            document,
            ExtractionRules(
                displayNameSelectors = listOf("["),
                bioSelectors = listOf("["),
                avatarSelectors = listOf("["),
                canonicalSelectors = listOf("["),
                linkSelectors = listOf("[")
            ),
            fallbackTitle = document.title()
        )

        assertEquals("Safe title", fields.displayName)
        assertEquals("Fallback bio", fields.bio)
        assertNull(fields.profileImageUrl)
        assertTrue(fields.links.isEmpty())
    }

    @Test
    fun emptyRulesRetainBoundedGenericDefaultsAndStripImageFragments() {
        val document = Jsoup.parse(
            """
            <html><head>
              <title>Generic title</title>
              <meta name="description" content="Generic biography">
              <meta property="og:image" content="http://example.test/avatar.png#crop">
              <link rel="canonical" href="https://example.test/profile">
            </head><body><a href="https://example.test/about">About</a></body></html>
            """.trimIndent(),
            "https://example.test/profile"
        )

        val fields = DeclarativeProfileExtractor.extract(document, ExtractionRules())

        assertEquals("Generic title", fields.displayName)
        assertEquals("Generic biography", fields.bio)
        assertEquals("http://example.test/avatar.png", fields.profileImageUrl)
        assertEquals(
            listOf("https://example.test/profile", "https://example.test/about"),
            fields.links
        )
    }
}
