package io.dossier.app.domain.scanner

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import io.dossier.app.data.web.PublicSearchDiscoveryService
import io.dossier.app.data.web.TypedSeedPublicFetchExecutor
import io.dossier.app.data.web.VerifiedPage
import io.dossier.app.domain.discovery.ProviderExecutionResult
import io.dossier.app.domain.discovery.ProviderResponseDecision
import io.dossier.app.domain.discovery.ProviderVerificationState
import io.dossier.app.domain.discovery.ScanId
import io.dossier.app.domain.discovery.TypedSeed
import io.dossier.app.domain.discovery.TypedSeedKind
import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceCollection
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.evidence.ExposureSourceClassification
import io.dossier.app.domain.model.FindingAttribution
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.pii.PiiExtractor
import io.dossier.app.domain.username.UsernameVariantGenerator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class ProfileScannerTypedFrontierRegressionTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("dossier-scanner").toFile()
    }

    @After
    fun tearDown() {
        BackgroundScanManager.resetSeams()
        root.deleteRecursively()
    }

    @Test
    fun `user input name and username are not searched but discovered username is`() = runBlocking {
        val context = FakeContext(root)
        val searchedSeeds = mutableListOf<TypedSeed>()

        val executor = TypedSeedPublicFetchExecutor(
            searchOutcomeSearcher = { seed, scopedInput, _ ->
                searchedSeeds.add(seed)
                if (seed.exactValue == "discovered_user") {
                    io.dossier.app.data.web.PublicSearchDiscoveryService.SearchOutcome.Success(
                        listOf(
                            io.dossier.app.data.web.PublicSearchDiscoveryService.PublicSearchResult(
                                url = "https://example.test/discovered_user",
                                title = "Discovered User",
                                snippet = "Snippet",
                                source = "TestProvider",
                                directlyVerified = true,
                                score = 1.0f,
                                query = "discovered_user",
                                pivotEvidenceIds = listOf("verified-username"),
                                verifiedPage = VerifiedPage(
                                    finalUrl = "https://example.test/discovered_user",
                                    title = "Discovered User",
                                    text = "Jane Example @discovered_user",
                                    contentHashSha256 = "discovered-user-hash"
                                )
                            )
                        )
                    )
                } else {
                    io.dossier.app.data.web.PublicSearchDiscoveryService.SearchOutcome.Success(emptyList())
                }
            }
        )

        val scanner = ProfileScanner(
            context = context,
            piiExtractor = PiiExtractor(),
            variantGenerator = UsernameVariantGenerator(),
            typedSeedExecutorOverride = executor
        )
        val initialEvidence = EvidenceCollection(
            evidence = listOf(
                Evidence(
                    id = "seed:username:input_user",
                    kind = EvidenceKind.Username,
                    value = "input_user",
                    state = EvidenceState.Observed,
                    confidence = 1.0f
                ),
                Evidence(
                    id = "verified-username",
                    kind = EvidenceKind.Username,
                    value = "discovered_user",
                    sourceUrl = "https://example.test/source",
                    state = EvidenceState.Verified,
                    sourceClassification = ExposureSourceClassification.PUBLIC_PROFILE,
                    confidence = 1.0f
                )
            )
        )

        val output = scanner.runTypedSeedFrontier(
            input = io.dossier.app.domain.model.IdentityInput(fullName = "Jane Example", usernames = listOf("input_user")),
            deepResearch = false,
            requestId = null,
            checkpointOwnerId = null,
            checkpointGeneration = null,
            planFingerprint = null,
            seedEvidence = initialEvidence,
            scanId = ScanId("test-name-username")
        )

        assertEquals(1, searchedSeeds.size)
        assertEquals("discovered_user", searchedSeeds[0].exactValue)
        assertEquals(TypedSeedKind.Username, searchedSeeds[0].kind)

        assertTrue(
            output.evidence.any {
                it.kind == EvidenceKind.PublicSearchEvidence &&
                    it.value == "https://example.test/discovered_user" &&
                    it.state == EvidenceState.Observed
            }
        )
        assertTrue(
            output.evidence.any {
                it.kind == EvidenceKind.Url &&
                    it.value == "https://example.test/discovered_user" &&
                    it.state == EvidenceState.Verified
            }
        )
    }

    private class FakeContext(private val root: File) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = root
    }

    @Test
    fun `scanIdentity orchestrates typed frontier correctly`() = runBlocking {
        val context = FakeContext(root)
        val piiExtractor = PiiExtractor()
        val variantGenerator = UsernameVariantGenerator()

        val urlA = "https://profile.example.test/a"
        val email = "jane.orchestration@example.test"
        val input = IdentityInput(
            fullName = "Jane Example",
            profileUrls = listOf(urlA)
        )

        val fetcher = TypedSeedPublicFetchExecutor.PublicSeedFetcher { _, requested, _, _ ->
            val body = when (requested) {
                urlA -> "<html><body>contact $email</body></html>"
                else -> ""
            }
            ProviderExecutionResult(
                decision = ProviderResponseDecision(ProviderVerificationState.Present, ""),
                statusCode = 200,
                requestedUrl = requested,
                finalUrl = requested,
                bodyText = body,
                latencyMs = 100,
                attemptCount = 1,
                contentType = "text/html"
            )
        }

        val archiveResolver = TypedSeedPublicFetchExecutor.ArchiveSeedResolver { null }

        val executor = TypedSeedPublicFetchExecutor(
            fetcher = fetcher,
            archiveResolver = archiveResolver,
            piiExtractor = piiExtractor
        )

        val scanner = ProfileScanner(
            context = context,
            piiExtractor = piiExtractor,
            variantGenerator = variantGenerator,
            typedSeedExecutorOverride = executor
        )

        scanner.scanIdentity(input = input)
        val cumulative = scanner.typedSeedExecutionEvidence()

        assertTrue(cumulative.evidence.any { it.value == email })
    }

    @Test
    fun `already verified initial profile URL is not fetched again by typed frontier`() = runBlocking {
        val context = FakeContext(root)
        val calls = AtomicInteger(0)
        val executor = TypedSeedPublicFetchExecutor(
            fetcher = TypedSeedPublicFetchExecutor.PublicSeedFetcher { _, requested, _, _ ->
                calls.incrementAndGet()
                ProviderExecutionResult(
                    decision = ProviderResponseDecision(ProviderVerificationState.Present, "fixture"),
                    statusCode = 200,
                    requestedUrl = requested,
                    finalUrl = requested,
                    bodyText = "<html><body>fixture</body></html>",
                    latencyMs = 1,
                    attemptCount = 1,
                    contentType = "text/html"
                )
            }
        )
        val scanner = ProfileScanner(
            context = context,
            piiExtractor = PiiExtractor(),
            variantGenerator = UsernameVariantGenerator(),
            typedSeedExecutorOverride = executor
        )
        val url = "https://profile.example.test/jane"
        val evidence = EvidenceCollection(
            evidence = listOf(
                Evidence(
                    id = "verified-profile",
                    kind = EvidenceKind.Profile,
                    value = url,
                    sourceUrl = url,
                    state = EvidenceState.Verified,
                    sourceClassification = ExposureSourceClassification.PUBLIC_PROFILE
                )
            )
        )
        val output = scanner.runTypedSeedFrontier(
            input = IdentityInput(fullName = "Jane Example"),
            deepResearch = false,
            requestId = null,
            checkpointOwnerId = null,
            checkpointGeneration = null,
            planFingerprint = null,
            seedEvidence = evidence,
            scanId = ScanId("typed-frontier-no-refetch")
        )

        assertEquals(0, calls.get())
        assertTrue(output.evidence.any { it.id == "verified-profile" })
    }

    @Test
    fun `final seed commits evidence that admits and executes new pending pivots`() = runBlocking {
        val context = FakeContext(root)
        val piiExtractor = PiiExtractor()
        val variantGenerator = UsernameVariantGenerator()

        val urlA = "https://profile.example.test/seed1"
        val urlB = "https://profile.example.test/seed2"
        val email = "final.target@example.test"

        // The first URL is an authorized launch seed.  The regression is
        // specifically about the second URL emitted by that final completion;
        // a candidate-only record would be correctly rejected before execution.
        val input = IdentityInput(
            fullName = "Jane Example",
            profileUrls = listOf(urlA)
        )
        val seedEvidence = EvidenceCollection()

        val fetcher = TypedSeedPublicFetchExecutor.PublicSeedFetcher { _, requested, _, _ ->
            val body = when (requested) {
                urlA -> "<html><body>Go to <a href=\"$urlB\">seed2</a></body></html>"
                urlB -> "<html><body>contact $email</body></html>"
                else -> ""
            }
            ProviderExecutionResult(
                decision = ProviderResponseDecision(ProviderVerificationState.Present, ""),
                statusCode = 200,
                requestedUrl = requested,
                finalUrl = requested,
                bodyText = body,
                latencyMs = 10,
                attemptCount = 1,
                contentType = "text/html"
            )
        }

        val executor = TypedSeedPublicFetchExecutor(
            fetcher = fetcher,
            archiveResolver = TypedSeedPublicFetchExecutor.ArchiveSeedResolver { null },
            piiExtractor = piiExtractor
        )

        val scanner = ProfileScanner(
            context = context,
            piiExtractor = piiExtractor,
            variantGenerator = variantGenerator,
            typedSeedExecutorOverride = executor
        )

        val cumulative = scanner.runTypedSeedFrontier(
            input = input,
            deepResearch = true,
            requestId = null,
            checkpointOwnerId = null,
            checkpointGeneration = null,
            planFingerprint = null,
            seedEvidence = seedEvidence,
            scanId = ScanId("test-scan")
        )

        assertTrue("Expected final seed to admit and execute new pivots", cumulative.evidence.any { it.value == email })
    }

    @Test
    fun `directly verified search URL reenters bounded fetch frontier`() = runBlocking {
        val context = FakeContext(root)
        val searchEmail = "pivot@example.test"
        val downstreamEmail = "downstream@example.test"
        val searchUrl = "https://social.example.test/jane?ref=search#profile"
        val normalizedSearchUrl = "https://social.example.test/jane?ref=search"
        val fetchCalls = AtomicInteger(0)

        val executor = TypedSeedPublicFetchExecutor(
            searchOutcomeSearcher = { seed, _, _ ->
                assertEquals(TypedSeedKind.Email, seed.kind)
                assertEquals(searchEmail, seed.normalizedValue)
                PublicSearchDiscoveryService.SearchOutcome.Success(
                    listOf(
                        PublicSearchDiscoveryService.PublicSearchResult(
                            title = "Jane Example profile",
                            snippet = "Public profile indexed for $searchEmail",
                            url = searchUrl,
                            query = "\"$searchEmail\"",
                            source = "Fixture Search",
                            score = 0.95f,
                            directlyVerified = true,
                            contentHashSha256 = "verified-email-page-hash",
                            verifiedPage = VerifiedPage(
                                finalUrl = normalizedSearchUrl,
                                title = "Jane Example profile",
                                text = "Contact $downstreamEmail",
                                contentHashSha256 = "verified-email-page-hash"
                            )
                        )
                    )
                )
            },
            fetcher = TypedSeedPublicFetchExecutor.PublicSeedFetcher { _, _, _, _ ->
                fetchCalls.incrementAndGet()
                throw AssertionError("directly verified search page must not be fetched again")
            },
            archiveResolver = TypedSeedPublicFetchExecutor.ArchiveSeedResolver { null },
            piiExtractor = PiiExtractor()
        )
        val scanner = ProfileScanner(
            context = context,
            piiExtractor = PiiExtractor(),
            variantGenerator = UsernameVariantGenerator(),
            typedSeedExecutorOverride = executor
        )

        val output = scanner.runTypedSeedFrontier(
            input = IdentityInput(fullName = "", emails = listOf(searchEmail)),
            deepResearch = true,
            requestId = null,
            checkpointOwnerId = null,
            checkpointGeneration = null,
            planFingerprint = null,
            seedEvidence = EvidenceCollection(),
            scanId = ScanId("search-url-recursion")
        )

        assertEquals(0, fetchCalls.get())

        val searchEvidence = output.evidence.single {
            it.kind == EvidenceKind.PublicSearchEvidence && it.value == searchUrl
        }
        assertEquals(EvidenceState.Observed, searchEvidence.state)
        assertEquals(FindingAttribution.Unconfirmed, searchEvidence.attribution)
        assertEquals("verified-email-page-hash", searchEvidence.contentHashSha256)
        assertTrue(searchEvidence.discoveryPath.isNotEmpty())
        assertEquals(searchUrl, searchEvidence.sourceUrl)

        assertTrue(
            "Fetched search result must feed downstream exact evidence through the canonical collection",
            output.evidence.any {
                it.kind == EvidenceKind.Email && it.value == downstreamEmail &&
                    it.sourceUrl == normalizedSearchUrl
            }
        )
    }

    @Test
    fun `directly verified phone search URL reuses page without a second fetch`() = runBlocking {
        val context = FakeContext(root)
        val searchPhone = "15550100400"
        val downstreamPhone = "15559876543"
        val searchUrl = "https://social.example.test/jane-phone#profile"
        val normalizedSearchUrl = "https://social.example.test/jane-phone"
        val fetchCalls = AtomicInteger(0)

        val executor = TypedSeedPublicFetchExecutor(
            searchOutcomeSearcher = { seed, _, _ ->
                assertEquals(TypedSeedKind.Phone, seed.kind)
                assertEquals("15550100400", seed.normalizedValue)
                PublicSearchDiscoveryService.SearchOutcome.Success(
                    listOf(
                        PublicSearchDiscoveryService.PublicSearchResult(
                            title = "Jane Example phone profile",
                            snippet = "Public profile indexed for $searchPhone",
                            url = searchUrl,
                            query = "\"$searchPhone\"",
                            source = "Fixture Phone Search",
                            score = 0.95f,
                            directlyVerified = true,
                            verifiedPage = VerifiedPage(
                                finalUrl = normalizedSearchUrl,
                                title = "Jane Example phone profile",
                                text = "Phone: $downstreamPhone",
                                contentHashSha256 = "verified-phone-page-hash"
                            )
                        )
                    )
                )
            },
            fetcher = TypedSeedPublicFetchExecutor.PublicSeedFetcher { _, _, _, _ ->
                fetchCalls.incrementAndGet()
                throw AssertionError("directly verified search page must not be fetched again")
            },
            archiveResolver = TypedSeedPublicFetchExecutor.ArchiveSeedResolver { null },
            piiExtractor = PiiExtractor()
        )
        val scanner = ProfileScanner(
            context = context,
            piiExtractor = PiiExtractor(),
            variantGenerator = UsernameVariantGenerator(),
            typedSeedExecutorOverride = executor
        )

        val output = scanner.runTypedSeedFrontier(
            input = IdentityInput(fullName = "", phones = listOf(searchPhone)),
            deepResearch = true,
            requestId = null,
            checkpointOwnerId = null,
            checkpointGeneration = null,
            planFingerprint = null,
            seedEvidence = EvidenceCollection(),
            scanId = ScanId("phone-search-url-reuse")
        )

        assertEquals(0, fetchCalls.get())
        val searchEvidence = output.evidence.single {
            it.kind == EvidenceKind.PublicSearchEvidence && it.value == searchUrl
        }
        assertEquals(EvidenceState.Observed, searchEvidence.state)
        assertEquals(FindingAttribution.Unconfirmed, searchEvidence.attribution)
        assertEquals("verified-phone-page-hash", searchEvidence.contentHashSha256)
        assertEquals(normalizedSearchUrl, output.evidence.first {
            it.kind == EvidenceKind.Phone && it.value == downstreamPhone
        }.sourceUrl)
    }

    @Test
    fun `durable typed frontier cancellation releases in-flight seed for reload`() = runBlocking {
        val context = FakeContext(root)
        val requestId = "11111111-1111-4111-8111-111111111111"
        val ownerId = "22222222-2222-4222-8222-222222222222"
        val generation = "33333333-3333-4333-8333-333333333333"
        val planFingerprint = "a".repeat(64)
        val lifecycleStore = ScanLifecycleStore(
            preferences = InMemoryPreferences(),
            nowEpochMillis = { 100L }
        )
        val typedStore = TypedSeedFrontierStore(
            rootDir = root,
            requestId = requestId,
            crypto = TestCheckpointCrypto(),
            nowMillis = { 100L }
        )
        BackgroundScanManager.lifecycleStoreProvider = { lifecycleStore }
        BackgroundScanManager.typedFrontierStoreProvider = { _, requestedRequestId ->
            assertEquals(requestId, requestedRequestId)
            typedStore
        }
        assertEquals(
            ScanLifecycleWriteResult.Saved,
            lifecycleStore.publish(
                ScanLifecycleRecord(
                    ownerId = ownerId,
                    requestId = requestId,
                    generation = generation,
                    phase = ScanLifecyclePhase.Running,
                    updatedAtEpochMillis = 100L,
                    resultReady = false
                )
            )
        )

        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val url = "https://profile.example.test/durable-cancel"
        val executor = TypedSeedPublicFetchExecutor(
            fetcher = TypedSeedPublicFetchExecutor.PublicSeedFetcher { _, requested, _, _ ->
                started.complete(Unit)
                release.await()
                ProviderExecutionResult(
                    decision = ProviderResponseDecision(ProviderVerificationState.Present, "fixture"),
                    statusCode = 200,
                    requestedUrl = requested,
                    finalUrl = requested,
                    bodyText = "<html><body>fixture</body></html>",
                    latencyMs = 1,
                    attemptCount = 1,
                    contentType = "text/html"
                )
            },
            archiveResolver = TypedSeedPublicFetchExecutor.ArchiveSeedResolver { null }
        )
        val scanner = ProfileScanner(
            context = context,
            piiExtractor = PiiExtractor(),
            variantGenerator = UsernameVariantGenerator(),
            typedSeedExecutorOverride = executor
        )

        val run = async {
            scanner.runTypedSeedFrontier(
                input = IdentityInput(fullName = "", profileUrls = listOf(url)),
                deepResearch = true,
                requestId = requestId,
                checkpointOwnerId = ownerId,
                checkpointGeneration = generation,
                planFingerprint = planFingerprint,
                seedEvidence = EvidenceCollection(),
                scanId = ScanId("durable-cancellation")
            )
        }

        withTimeout(5_000L) { started.await() }
        run.cancelAndJoin()
        release.complete(Unit)
        assertTrue("Cancellation must propagate to the scanner job", run.isCancelled)

        val config = TypedSeedFrontierConfig(
            maxDepth = TypedSeedFrontierConfig.DEFAULT_MAX_DEPTH,
            maxTotalSeeds = TypedSeedFrontierConfig.DEFAULT_MAX_TOTAL_SEEDS
        )
        val reloaded = BackgroundScanManager.loadTypedFrontierIfOwner(
            context = context,
            workerId = ownerId,
            generation = generation,
            requestId = requestId,
            config = config,
            planFingerprint = planFingerprint
        )
        assertTrue("A cancelled durable scan must leave a reloadable frontier", reloaded is TypedSeedFrontierLoadResult.Available)
        val restored = (reloaded as TypedSeedFrontierLoadResult.Available).frontier
        assertEquals(1, restored.pendingCount)
        assertEquals(0, restored.inFlightCount)
        assertEquals(TypedSeedFrontierEntryState.Pending, restored.entries.single().state)
        assertEquals(url, restored.entries.single().seed.exactValue)
    }

    private class InMemoryPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            values[key] as? MutableSet<String> ?: defValues

        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clear = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor = apply {
                pending[key] = value
                removals.remove(key)
            }

            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = apply {
                pending[key] = values
                removals.remove(key)
            }

            override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply {
                pending[key] = value
                removals.remove(key)
            }

            override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply {
                pending[key] = value
                removals.remove(key)
            }

            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply {
                pending[key] = value
                removals.remove(key)
            }

            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply {
                pending[key] = value
                removals.remove(key)
            }

            override fun remove(key: String): SharedPreferences.Editor = apply {
                pending.remove(key)
                removals.add(key)
            }

            override fun clear(): SharedPreferences.Editor = apply { clear = true }

            override fun commit(): Boolean {
                if (clear) values.clear()
                removals.forEach(values::remove)
                values.putAll(pending)
                return true
            }

            override fun apply() {
                commit()
            }
        }
    }

    private class TestCheckpointCrypto : CheckpointCrypto {
        private val key = SecretKeySpec(ByteArray(32) { index -> (index + 17).toByte() }, "AES")
        private val random = SecureRandom()

        override fun encrypt(plaintext: ByteArray, aad: ByteArray): CheckpointCrypto.Encrypted {
            val iv = ByteArray(12).also(random::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            return CheckpointCrypto.Encrypted(
                ivBase64 = Base64.getEncoder().encodeToString(iv),
                ciphertextBase64 = Base64.getEncoder().encodeToString(cipher.doFinal(plaintext))
            )
        }

        override fun decrypt(ivBase64: String, ciphertextBase64: String, aad: ByteArray): ByteArray? = runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(128, Base64.getDecoder().decode(ivBase64))
            )
            cipher.updateAAD(aad)
            cipher.doFinal(Base64.getDecoder().decode(ciphertextBase64))
        }.getOrNull()
    }
}
