package io.dossier.app.discovery

import io.dossier.app.data.platform.ProviderCatalogV2
import io.dossier.app.domain.discovery.*
import io.dossier.app.domain.model.Platform
import io.dossier.app.domain.model.ProfileScanResult
import io.dossier.app.domain.model.UsernameCandidate
import io.dossier.app.domain.model.UsernameMatchType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ProviderExecutionRuntimeTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val sampleProvider = ProviderDefinition(
        id = "test_provider",
        displayName = "Test Provider",
        category = ProviderCategory.Social,
        profileUrlTemplate = "https://test.example.com/{username}",
        queryCapabilities = setOf(QueryCapability.Username),
        existenceRules = ExistenceRules(
            requiredStatus = setOf(200),
            notFoundStatus = setOf(404),
            requiredText = listOf("profile"),
            softNotFoundText = listOf("user does not exist", "page not found"),
            authenticationText = listOf("sign in to continue", "login required"),
            challengeText = listOf("solve captcha", "verify you are human")
        ),
        requestPolicy = ProviderRequestPolicy(
            timeoutMs = 1000,
            retryBudget = 2,
            minimumIntervalMs = 0,
            cooldownMs = 0
        )
    )

    @Before
    fun setUp() {
        ScanCoordinatorRuntime.resetCounts()
    }

    @After
    fun tearDown() {
        ScanCoordinatorRuntime.resetForTests()
    }

    private fun createMockClient(
        handler: (Request) -> Response
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain -> handler(chain.request()) }
            .build()
    }

    private fun mockResponse(
        request: Request,
        code: Int,
        bodyText: String,
        finalUrl: String = request.url.toString()
    ): Response {
        return Response.Builder()
            .request(
                if (finalUrl != request.url.toString()) {
                    request.newBuilder().url(finalUrl).build()
                } else request
            )
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else if (code == 404) "Not Found" else "Error")
            .body(bodyText.toResponseBody("text/html; charset=utf-8".toMediaType()))
            .build()
    }

    @Test
    fun presentOutcomeExecutesAndReportsCompleted() = runBlocking {
        val client = createMockClient { request ->
            mockResponse(request, 200, "<html><body><h1>User profile page</h1></body></html>")
        }
        val runtime = ProviderExecutionRuntime(client)
        ScanCoordinatorRuntime.onProviderQueued(sampleProvider.id)

        val result = runtime.execute(sampleProvider, "https://test.example.com/target_user")

        assertEquals(ProviderVerificationState.Present, result.decision.state)
        assertEquals(200, result.statusCode)

        val snapshot = ScanCoordinatorRuntime.snapshot.value
        assertEquals(1, snapshot.scheduledProviderCount)
        assertEquals(1, snapshot.completedProviderCount)
        assertEquals(0, snapshot.unavailableProviderCount)
    }

    @Test
    fun plannedProviderBreadthDoesNotPretendChecksWereQueued() {
        val snapshot = LiveScanSnapshot(directProfileProviders = 78)

        assertEquals(78, snapshot.directProfileProviders)
        assertEquals(0, snapshot.scheduledProviderCount)
        assertEquals(0, snapshot.completedProviderCount)
        assertEquals(0, snapshot.unavailableProviderCount)
    }

    @Test
    fun hardNotFoundOutcomeReportsCompleted() = runBlocking {
        val client = createMockClient { request ->
            mockResponse(request, 404, "<html><body>404 Not Found</body></html>")
        }
        val runtime = ProviderExecutionRuntime(client)
        ScanCoordinatorRuntime.onProviderQueued(sampleProvider.id)

        val result = runtime.execute(sampleProvider, "https://test.example.com/missing_user")

        assertEquals(ProviderVerificationState.NotFound, result.decision.state)
        assertEquals(404, result.statusCode)

        val snapshot = ScanCoordinatorRuntime.snapshot.value
        assertEquals(1, snapshot.completedProviderCount)
        assertEquals(0, snapshot.unavailableProviderCount)
    }

    @Test
    fun softNotFoundOutcomeReportsCompleted() = runBlocking {
        val client = createMockClient { request ->
            mockResponse(request, 200, "<html><body>Profile — user does not exist</body></html>")
        }
        val runtime = ProviderExecutionRuntime(client)
        ScanCoordinatorRuntime.onProviderQueued(sampleProvider.id)

        val result = runtime.execute(sampleProvider, "https://test.example.com/ghost_user")

        assertEquals(ProviderVerificationState.SoftNotFound, result.decision.state)

        val snapshot = ScanCoordinatorRuntime.snapshot.value
        assertEquals(1, snapshot.completedProviderCount)
        assertEquals(0, snapshot.unavailableProviderCount)
    }

    @Test
    fun authenticationRequiredReportsUnavailable() = runBlocking {
        val client = createMockClient { request ->
            mockResponse(request, 200, "<html><body>Please sign in to continue</body></html>")
        }
        val runtime = ProviderExecutionRuntime(client)
        ScanCoordinatorRuntime.onProviderQueued(sampleProvider.id)

        val result = runtime.execute(sampleProvider, "https://test.example.com/locked_user")

        assertEquals(ProviderVerificationState.AuthenticationRequired, result.decision.state)

        val snapshot = ScanCoordinatorRuntime.snapshot.value
        assertEquals(0, snapshot.completedProviderCount)
        assertEquals(1, snapshot.unavailableProviderCount)
    }

    @Test
    fun automationChallengedReportsUnavailable() = runBlocking {
        val client = createMockClient { request ->
            mockResponse(request, 200, "<html><body>Security Check: solve captcha to proceed</body></html>")
        }
        val runtime = ProviderExecutionRuntime(client)
        ScanCoordinatorRuntime.onProviderQueued(sampleProvider.id)

        val result = runtime.execute(sampleProvider, "https://test.example.com/bot_check")

        assertEquals(ProviderVerificationState.AutomationChallenged, result.decision.state)

        val snapshot = ScanCoordinatorRuntime.snapshot.value
        assertEquals(0, snapshot.completedProviderCount)
        assertEquals(1, snapshot.unavailableProviderCount)
    }

    @Test
    fun externalRedirectReportsUnavailable() = runBlocking {
        val client = createMockClient { request ->
            mockResponse(
                request,
                200,
                "<html><body>Redirected profile</body></html>",
                finalUrl = "https://external-auth.thirdparty.com/login"
            )
        }
        val runtime = ProviderExecutionRuntime(client)
        ScanCoordinatorRuntime.onProviderQueued(sampleProvider.id)

        val result = runtime.execute(sampleProvider, "https://test.example.com/redirected_user")

        assertEquals(ProviderVerificationState.RedirectedOutsideProvider, result.decision.state)

        val snapshot = ScanCoordinatorRuntime.snapshot.value
        assertEquals(0, snapshot.completedProviderCount)
        assertEquals(1, snapshot.unavailableProviderCount)
    }

    @Test
    fun unexpectedStatusReportsUnavailable() = runBlocking {
        val client = createMockClient { request ->
            mockResponse(request, 503, "<html><body>Service Unavailable</body></html>")
        }
        val runtime = ProviderExecutionRuntime(client)
        ScanCoordinatorRuntime.onProviderQueued(sampleProvider.id)

        val result = runtime.execute(sampleProvider, "https://test.example.com/down_user")

        assertEquals(ProviderVerificationState.UnexpectedStatus, result.decision.state)
        assertEquals(503, result.statusCode)

        val snapshot = ScanCoordinatorRuntime.snapshot.value
        assertEquals(0, snapshot.completedProviderCount)
        assertEquals(1, snapshot.unavailableProviderCount)
    }

    @Test
    fun boundedBodyTruncatesHugePayloadWithoutOOM() {
        val hugeBody = "X".repeat(2_000_000)
        val responseBody = hugeBody.toResponseBody("text/html".toMediaType())

        val read = ProviderExecutionRuntime.readBoundedBody(responseBody, 100_000)
        assertEquals(100_000, read.length)
        assertTrue(read.all { it == 'X' })
    }

    @Test
    fun timeoutAndRetryBehaviorRetriesTransientErrors() = runBlocking {
        val attempts = AtomicInteger(0)
        val client = createMockClient { request ->
            val count = attempts.incrementAndGet()
            if (count == 1) {
                mockResponse(request, 500, "Internal Server Error")
            } else {
                mockResponse(request, 200, "<html><body><h1>User profile</h1></body></html>")
            }
        }
        val runtime = ProviderExecutionRuntime(client)
        ScanCoordinatorRuntime.onProviderQueued(sampleProvider.id)

        val result = runtime.execute(sampleProvider, "https://test.example.com/flaky_user")

        assertEquals(2, attempts.get())
        assertEquals(ProviderVerificationState.Present, result.decision.state)
        assertEquals(200, result.statusCode)
        val snapshot = ScanCoordinatorRuntime.snapshot.value
        assertEquals(1, snapshot.scheduledProviderCount)
        assertEquals(1, snapshot.startedProviderCount)
        assertEquals(1, snapshot.completedProviderCount)
        assertEquals(0, snapshot.unavailableProviderCount)
    }

    @Test
    fun identicalTimeoutAndRedirectPoliciesReuseOneDerivedClient() = runBlocking {
        val createdClients = AtomicInteger(0)
        val client = createMockClient { request ->
            mockResponse(request, 200, "<html><body>profile</body></html>")
        }
        val runtime = ProviderExecutionRuntime(
            client = client,
            callClientFactory = { _, _ ->
                createdClients.incrementAndGet()
                client.newBuilder().build()
            }
        )
        val provider = sampleProvider.copy(
            requestPolicy = sampleProvider.requestPolicy.copy(retryBudget = 0)
        )

        runtime.execute(provider, "https://test.example.com/reused-first")
        runtime.execute(provider, "https://test.example.com/reused-second")
        assertEquals("same policy should share a derived client", 1, createdClients.get())

        val noRedirectProvider = provider.copy(
            existenceRules = provider.existenceRules?.copy(followRedirects = false)
        )
        runtime.execute(noRedirectProvider, "https://test.example.com/no-redirect")
        assertEquals("redirect policy must remain part of the cache key", 2, createdClients.get())
    }

    @Test
    fun derivedClientCacheEvictsOldPoliciesButRetainsRecentOnes() = runBlocking {
        val createdClients = AtomicInteger(0)
        val client = createMockClient { request ->
            mockResponse(request, 200, "<html><body>profile</body></html>")
        }
        val runtime = ProviderExecutionRuntime(
            client = client,
            callClientFactory = { _, _ ->
                createdClients.incrementAndGet()
                client.newBuilder().build()
            }
        )
        val basePolicy = sampleProvider.requestPolicy.copy(retryBudget = 0)

        suspend fun executeWithTimeout(timeoutMs: Long) {
            runtime.execute(
                provider = sampleProvider.copy(
                    requestPolicy = basePolicy.copy(timeoutMs = timeoutMs)
                ),
                url = "https://test.example.com/cache-$timeoutMs"
            )
        }

        val cacheLimit = ProviderExecutionRuntime.CALL_CLIENT_CACHE_MAX_ENTRIES
        repeat(cacheLimit + 1) { index ->
            executeWithTimeout(500L + index)
        }
        assertEquals(cacheLimit + 1, createdClients.get())

        // The oldest entry was evicted, so revisiting it builds one replacement.
        executeWithTimeout(500L)
        assertEquals(cacheLimit + 2, createdClients.get())

        // The newest entry remains cached after the replacement insertion.
        executeWithTimeout(500L + cacheLimit)
        assertEquals(cacheLimit + 2, createdClients.get())
    }

    @Test
    fun exhaustiveRetriesProduceCleanUnavailableStateWithoutExceptionLeak() = runBlocking {
        val attempts = AtomicInteger(0)
        val outcomes = mutableListOf<ProviderOutcome>()
        val client = createMockClient { _ ->
            attempts.incrementAndGet()
            throw InterruptedIOException("target-specific detail must not escape")
        }
        val runtime = ProviderExecutionRuntime(
            client = client,
            diagnosticsRecorder = { _, outcome, _ -> outcomes += outcome }
        )
        ScanCoordinatorRuntime.onProviderQueued(sampleProvider.id)

        val result = runtime.execute(sampleProvider, "https://test.example.com/timeout_user")

        assertEquals(ProviderVerificationState.Timeout, result.decision.state)
        assertNull(result.statusCode)
        assertEquals("Request timed out", result.decision.explanation)
        assertEquals(listOf(ProviderOutcome.Timeout), outcomes)

        val snapshot = ScanCoordinatorRuntime.snapshot.value
        assertEquals(1, snapshot.startedProviderCount)
        assertEquals(1, snapshot.unavailableProviderCount)
    }

    @Test
    fun okhttpCallTimeoutProducesTypedTimeoutWithoutLiveNetwork() = runBlocking {
        val client = createMockClient { request ->
            Thread.sleep(700)
            mockResponse(request, 200, "<html><body>User profile</body></html>")
        }
        val provider = sampleProvider.copy(
            requestPolicy = sampleProvider.requestPolicy.copy(
                timeoutMs = 500,
                retryBudget = 0
            )
        )
        val runtime = ProviderExecutionRuntime(client)
        ScanCoordinatorRuntime.onProviderQueued(provider.id)

        val result = runtime.execute(provider, "https://test.example.com/slow_user")

        assertEquals(ProviderVerificationState.Timeout, result.decision.state)
        assertEquals(1, result.attemptCount)
        assertEquals(1, ScanCoordinatorRuntime.snapshot.value.unavailableProviderCount)
    }

    @Test
    fun rateLimitIsTypedUnavailableAndStartsCooldown() = runBlocking {
        val client = createMockClient { request ->
            mockResponse(request, 429, "Too many requests")
        }
        val runtime = ProviderExecutionRuntime(client)
        val rateLimitedProvider = sampleProvider.copy(
            requestPolicy = sampleProvider.requestPolicy.copy(
                retryBudget = 0,
                cooldownMs = 10_000
            )
        )
        ScanCoordinatorRuntime.onProviderQueued(rateLimitedProvider.id)

        val result = runtime.execute(rateLimitedProvider, "https://test.example.com/rate_limited")

        assertEquals(ProviderVerificationState.RateLimited, result.decision.state)
        assertTrue(runtime.isCooldownActive(rateLimitedProvider.id))
        val snapshot = ScanCoordinatorRuntime.snapshot.value
        assertEquals(1, snapshot.scheduledProviderCount)
        assertEquals(1, snapshot.startedProviderCount)
        assertEquals(0, snapshot.completedProviderCount)
        assertEquals(1, snapshot.unavailableProviderCount)
    }

    @Test
    fun staleScanEventsCannotMutateActiveProviderCounts() {
        val active = ScanId("active-scan")
        val stale = ScanId("stale-scan")
        ScanCoordinatorRuntime.resetCounts(active)

        ScanCoordinatorRuntime.onProviderQueued("github", stale)
        ScanCoordinatorRuntime.onProviderStarted("github", scanId = stale)
        ScanCoordinatorRuntime.onProviderCompleted(
            "github",
            ProviderVerificationState.Present,
            10L,
            stale
        )

        val snapshot = ScanCoordinatorRuntime.snapshot.value
        assertEquals(active, snapshot.scanId)
        assertEquals(0, snapshot.scheduledProviderCount)
        assertEquals(0, snapshot.startedProviderCount)
        assertEquals(0, snapshot.completedProviderCount)
        assertEquals(0, snapshot.unavailableProviderCount)
    }

    @Test
    fun rendererPolicyRejectsEveryUnavailableProviderState() {
        assertTrue(ProviderRendererPolicy.allows(ProviderVerificationState.Present))
        ProviderVerificationState.entries
            .filterNot { it == ProviderVerificationState.Present }
            .forEach { state -> assertFalse(state.name, ProviderRendererPolicy.allows(state)) }
    }

    @Test
    fun safeEventPayloadsRejectUrlShapedProviderIds() = runBlocking {
        val scanId = ScanCoordinatorRuntime.activeScanId() ?: error("test scan was not initialized")
        val nextEvent = async(start = CoroutineStart.UNDISPATCHED) {
            ScanCoordinatorRuntime.events.first()
        }

        ScanCoordinatorRuntime.onProviderQueued(
            "https://provider.example/profile/target-user",
            scanId
        )

        val event = nextEvent.await() as ScanEvent.ProviderQueued
        assertEquals("unknown", event.providerId)
        assertFalse(event.toString().contains("target-user"))
        assertFalse(event.toString().contains("https://"))
    }

    @Test
    fun exactProviderLookupWorksInCatalog() {
        val github = ProviderCatalogV2.findById("github")
        assertNotNull(github)
        assertEquals("github", github?.id)

        // Case-insensitive lookup
        val upperGithub = ProviderCatalogV2.findById("GITHUB")
        assertNotNull(upperGithub)
        assertEquals("github", upperGithub?.id)

        // Index operator lookup
        val indexed = ProviderCatalogV2["codeberg"]
        assertNotNull(indexed)
        assertEquals("codeberg", indexed?.id)

        // Missing id lookup returns null
        val missing = ProviderCatalogV2.findById("non_existent_provider_xyz")
        assertNull(missing)
    }

    @Test
    fun uncataloguedProfileDefinitionUsesOpaqueHostIdentityAndValidatedPolicy() {
        val definition = ProviderExecutionRuntime.uncataloguedProfileDefinition(
            "https://www.example.test/profile/jane?token=redacted"
        )
        assertNotNull(definition)
        assertTrue(definition!!.id.startsWith("unmapped-"))
        assertEquals(29, definition.id.length)
        assertFalse(definition.id.contains("example"))
        assertEquals(setOf(QueryCapability.Url), definition.queryCapabilities)
        assertEquals(setOf("example.test"), definition.approvedHosts)
        assertEquals(1, definition.requestPolicy.maxConcurrency)
        assertEquals(750L, definition.requestPolicy.minimumIntervalMs)
        assertEquals(5_000L, definition.requestPolicy.timeoutMs)
        assertEquals(1, definition.requestPolicy.retryBudget)
        assertTrue(ProviderDefinitionValidator.validate(definition).isEmpty())
        assertEquals(
            definition.id,
            ProviderExecutionRuntime.uncataloguedProviderId("https://example.test/other")
        )
        assertNull(ProviderExecutionRuntime.uncataloguedProfileDefinition("ftp://example.test/profile"))
        assertNull(ProviderExecutionRuntime.uncataloguedProfileDefinition("not a URL"))
    }

    @Test
    fun uncataloguedRuntimeRetainsBodyBoundAndRedirectLifecycle() = runBlocking {
        val definition = checkNotNull(
            ProviderExecutionRuntime.uncataloguedProfileDefinition("https://example.test/profile/jane")
        )
        val hugeBody = "x".repeat(ProviderExecutionRuntime.MAX_BODY_CHARS + 50_000)
        val client = createMockClient { request ->
            mockResponse(
                request,
                200,
                hugeBody,
                finalUrl = "https://login.example.invalid/auth"
            )
        }
        val runtime = ProviderExecutionRuntime(client)
        ScanCoordinatorRuntime.onProviderQueued(definition.id)

        val result = runtime.execute(definition, "https://example.test/profile/jane")

        assertEquals(ProviderVerificationState.RedirectedOutsideProvider, result.decision.state)
        assertEquals(ProviderExecutionRuntime.MAX_BODY_CHARS, result.bodyText.length)
        assertEquals(1, result.attemptCount)
        val snapshot = ScanCoordinatorRuntime.snapshot.value
        assertEquals(1, snapshot.scheduledProviderCount)
        assertEquals(1, snapshot.startedProviderCount)
        assertEquals(0, snapshot.completedProviderCount)
        assertEquals(1, snapshot.unavailableProviderCount)
    }

    @Test
    fun customSchedulingKeyAndClassifierWorksCorrectlyAndCapsBody() = runBlocking {
        val hugeBody = "X".repeat(300_000)
        val provider = sampleProvider.copy(id = "test-provider")
        val client = createMockClient { request ->
            mockResponse(request, 200, hugeBody)
        }
        val diagnostics = mutableListOf<Pair<String, ProviderOutcome>>()
        val runtime = ProviderExecutionRuntime(
            client = client,
            diagnosticsRecorder = { providerId, outcome, _ ->
                diagnostics += providerId to outcome
            }
        )
        ScanCoordinatorRuntime.onProviderQueued(provider.id)

        val customClassifier = { _: ProviderDefinition, obs: ProviderResponseObservation ->
            assertEquals(192 * 1024, obs.bodyText.length)
            ProviderResponseDecision(ProviderVerificationState.Present, "Custom classifier success")
        }

        val result = runtime.execute(
            provider = provider,
            url = "https://test.example.com/custom_user",
            schedulingKey = " API.Example.COM ",
            classifier = customClassifier,
            maxBodyChars = 192 * 1024
        )

        assertEquals(ProviderVerificationState.Present, result.decision.state)
        assertEquals("Custom classifier success", result.decision.explanation)
        assertEquals(200, result.statusCode)
        assertEquals(192 * 1024, result.bodyText.length)
        assertEquals(listOf("test-provider" to ProviderOutcome.Success), diagnostics)
        val snapshot = ScanCoordinatorRuntime.snapshot.value
        assertEquals(1, snapshot.startedProviderCount)
        assertEquals(1, snapshot.completedProviderCount)
        assertEquals(
            "api.example.com",
            ProviderExecutionRuntime.normalizeSchedulingKey(" API.Example.COM ", "fallback")
        )
        assertEquals(
            "fallback",
            ProviderExecutionRuntime.normalizeSchedulingKey("invalid/key", "fallback")
        )
    }

    @Test
    fun modelSerializationBackwardCompatibility() {
        // Legacy JSON without providerId or providerVerificationState
        val legacyCandidateJson = """
            {
                "username": "janedoe",
                "platform": "GitHub",
                "url": "https://github.com/janedoe",
                "matchType": "Exact",
                "confidence": 0.95
            }
        """.trimIndent()

        val decodedCandidate = json.decodeFromString<UsernameCandidate>(legacyCandidateJson)
        assertEquals("janedoe", decodedCandidate.username)
        assertNull(decodedCandidate.providerId)

        // New JSON with providerId
        val candidateWithProvider = decodedCandidate.copy(providerId = "github")
        val encodedCandidate = json.encodeToString(candidateWithProvider)
        val decodedWithProvider = json.decodeFromString<UsernameCandidate>(encodedCandidate)
        assertEquals("github", decodedWithProvider.providerId)

        // Legacy ProfileScanResult JSON without providerId / providerVerificationState
        val legacyResultJson = """
            {
                "candidate": $legacyCandidateJson,
                "exists": true,
                "httpStatus": 200,
                "displayName": "Jane Doe",
                "bio": "Open source developer",
                "profileImageUrl": null,
                "links": ["https://github.com/janedoe"],
                "extractedText": "Jane Doe developer bio",
                "findings": [],
                "confidenceSignals": ["Direct HTTP 200"],
                "verified": true,
                "verificationStatus": "✓ Verified (HTTP 200, direct page access)"
            }
        """.trimIndent()

        val decodedResult = json.decodeFromString<ProfileScanResult>(legacyResultJson)
        assertTrue(decodedResult.exists)
        assertTrue(decodedResult.verified)
        assertNull(decodedResult.providerId)
        assertNull(decodedResult.providerVerificationState)

        // New ProfileScanResult with providerId and providerVerificationState
        val newResult = decodedResult.copy(
            providerId = "github",
            providerVerificationState = ProviderVerificationState.Present
        )
        val encodedNewResult = json.encodeToString(newResult)
        val decodedNewResult = json.decodeFromString<ProfileScanResult>(encodedNewResult)
        assertEquals("github", decodedNewResult.providerId)
        assertEquals(ProviderVerificationState.Present, decodedNewResult.providerVerificationState)
    }

    @Test
    fun jobCancellationCancelsInFlightOkHttpCallAndDoesNotReportUnavailable() = runBlocking {
        val callStarted = CountDownLatch(1)
        val callCanceledByOkHttp = CountDownLatch(1)
        val client = OkHttpClient.Builder()
            .eventListener(object : EventListener() {
                override fun canceled(call: Call) {
                    callCanceledByOkHttp.countDown()
                }
            })
            .addInterceptor { chain ->
                callStarted.countDown()
                callCanceledByOkHttp.await(5, TimeUnit.SECONDS)
                if (chain.call().isCanceled()) {
                    throw java.io.IOException("Canceled")
                }
                mockResponse(chain.request(), 200, "OK")
            }
            .build()

        val runtime = ProviderExecutionRuntime(client)
        val provider = sampleProvider.copy(
            requestPolicy = sampleProvider.requestPolicy.copy(
                timeoutMs = 10_000,
                retryBudget = 0
            )
        )
        ScanCoordinatorRuntime.onProviderQueued(provider.id)

        val deferred = async(Dispatchers.IO) {
            runtime.execute(provider, "https://test.example.com/target")
        }

        assertTrue("Call never started", callStarted.await(3, TimeUnit.SECONDS))
        deferred.cancel()

        assertTrue("OkHttp Call was not canceled on Job cancellation", callCanceledByOkHttp.await(3, TimeUnit.SECONDS))

        var threwCancellation = false
        try {
            deferred.await()
        } catch (e: CancellationException) {
            threwCancellation = true
        }
        assertTrue("Expected CancellationException from cancelled deferred", threwCancellation)

        val snapshot = ScanCoordinatorRuntime.snapshot.value
        assertEquals(0, snapshot.completedProviderCount)
        assertEquals(0, snapshot.unavailableProviderCount)
    }

    @Test
    fun outerTimeoutCancellationPropagatesInsteadOfReportingUnavailable() = runBlocking {
        val callStarted = CountDownLatch(1)
        val callCanceledByOkHttp = CountDownLatch(1)
        val client = OkHttpClient.Builder()
            .eventListener(object : EventListener() {
                override fun canceled(call: Call) {
                    callCanceledByOkHttp.countDown()
                }
            })
            .addInterceptor { chain ->
                callStarted.countDown()
                callCanceledByOkHttp.await(5, TimeUnit.SECONDS)
                if (chain.call().isCanceled()) {
                    throw java.io.IOException("Canceled")
                }
                mockResponse(chain.request(), 200, "OK")
            }
            .build()

        val runtime = ProviderExecutionRuntime(client)
        val provider = sampleProvider.copy(
            requestPolicy = sampleProvider.requestPolicy.copy(
                timeoutMs = 10_000,
                retryBudget = 0
            )
        )
        ScanCoordinatorRuntime.onProviderQueued(provider.id)

        val deferred = async(Dispatchers.IO) {
            withTimeout(1_000) {
                runtime.execute(provider, "https://test.example.com/outer-timeout")
            }
        }

        assertTrue("Call never started", callStarted.await(3, TimeUnit.SECONDS))
        var threwTimeoutCancellation = false
        try {
            deferred.await()
        } catch (_: TimeoutCancellationException) {
            threwTimeoutCancellation = true
        }
        assertTrue("Expected outer timeout to propagate", threwTimeoutCancellation)
        assertTrue(
            "OkHttp Call was not canceled on outer timeout",
            callCanceledByOkHttp.await(3, TimeUnit.SECONDS)
        )

        val snapshot = ScanCoordinatorRuntime.snapshot.value
        assertEquals(0, snapshot.completedProviderCount)
        assertEquals(0, snapshot.unavailableProviderCount)
    }

    @Test
    fun defaultClientIsHardenedWithPublicPolicies() {
        val client = ProviderExecutionRuntime.defaultClient()
        assertEquals(io.dossier.app.data.web.DiscoveryHttpPolicy.PUBLIC_DNS, client.dns)
        assertTrue(
            "Default client must include PUBLIC_URL_INTERCEPTOR",
            client.networkInterceptors.contains(io.dossier.app.data.web.DiscoveryHttpPolicy.PUBLIC_URL_INTERCEPTOR)
        )
    }
}
