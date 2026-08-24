package io.dossier.app.evidence

import io.dossier.app.domain.discovery.DiscoveryScanPreferences
import io.dossier.app.domain.discovery.ProviderCategory
import io.dossier.app.domain.discovery.ProviderExecutionRuntime
import io.dossier.app.domain.discovery.ScanCoordinatorRuntime
import io.dossier.app.domain.discovery.ScanMode
import io.dossier.app.domain.discovery.WhatsMyNameCatalog
import io.dossier.app.domain.discovery.WhatsMyNameCatalogState
import io.dossier.app.domain.discovery.WhatsMyNameSite
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.evidence.UsernameSurfaceRuntimeCache
import io.dossier.app.domain.evidence.UsernameSurfaceState
import io.dossier.app.domain.evidence.WhatsMyNameUsernamePlugin
import io.dossier.app.domain.model.IdentityInput
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class WhatsMyNameUsernamePluginTest {

    private fun createMockClient(handler: (okhttp3.Request) -> Response): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain -> handler(chain.request()) }
            .build()
    }

    private fun mockResponse(request: okhttp3.Request, code: Int, body: String): Response {
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("OK")
            .body(body.toResponseBody("text/html".toMediaType()))
            .build()
    }

    @Before
    fun setup() {
        ScanCoordinatorRuntime.resetCounts()
        UsernameSurfaceRuntimeCache.clear()
        DiscoveryScanPreferences.setMode(ScanMode.Standard)
    }

    @After
    fun teardown() {
        WhatsMyNameCatalog.setTestState(WhatsMyNameCatalogState.Unavailable("Not installed"))
        ScanCoordinatorRuntime.resetForTests()
        DiscoveryScanPreferences.setMode(ScanMode.Standard)
        UsernameSurfaceRuntimeCache.clear()
    }

    @Test
    fun unavailableCatalogYieldsZeroCallsAndCachedState() = runBlocking {
        WhatsMyNameCatalog.setTestState(WhatsMyNameCatalogState.Unavailable("Test unavailable"))

        val calls = AtomicInteger(0)
        val client = createMockClient { req -> calls.incrementAndGet(); mockResponse(req, 200, "found") }
        val plugin = WhatsMyNameUsernamePlugin(ProviderExecutionRuntime(client))

        val evidence = plugin.scan(IdentityInput(fullName = "Test", primaryUsername = "testuser"))
        assertTrue(evidence.evidence.isEmpty())
        assertEquals(0, calls.get())

        val cached = UsernameSurfaceRuntimeCache.observations.value
        assertEquals(1, cached.size)
        assertEquals(UsernameSurfaceState.Unavailable, cached[0].state)
        assertEquals("Test unavailable", cached[0].reason)
    }

    @Test
    fun explicitHandlesCheckedBoundedLimits() = runBlocking {
        val sites = (1..60).map { i ->
            WhatsMyNameSite("site-$i", "Site $i", ProviderCategory.Social, "https://site$i.com/{account}", "https://site$i.com/{account}", 200, "found", 404, "missing", "")
        }
        WhatsMyNameCatalog.setTestState(WhatsMyNameCatalogState.Ready(sites, emptyList(), emptyList(), emptyList(), emptyList(), 60, 60, 0))
        DiscoveryScanPreferences.setMode(ScanMode.Quick) // Quick mode limits to 50 sites

        val calls = AtomicInteger(0)
        val requestedUrls = ConcurrentLinkedQueue<String>()
        val client = createMockClient { request ->
            calls.incrementAndGet()
            requestedUrls += request.url.toString()
            mockResponse(request, 404, "missing")
        }
        val plugin = WhatsMyNameUsernamePlugin(ProviderExecutionRuntime(client))

        // Input has fullName and emails which shouldn't be checked, only usernames
        val input = IdentityInput(fullName = "John Doe", emails = listOf("john@doe.com"), primaryUsername = "user1", usernames = listOf("user2"))

        val evidence = plugin.scan(input)
        assertTrue(evidence.evidence.isEmpty())
        // 2 handles * 50 sites = 100 calls
        assertEquals(100, calls.get())
        assertTrue(requestedUrls.all { it.endsWith("/user1") || it.endsWith("/user2") })
        assertTrue(requestedUrls.none { "john" in it.lowercase() || "doe" in it.lowercase() })

        val snapshot = ScanCoordinatorRuntime.snapshot.value
        assertEquals(100, snapshot.scheduledProviderCount)
        assertEquals(100, snapshot.completedProviderCount)
    }

    @Test
    fun presentEvidenceCreatesObservedEvidenceWithNonOwnershipSignal() = runBlocking {
        val site = WhatsMyNameSite("wmn-test-1", "Test", ProviderCategory.Social, "https://test.com/{account}", "https://test.com/{account}", 200, "found", 404, "missing", ".")
        WhatsMyNameCatalog.setTestState(WhatsMyNameCatalogState.Ready(listOf(site), emptyList(), emptyList(), emptyList(), emptyList(), 1, 1, 0))

        val client = createMockClient { req -> mockResponse(req, 200, "found") }
        val plugin = WhatsMyNameUsernamePlugin(ProviderExecutionRuntime(client))

        val evidence = plugin.scan(IdentityInput(fullName = "Test", primaryUsername = "user.1"))

        assertEquals(1, evidence.evidence.size)
        val ev = evidence.evidence.first()
        assertEquals("https://test.com/user1", ev.value)
        assertEquals("wmn-test-1", ev.providerId)
        assertEquals(EvidenceState.Observed, ev.state)
        assertTrue(ev.signals.any { it.contains("does not establish ownership") })

        assertEquals(1, evidence.relationships.size)
        val rel = evidence.relationships.first()
        assertEquals("PUBLIC_PROFILE_EXISTS", rel.relation)

        val cached = UsernameSurfaceRuntimeCache.observations.value
        assertEquals(1, cached.size)
        assertEquals(UsernameSurfaceState.Present, cached[0].state)
        assertEquals("wmn-test-1", cached[0].providerId)

        WhatsMyNameCatalog.setTestState(WhatsMyNameCatalogState.Unavailable("Catalog changed"))
        plugin.scan(IdentityInput(fullName = "Test", primaryUsername = "user2"))
        val replacement = UsernameSurfaceRuntimeCache.observations.value
        assertEquals(1, replacement.size)
        assertEquals("user2", replacement.single().username)
        assertEquals(UsernameSurfaceState.Unavailable, replacement.single().state)
    }

    @Test
    fun absentAndUnavailableStayOutOfEvidenceButRemainInCache() = runBlocking {
        val sites = listOf(
            WhatsMyNameSite("site-absent", "Site 1", ProviderCategory.Social, "https://site1.com/{account}", "https://site1.com/{account}", 200, "found", 404, "missing", ""),
            WhatsMyNameSite("site-unavailable", "Site 2", ProviderCategory.Social, "https://site2.com/{account}", "https://site2.com/{account}", 200, "found", 404, "missing", "")
        )
        WhatsMyNameCatalog.setTestState(WhatsMyNameCatalogState.Ready(sites, emptyList(), emptyList(), emptyList(), emptyList(), 2, 2, 0))

        val client = createMockClient { req ->
            if (req.url.toString().contains("site1")) {
                mockResponse(req, 404, "missing")
            } else {
                mockResponse(req, 500, "error")
            }
        }
        val plugin = WhatsMyNameUsernamePlugin(ProviderExecutionRuntime(client))

        val evidence = plugin.scan(IdentityInput(fullName = "Test", primaryUsername = "user1"))

        assertTrue(evidence.evidence.isEmpty())
        assertTrue(evidence.relationships.isEmpty())

        val cached = UsernameSurfaceRuntimeCache.observations.value
        assertEquals(2, cached.size)

        val absent = cached.find { it.site == "Site 1" }!!
        assertEquals(UsernameSurfaceState.Absent, absent.state)

        val unavailable = cached.find { it.site == "Site 2" }!!
        assertEquals(UsernameSurfaceState.Unavailable, unavailable.state)
        assertEquals("site-unavailable", unavailable.providerId)

        val snapshot = ScanCoordinatorRuntime.snapshot.value
        assertEquals(2, snapshot.scheduledProviderCount)
        assertEquals(1, snapshot.completedProviderCount) // 404 is completed
        assertEquals(1, snapshot.unavailableProviderCount) // 500 is unavailable
    }

    @Test
    fun executionNeverExceedsSixInFlightChecks() = runBlocking {
        val sites = (1..12).map { index ->
            WhatsMyNameSite(
                id = "site-$index",
                name = "Site $index",
                category = ProviderCategory.Social,
                uriPretty = "https://site$index.example/{account}",
                uriCheck = "https://site$index.example/{account}",
                eCode = 200,
                eString = "found",
                mCode = 404,
                mString = "missing",
                stripBadChar = ""
            )
        }
        WhatsMyNameCatalog.setTestState(
            WhatsMyNameCatalogState.Ready(
                sites,
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                12,
                12,
                0
            )
        )

        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        val client = createMockClient { request ->
            val active = inFlight.incrementAndGet()
            maxInFlight.updateAndGet { previous -> maxOf(previous, active) }
            try {
                Thread.sleep(25)
                mockResponse(request, 200, "found")
            } finally {
                inFlight.decrementAndGet()
            }
        }

        val plugin = WhatsMyNameUsernamePlugin(ProviderExecutionRuntime(client))
        plugin.scan(IdentityInput(fullName = "Test", primaryUsername = "bounded"))

        assertTrue("Expected at least two concurrent checks", maxInFlight.get() >= 2)
        assertTrue("Observed ${maxInFlight.get()} in-flight checks", maxInFlight.get() <= 6)
        val snapshot = ScanCoordinatorRuntime.snapshot.value
        assertEquals(12, snapshot.scheduledProviderCount)
        assertEquals(12, snapshot.completedProviderCount)
        assertEquals(0, snapshot.unavailableProviderCount)
    }
}
