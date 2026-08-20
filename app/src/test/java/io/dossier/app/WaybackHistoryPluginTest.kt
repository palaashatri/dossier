package io.dossier.app

import io.dossier.app.data.web.WaybackHistoryPlugin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WaybackHistoryPluginTest {

    @Test
    fun cdxParserRequiresExactUrlSuccessfulHtmlAndValidTimestamp() {
        val payload = """
            [
              ["timestamp","original","digest","statuscode","mimetype"],
              ["20240102030405","https://example.com/profile","ABC","200","text/html"],
              ["20240102030406","https://example.com/other","DEF","200","text/html"],
              ["20240102030407","https://example.com/profile","GHI","404","text/html"],
              ["bad","https://example.com/profile","JKL","200","text/html"]
            ]
        """.trimIndent()

        val captures = WaybackHistoryPlugin.parseCdx(payload, "https://example.com/profile")
        assertEquals(1, captures.size)
        assertEquals("20240102030405", captures.single().timestamp)
        assertEquals("ABC", captures.single().digest)
    }

    @Test
    fun cdxParserAcceptsWwwEquivalentAndSortsNewestFirst() {
        val payload = """
            [
              ["timestamp","original","digest","statuscode","mimetype"],
              ["20200102030405","https://www.example.com/profile/","OLD","200","text/html"],
              ["20250102030405","https://example.com/profile","NEW","200","text/html"]
            ]
        """.trimIndent()

        val captures = WaybackHistoryPlugin.parseCdx(payload, "https://example.com/profile")
        assertEquals(listOf("NEW", "OLD"), captures.map { it.digest })
    }

    @Test
    fun snapshotUrlUsesIdReplayAndTimestampConvertsToEpoch() {
        val capture = WaybackHistoryPlugin.Capture(
            timestamp = "20240102030405",
            originalUrl = "https://example.com/profile",
            digest = "ABC",
            statusCode = "200",
            mimeType = "text/html"
        )
        assertEquals(
            "https://web.archive.org/web/20240102030405id_/https://example.com/profile",
            WaybackHistoryPlugin.snapshotUrl(capture)
        )
        assertEquals(1704164645000L, WaybackHistoryPlugin.timestampMillis(capture.timestamp))
        assertNull(WaybackHistoryPlugin.timestampMillis("not-a-timestamp"))
        assertTrue(WaybackHistoryPlugin.snapshotUrl(capture).startsWith("https://web.archive.org/web/"))
    }
}
