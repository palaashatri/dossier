package io.dossier.app.domain.scanner

import android.content.Context
import android.content.ContextWrapper
import io.dossier.app.data.web.TypedSeedPublicFetchExecutor
import io.dossier.app.domain.discovery.ProviderExecutionResult
import io.dossier.app.domain.discovery.ProviderResponseDecision
import io.dossier.app.domain.discovery.ProviderVerificationState
import io.dossier.app.domain.discovery.ScanId
import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceCollection
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.evidence.ExposureSourceClassification
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.pii.PiiExtractor
import io.dossier.app.domain.username.UsernameVariantGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class ProfileScannerTypedFrontierRegressionTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("dossier-scanner").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
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
}
