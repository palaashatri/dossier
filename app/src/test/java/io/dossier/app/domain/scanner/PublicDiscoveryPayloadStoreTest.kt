package io.dossier.app.domain.scanner

import io.dossier.app.domain.discovery.ProviderVerificationState
import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.FindingType
import io.dossier.app.domain.model.Platform
import io.dossier.app.domain.model.ProfileScanResult
import io.dossier.app.domain.model.RiskLevel
import io.dossier.app.domain.model.UsernameCandidate
import io.dossier.app.domain.model.UsernameMatchType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class PublicDiscoveryPayloadStoreTest {
    private lateinit var root: File
    private lateinit var crypto: TestPayloadCrypto
    private var now = 10_000L

    @Before
    fun setUp() {
        root = Files.createTempDirectory("dossier-public-payload").toFile()
        crypto = TestPayloadCrypto()
        now = 10_000L
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `round trip retains exact bounded result and keeps ciphertext opaque`() {
        val store = store(PLAN)
        val result = result()

        val summary = store.save(ScanPayloadStage.PublicSearch, listOf(result))

        assertEquals(1, summary?.itemCount)
        assertEquals(listOf(result), store.load(ScanPayloadStage.PublicSearch))
        assertEquals(summary, store.loadSummary(ScanPayloadStage.PublicSearch))
        assertFalse(store.payloadFileForTesting(ScanPayloadStage.PublicSearch).readText().contains(result.candidate.url))
        assertTrue(store.payloadFileForTesting(ScanPayloadStage.PublicSearch).readText().isNotBlank())
    }

    @Test
    fun `plan mismatch and ciphertext tampering fail closed`() {
        val store = store(PLAN)
        assertTrue(store.save(ScanPayloadStage.PublicImage, listOf(result())) != null)

        val mismatch = PublicDiscoveryPayloadStore(root, REQUEST_ID, OTHER_PLAN, crypto, { now })
        assertNull(mismatch.load(ScanPayloadStage.PublicImage))

        val file = store.payloadFileForTesting(ScanPayloadStage.PublicImage)
        val original = file.readText()
        val marker = "\"ciphertextBase64\":\""
        val start = original.indexOf(marker) + marker.length
        check(start >= marker.length)
        val replacement = if (original[start] == 'A') 'B' else 'A'
        file.writeText(original.substring(0, start) + replacement + original.substring(start + 1))
        assertNull(store.load(ScanPayloadStage.PublicImage))
    }

    @Test
    fun `expiry and unsafe oversized payloads are not cached`() {
        val store = store(PLAN)
        assertTrue(store.save(ScanPayloadStage.PublicSearch, listOf(result())) != null)
        now += 24L * 60L * 60L * 1000L + 1L
        assertNull(store.load(ScanPayloadStage.PublicSearch))

        now = 10_000L
        assertNull(
            store.save(
                ScanPayloadStage.PublicSearch,
                listOf(result(extractedText = "x".repeat(4_097)))
            )
        )
        assertNull(store.save(ScanPayloadStage.PublicSearch, List(ScanPayloadSummary.MAX_ITEMS + 1) { result() }))
    }

    @Test
    fun `verified account result cannot enter public discovery cache`() {
        val store = store(PLAN)
        assertNull(
            store.save(
                ScanPayloadStage.PublicSearch,
                listOf(result().copy(verified = true, verificationStatus = "verified account"))
            )
        )
    }

    @Test
    fun `clear tombstone blocks late writes`() {
        val store = store(PLAN)
        assertTrue(store.save(ScanPayloadStage.PublicSearch, listOf(result())) != null)
        assertTrue(store.clear())
        assertFalse(store.payloadFileForTesting(ScanPayloadStage.PublicSearch).exists())
        assertNull(store.load(ScanPayloadStage.PublicSearch))
        assertNull(store.save(ScanPayloadStage.PublicSearch, listOf(result())))
    }

    @Test
    fun `request clear does not delete another request payload`() {
        val first = storeFor(REQUEST_ID, PLAN)
        val second = storeFor(OTHER_REQUEST_ID, OTHER_PLAN)
        assertTrue(first.save(ScanPayloadStage.PublicSearch, listOf(result())) != null)
        assertTrue(second.save(ScanPayloadStage.PublicImage, listOf(result())) != null)

        assertTrue(first.clear())
        assertNull(first.load(ScanPayloadStage.PublicSearch))
        assertEquals(1, second.load(ScanPayloadStage.PublicImage)?.size)
    }

    private fun store(plan: String): PublicDiscoveryPayloadStore =
        storeFor(REQUEST_ID, plan)

    private fun storeFor(requestId: String, plan: String): PublicDiscoveryPayloadStore =
        PublicDiscoveryPayloadStore(root, requestId, plan, crypto, { now })

    private fun result(extractedText: String = "Indexed public image/search candidate") = ProfileScanResult(
        candidate = UsernameCandidate(
            username = "public-candidate",
            platform = Platform.Website,
            url = "https://example.test/public-candidate",
            matchType = UsernameMatchType.FuzzyVariant,
            confidence = 0.61f,
            providerId = null
        ),
        exists = true,
        httpStatus = 200,
        displayName = "Public candidate",
        bio = "Indexed lead; review manually",
        profileImageUrl = "https://cdn.example.test/candidate.jpg",
        links = listOf("https://example.test/public-candidate"),
        extractedText = extractedText,
        findings = listOf(
            Finding(
                type = FindingType.PublicSearchEvidence,
                value = "Public candidate",
                sourceUrl = "https://example.test/public-candidate",
                evidenceSnippet = "Search index candidate",
                confidence = 0.61f,
                risk = RiskLevel.Low,
                remediation = "Review before attributing ownership"
            )
        ),
        confidenceSignals = listOf("Indexed by a public search source"),
        verified = false,
        verificationStatus = "Public search evidence — review manually",
        provenance = "public search via test",
        providerVerificationState = ProviderVerificationState.Present
    )

    private class TestPayloadCrypto : CheckpointCrypto {
        private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

        override fun encrypt(plaintext: ByteArray, aad: ByteArray): CheckpointCrypto.Encrypted {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(aad)
            return CheckpointCrypto.Encrypted(
                ivBase64 = Base64.getEncoder().encodeToString(cipher.iv),
                ciphertextBase64 = Base64.getEncoder().encodeToString(cipher.doFinal(plaintext))
            )
        }

        override fun decrypt(
            ivBase64: String,
            ciphertextBase64: String,
            aad: ByteArray
        ): ByteArray? = runCatching {
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

    companion object {
        private const val REQUEST_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        private const val OTHER_REQUEST_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        private val PLAN = "a".repeat(64)
        private val OTHER_PLAN = "b".repeat(64)
    }
}
