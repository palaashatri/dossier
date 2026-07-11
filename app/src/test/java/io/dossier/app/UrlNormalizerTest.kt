package io.dossier.app

import io.dossier.app.data.face.ProfileImageDownloader
import io.dossier.app.domain.util.UrlNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlNormalizerTest {
    @Test
    fun ensureHttps_prefixesAndPreserves() {
        assertEquals("https://example.com/a", UrlNormalizer.ensureHttps("example.com/a"))
        assertEquals("https://example.com/a", UrlNormalizer.ensureHttps("//example.com/a"))
        assertEquals("http://example.com/a", UrlNormalizer.ensureHttps("http://example.com/a"))
        assertEquals("https://example.com/a", UrlNormalizer.ensureHttps("https://example.com/a"))
    }

    @Test
    fun isHttpUrl_detectsSchemes() {
        assertTrue(UrlNormalizer.isHttpUrl("https://x"))
        assertTrue(UrlNormalizer.isHttpUrl("http://x"))
        assertTrue(UrlNormalizer.isHttpUrl("//x"))
        assertFalse(UrlNormalizer.isHttpUrl("ftp://x"))
        assertFalse(UrlNormalizer.isHttpUrl("not a url"))
    }

    @Test
    fun profileImageDownloader_normalizeHttpUrl() {
        assertEquals(
            "https://cdn.example.com/a.jpg",
            ProfileImageDownloader.normalizeHttpUrl("//cdn.example.com/a.jpg#frag")
        )
        assertNull(ProfileImageDownloader.normalizeHttpUrl("not-a-url"))
        assertNull(ProfileImageDownloader.normalizeHttpUrl(""))
    }
}
