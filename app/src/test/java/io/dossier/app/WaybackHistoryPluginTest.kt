package io.dossier.app

import io.dossier.app.data.web.WaybackHistoryPlugin
import io.dossier.app.domain.evidence.EvidenceCollection
import io.dossier.app.domain.evidence.EvidenceReliability
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.model.IdentityInput
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

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

    @Test
    fun networkFailureEmitsBoundedUndatedUnavailableEvidence() = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor { throw IOException("offline") }
            .build()

        val result = WaybackHistoryPlugin(client).scan(
            IdentityInput(fullName = "Authorized subject", profileUrls = listOf("https://example.com/profile"))
        )

        val unavailable = result.evidence.single()
        assertEquals(EvidenceState.Unavailable, unavailable.state)
        assertEquals(EvidenceReliability.ArchiveSnapshot, unavailable.reliability)
        assertEquals(null, unavailable.observedAtEpochMillis)
        assertEquals(null, unavailable.retrievedAtEpochMillis)
    }

    @Test
    fun malformedCdxResponseEmitsUnavailableEvidenceInsteadOfSilentlyDisappearing() = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("not-json".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val result = WaybackHistoryPlugin(client).scan(
            IdentityInput(fullName = "Authorized subject", profileUrls = listOf("https://example.com/profile"))
        )

        assertEquals(1, result.evidence.size)
        assertTrue(result.evidence.single().snippet.orEmpty().contains("unavailable", ignoreCase = true))
    }

    @Test
    fun successfulButMalformedCdxRowEmitsUnavailableEvidence() = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        """
                        [
                          ["timestamp","original","digest","statuscode","mimetype"],
                          ["20240102030405","https://example.com/profile"]
                        ]
                        """.trimIndent().toResponseBody("application/json".toMediaType())
                    )
                    .build()
            }
            .build()

        val result = WaybackHistoryPlugin(client).scan(
            IdentityInput(fullName = "Authorized subject", profileUrls = listOf("https://example.com/profile"))
        )

        assertEquals(1, result.evidence.size)
        assertEquals(EvidenceState.Unavailable, result.evidence.single().state)
        assertEquals(null, result.evidence.single().observedAtEpochMillis)
        assertTrue(result.evidence.single().signals.any { it.contains("No historical observation") })
    }

    @Test
    fun successfulHeaderOnlyCdxResponseRemainsLegitimateEmptyHistory() = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        "[[\"timestamp\",\"original\",\"digest\",\"statuscode\",\"mimetype\"]]"
                            .toResponseBody("application/json".toMediaType())
                    )
                    .build()
            }
            .build()

        val result = WaybackHistoryPlugin(client).scan(
            IdentityInput(fullName = "Authorized subject", profileUrls = listOf("https://example.com/profile"))
        )

        assertTrue(result.evidence.isEmpty())
    }

    @Test
    fun cdxStatusFilterViolationEmitsUnavailableInsteadOfNoHistory() {
        val result = scanCdxPayload(
            """
            [
              ["timestamp","original","digest","statuscode","mimetype"],
              ["20240102030405","https://example.com/profile","BADSTATUS","404","text/html"]
            ]
            """.trimIndent()
        )

        assertUnavailable(result)
    }

    @Test
    fun cdxMimeFilterViolationEmitsUnavailableInsteadOfNoHistory() {
        val result = scanCdxPayload(
            """
            [
              ["timestamp","original","digest","statuscode","mimetype"],
              ["20240102030405","https://example.com/profile","BADMIME","200","application/json"]
            ]
            """.trimIndent()
        )

        assertUnavailable(result)
    }

    @Test
    fun cdxMismatchedOriginalUrlEmitsUnavailableInsteadOfNoHistory() {
        val result = scanCdxPayload(
            """
            [
              ["timestamp","original","digest","statuscode","mimetype"],
              ["20240102030405","https://example.com/other","BADURL","200","text/html"]
            ]
            """.trimIndent()
        )

        assertUnavailable(result)
    }

    @Test
    fun cdxImpossibleCalendarTimestampEmitsUnavailableInsteadOfNullDate() {
        val result = scanCdxPayload(
            """
            [
              ["timestamp","original","digest","statuscode","mimetype"],
              ["20240230030405","https://example.com/profile","BADDAY","200","text/html"]
            ]
            """.trimIndent()
        )

        assertUnavailable(result)
    }

    private fun scanCdxPayload(payload: String): EvidenceCollection = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(payload.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        WaybackHistoryPlugin(client).scan(
            IdentityInput(fullName = "Authorized subject", profileUrls = listOf("https://example.com/profile"))
        )
    }

    private fun assertUnavailable(result: EvidenceCollection) {
        assertEquals(1, result.evidence.size)
        assertEquals(EvidenceState.Unavailable, result.evidence.single().state)
        assertNull(result.evidence.single().observedAtEpochMillis)
        assertTrue(result.evidence.single().signals.any { it.contains("No historical observation") })
    }
}
