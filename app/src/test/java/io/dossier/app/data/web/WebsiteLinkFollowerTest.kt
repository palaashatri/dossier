package io.dossier.app.data.web

import io.dossier.app.domain.discovery.ProviderExecutionRuntime
import io.dossier.app.domain.discovery.ScanCoordinatorRuntime
import io.dossier.app.domain.discovery.ScanId
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class WebsiteLinkFollowerTest {

    private val scanId = ScanId("website-link-follower-test")

    @Before
    fun setUp() {
        ScanCoordinatorRuntime.resetCounts(scanId)
    }

    @After
    fun tearDown() {
        ScanCoordinatorRuntime.resetForTests()
    }

    @Test
    fun presentRootAndSameOriginDisclosurePageAreParsedAndCounted() = runBlocking {
        val follower = follower { request ->
            when (request.url.encodedPath) {
                "/profile" -> response(
                    request,
                    200,
                    """
                    <html><body><h1>Authorized Jane</h1>
                    <a href="https://site.example/about">About</a>
                    <a href="https://other.example/contact">External</a>
                    </body></html>
                    """.trimIndent()
                )
                "/about" -> response(request, 200, "<html><body>Public biography</body></html>")
                else -> response(request, 404, "page not found")
            }
        }

        val result = follower.follow("https://site.example/profile", scanId)

        assertTrue(result.text.contains("Authorized Jane"))
        assertTrue(result.text.contains("Public biography"))
        assertTrue(result.links.contains("https://other.example/contact"))
        val snapshot = ScanCoordinatorRuntime.snapshot.value
        assertEquals(2, snapshot.scheduledProviderCount)
        assertEquals(2, snapshot.startedProviderCount)
        assertEquals(2, snapshot.completedProviderCount)
        assertEquals(0, snapshot.unavailableProviderCount)
    }

    @Test
    fun authenticationChallengeAndExternalRedirectFailClosed() = runBlocking {
        val cases = listOf(
            Triple(401, "login required", null),
            Triple(200, "verify you are human", null),
            Triple(200, "<html><body>profile</body></html>", "https://evil.example/auth")
        )

        cases.forEach { (code, body, finalUrl) ->
            ScanCoordinatorRuntime.resetCounts(scanId)
            val follower = follower { request ->
                response(request, code, body, finalUrl)
            }

            val result = follower.follow("https://site.example/profile", scanId)

            assertTrue(result.text.isEmpty())
            assertTrue(result.links.isEmpty())
            val snapshot = ScanCoordinatorRuntime.snapshot.value
            assertEquals(1, snapshot.scheduledProviderCount)
            assertEquals(1, snapshot.startedProviderCount)
            assertEquals(0, snapshot.completedProviderCount)
            assertEquals(1, snapshot.unavailableProviderCount)
        }
    }

    @Test
    fun runtimeBodyLimitBoundsFollowerOutput() = runBlocking {
        val hugeBody = "x".repeat(ProviderExecutionRuntime.MAX_BODY_CHARS + 50_000)
        val follower = follower { request -> response(request, 200, hugeBody) }

        val result = follower.follow("https://site.example/profile", scanId)

        assertTrue(result.text.isNotEmpty())
        assertTrue(result.text.length <= ProviderExecutionRuntime.MAX_BODY_CHARS)
        assertEquals(1, ScanCoordinatorRuntime.snapshot.value.completedProviderCount)
    }

    @Test
    fun linkedPageFanoutIsBoundedToFiveSameOriginPages() = runBlocking {
        val requests = AtomicInteger(0)
        val links = (1..8).joinToString("\n") { index ->
            "<a href=\"https://site.example/about/$index\">about $index</a>"
        }
        val follower = follower { request ->
            requests.incrementAndGet()
            if (request.url.encodedPath == "/profile") {
                response(request, 200, "<html><body>$links</body></html>")
            } else {
                response(request, 200, "<html><body>disclosure</body></html>")
            }
        }

        follower.follow("https://site.example/profile", scanId)

        assertEquals(6, requests.get())
        assertEquals(6, ScanCoordinatorRuntime.snapshot.value.completedProviderCount)
        assertEquals(0, ScanCoordinatorRuntime.snapshot.value.unavailableProviderCount)
    }

    private fun follower(handler: (Request) -> Response): WebsiteLinkFollower {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain -> handler(chain.request()) }
            .build()
        return WebsiteLinkFollower(
            context = null,
            providerRuntime = ProviderExecutionRuntime(client)
        )
    }

    private fun response(
        request: Request,
        code: Int,
        body: String,
        finalUrl: String? = null
    ): Response {
        val responseRequest = finalUrl?.let { request.newBuilder().url(it).build() } ?: request
        return Response.Builder()
            .request(responseRequest)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Error")
            .body(body.toResponseBody("text/html; charset=utf-8".toMediaType()))
            .build()
    }
}
