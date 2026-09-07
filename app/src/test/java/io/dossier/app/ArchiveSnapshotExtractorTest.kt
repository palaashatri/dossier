package io.dossier.app

import io.dossier.app.data.web.ArchiveSnapshotExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveSnapshotExtractorTest {

    @Test
    fun extractsExplicitHistoricalAttributesWithProvenanceSafeUrls() {
        val html = """
            <html>
              <head>
                <title>Alice Example · Profile</title>
                <meta property="og:title" content="Alice Example">
                <meta property="og:description" content="A public historical bio">
                <meta name="profile:username" content="@alice-example">
                <meta property="og:image" content="/avatars/alice.png#crop">
                <meta property="profile:organization" content="Example Org">
                <meta name="geo.placename" content="Example City">
              </head>
              <body>
                <a rel="me" href="https://alice.example/about">About</a>
                <a rel="external" href="https://web.archive.org/web/20240101/https://alice.example/old">Archive</a>
                <a rel="me" href="mailto:alice@example.test">Mail</a>
              </body>
            </html>
        """.trimIndent()

        val extracted = ArchiveSnapshotExtractor.extract(
            html = html,
            snapshotUrl = "https://web.archive.org/web/20240102030405id_/https://alice.example/profile",
            originalUrl = "https://alice.example/profile"
        )

        assertEquals("Alice Example", extracted.displayName)
        assertEquals("A public historical bio", extracted.bio)
        assertEquals("alice-example", extracted.username)
        assertEquals("https://alice.example/avatars/alice.png", extracted.avatarUrl)
        assertEquals("Example Org", extracted.organization)
        assertEquals("Example City", extracted.location)
        assertEquals(listOf("https://alice.example/about"), extracted.externalLinks)
    }

    @Test
    fun usernameIsNeverInferredFromOriginalUrlAndGenericTitlesAreIgnored() {
        val extracted = ArchiveSnapshotExtractor.extract(
            html = "<html><head><title>Profile</title></head><body>alice-example</body></html>",
            snapshotUrl = "https://web.archive.org/web/20240101id_/https://example.test/alice-example",
            originalUrl = "https://example.test/alice-example"
        )

        assertNull(extracted.username)
        assertNull(extracted.displayName)
        assertTrue(extracted.isEmpty)
    }

    @Test
    fun invalidLinksAreDroppedAndValuesRemainBounded() {
        val hugeBio = "b".repeat(ArchiveSnapshotExtractor.MAX_BIO_CHARS + 80)
        val html = """
            <html><head>
              <meta name="description" content="$hugeBio">
              <meta property="og:image" content="javascript:alert(1)">
            </head><body>
              <a rel="me" href="https://user:secret@example.test/private">bad</a>
              <a rel="me" href="data:text/plain,secret">bad</a>
              <a rel="me" href="https://valid.example/public">valid</a>
            </body></html>
        """.trimIndent()

        val extracted = ArchiveSnapshotExtractor.extract(
            html = html,
            snapshotUrl = "https://web.archive.org/web/20240101id_/https://example.test/profile",
            originalUrl = "https://example.test/profile"
        )

        assertEquals(ArchiveSnapshotExtractor.MAX_BIO_CHARS, extracted.bio?.length)
        assertNull(extracted.avatarUrl)
        assertEquals(listOf("https://valid.example/public"), extracted.externalLinks)
        assertFalse(extracted.externalLinks.any { it.contains("secret", ignoreCase = true) })
    }
}
