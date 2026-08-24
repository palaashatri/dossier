package io.dossier.app.domain.scanner

import io.dossier.app.domain.discovery.ProviderVerificationState
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.Platform
import io.dossier.app.domain.model.ProfileScanResult
import io.dossier.app.domain.model.UsernameCandidate
import io.dossier.app.domain.model.UsernameMatchType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class ProfileScanCheckpointStoreTest {
    private lateinit var root: File
    private lateinit var crypto: JvmCheckpointCrypto
    private lateinit var syncer: RecordingDirectorySyncer
    private var now: Long = 10_000L

    @Before
    fun setUp() {
        root = Files.createTempDirectory("dossier-profile-checkpoint").toFile()
        crypto = JvmCheckpointCrypto()
        syncer = RecordingDirectorySyncer()
        now = 10_000L
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `scope requires canonical request and lowercase SHA-256 plan`() {
        assertThrows(IllegalArgumentException::class.java) {
            store(requestId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa".uppercase())
        }
        assertThrows(IllegalArgumentException::class.java) {
            store(planFingerprint = "not-a-fingerprint")
        }
        assertThrows(IllegalArgumentException::class.java) {
            store(planFingerprint = "A".repeat(64))
        }
    }

    @Test
    fun `canonical key lowercases scheme and host but preserves port path and query case`() {
        assertEquals(
            "https://example.com:8443/User/Path?Token=AbC",
            ProfileScanCheckpointStore.canonicalCandidateKey(
                " HTTPS://Example.COM:8443/User/Path?Token=AbC#ignored "
            )
        )
        assertNotEquals(
            ProfileScanCheckpointStore.canonicalCandidateKey("https://example.com/User"),
            ProfileScanCheckpointStore.canonicalCandidateKey("https://example.com/user")
        )
        assertEquals(
            "not a url",
            ProfileScanCheckpointStore.canonicalCandidateKey("not a url#fragment")
        )
    }

    @Test
    fun `plan fingerprint is order stable excludes PII and tracks policy shape`() {
        val candidates = listOf(
            candidate("https://EXAMPLE.com/Profile/A", "alpha"),
            candidate("https://example.com/Profile/B", "beta")
        )
        val input = IdentityInput(
            fullName = "Case Subject",
            aliases = listOf("Second", "First"),
            emails = listOf("USER@EXAMPLE.TEST"),
            profileUrls = listOf("https://example.com/Profile/A")
        )
        val reordered = input.copy(
            aliases = listOf("first", "second"),
            emails = listOf("user@example.test")
        )

        val first = ProfileScanCheckpointStore.planFingerprint(input, false, candidates)
        val second = ProfileScanCheckpointStore.planFingerprint(reordered, false, candidates.reversed())
        val changedSubjectSameShape = ProfileScanCheckpointStore.planFingerprint(
            IdentityInput(
                fullName = "Different Subject",
                emails = listOf("different@example.test"),
                profileUrls = listOf("https://different.example/Private/Path")
            ),
            false,
            candidates.mapIndexed { index, candidate ->
                candidate.copy(
                    username = "different-$index",
                    url = "https://different.example/Profile/$index"
                )
            }
        )
        val changedPolicyShape = ProfileScanCheckpointStore.planFingerprint(
            input,
            false,
            candidates.mapIndexed { index, candidate ->
                if (index == 0) candidate.copy(confidence = 0.5f) else candidate
            }
        )

        assertEquals(first, second)
        assertEquals(first, changedSubjectSameShape)
        assertNotEquals(first, changedPolicyShape)
        assertTrue(first.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `stable result round trips without plaintext path or payload leakage`() {
        val secret = "authorized-subject-token"
        val result = result(
            candidate("https://Example.com/User/Path?Token=KeepCase", secret),
            ProviderVerificationState.Present,
            extractedText = secret
        )
        val store = store()

        assertTrue(store.save(result))
        val file = store.resultFileForTesting(result.candidate)
        val envelopeText = file.readText()

        assertTrue(file.isFile)
        assertFalse(file.absolutePath.contains(secret))
        assertFalse(envelopeText.contains(secret))
        assertFalse(envelopeText.contains(result.candidate.url))
        assertEquals(result, store.load(result.candidate))
        assertTrue(syncer.syncedDirectories.isNotEmpty())
    }

    @Test
    fun `missing load performs no crypto operation`() {
        val store = store()

        assertNull(store.load(candidate("https://example.com/missing", "missing")))
        assertEquals(0, crypto.decryptCalls)
        assertEquals(0, crypto.encryptCalls)
    }

    @Test
    fun `only stable provider states are persisted`() {
        val reusable = setOf(
            ProviderVerificationState.Present,
            ProviderVerificationState.NotFound,
            ProviderVerificationState.SoftNotFound,
            ProviderVerificationState.AuthenticationRequired,
            ProviderVerificationState.RedirectedOutsideProvider
        )
        val store = store()

        ProviderVerificationState.entries.forEach { state ->
            val candidate = candidate("https://example.com/${state.name}", state.name)
            val saved = store.save(result(candidate, state))
            assertEquals("Unexpected policy for $state", state in reusable, saved)
            assertEquals("Unexpected load policy for $state", state in reusable, store.load(candidate) != null)
        }
        val unknown = result(candidate("https://example.com/unknown", "unknown"), null)
        assertFalse(store.save(unknown))
    }

    @Test
    fun `AAD prevents ciphertext reuse across request scopes`() {
        val original = store(requestId = REQUEST_A)
        val result = result(candidate("https://example.com/User", "user"), ProviderVerificationState.Present)
        assertTrue(original.save(result))

        val other = store(requestId = REQUEST_B)
        val otherFile = other.resultFileForTesting(result.candidate)
        assertTrue(requireNotNull(otherFile.parentFile).mkdirs())
        original.resultFileForTesting(result.candidate).copyTo(otherFile)

        assertNull(other.load(result.candidate))
    }

    @Test
    fun `expired and future-dated records fail closed`() {
        val store = store()
        val result = result(candidate("https://example.com/time", "time"), ProviderVerificationState.NotFound)
        assertTrue(store.save(result))

        now = 9_999L
        assertNull(store.load(result.candidate))
        now = 10_000L + ProfileScanCheckpointStore.MAX_AGE_MILLIS
        assertEquals(result, store.load(result.candidate))
        now += 1L
        assertNull(store.load(result.candidate))
    }

    @Test
    fun `corrupt and oversized envelopes fail closed before unbounded processing`() {
        val store = store()
        val candidate = candidate("https://example.com/corrupt", "corrupt")
        val file = store.resultFileForTesting(candidate)
        assertTrue(requireNotNull(file.parentFile).mkdirs())
        file.writeText("not-json")
        assertNull(store.load(candidate))

        file.writeBytes(ByteArray(ProfileScanCheckpointStore.MAX_ENVELOPE_BYTES + 1))
        val decryptCallsBefore = crypto.decryptCalls
        assertNull(store.load(candidate))
        assertEquals(decryptCallsBefore, crypto.decryptCalls)
    }

    @Test
    fun `oversized plaintext is rejected without creating a checkpoint`() {
        val store = store()
        val candidate = candidate("https://example.com/large", "large")
        val large = result(
            candidate,
            ProviderVerificationState.Present,
            extractedText = "x".repeat(ProfileScanCheckpointStore.MAX_PAYLOAD_BYTES)
        )

        assertFalse(store.save(large))
        assertFalse(store.resultFileForTesting(candidate).exists())
    }

    @Test
    fun `atomic replacement keeps latest result and leaves no temporary file`() {
        val store = store()
        val candidate = candidate("https://example.com/replace", "replace")
        val first = result(candidate, ProviderVerificationState.NotFound, extractedText = "first")
        val second = result(candidate, ProviderVerificationState.Present, extractedText = "second")

        assertTrue(store.save(first))
        assertTrue(store.save(second))

        assertEquals(second, store.load(candidate))
        assertFalse(
            requireNotNull(store.resultFileForTesting(candidate).parentFile)
                .listFiles().orEmpty()
                .any { it.name.endsWith(".tmp") }
        )
    }

    @Test
    fun `clear request removes only exact v2 scope and preserves legacy data`() {
        val first = store(requestId = REQUEST_A)
        val second = store(requestId = REQUEST_B)
        val firstResult = result(candidate("https://example.com/first", "first"), ProviderVerificationState.Present)
        val secondResult = result(candidate("https://example.com/second", "second"), ProviderVerificationState.NotFound)
        assertTrue(first.save(firstResult))
        assertTrue(second.save(secondResult))
        val legacy = File(root, "dossier_checkpoints/profile-v1/legacy.checkpoint")
        assertTrue(requireNotNull(legacy.parentFile).mkdirs())
        legacy.writeText("legacy")

        assertTrue(ProfileScanCheckpointStore.clearRequest(root, REQUEST_A, syncer))
        assertNull(first.load(firstResult.candidate))
        assertEquals(secondResult, second.load(secondResult.candidate))
        assertTrue(legacy.isFile)
        assertFalse(ProfileScanCheckpointStore.clearRequest(root, "../../scope", syncer))
    }

    @Test
    fun `durable clear tombstone prevents late worker from recreating request scope`() {
        val store = store(requestId = REQUEST_A)
        val before = result(candidate("https://example.com/before", "before"), ProviderVerificationState.Present)
        val late = result(candidate("https://example.com/late", "late"), ProviderVerificationState.Present)
        assertTrue(store.save(before))

        assertTrue(ProfileScanCheckpointStore.clearRequest(root, REQUEST_A, syncer))

        assertFalse(store.save(late))
        assertNull(store.load(before.candidate))
        assertNull(store.load(late.candidate))
        assertFalse(store.resultFileForTesting(late.candidate).exists())
    }

    @Test
    fun `clear all retires every observed request scope with one global lock`() {
        val first = store(requestId = REQUEST_A)
        val second = store(requestId = REQUEST_B)
        val firstResult = result(candidate("https://example.com/all-a", "all-a"), ProviderVerificationState.Present)
        val secondResult = result(candidate("https://example.com/all-b", "all-b"), ProviderVerificationState.NotFound)
        assertTrue(first.save(firstResult))
        assertTrue(second.save(secondResult))

        assertTrue(ProfileScanCheckpointStore.clearAll(root, syncer))

        assertNull(first.load(firstResult.candidate))
        assertNull(second.load(secondResult.candidate))
        assertFalse(first.save(firstResult))
        assertFalse(second.save(secondResult))
        val profileRoot = File(root, "dossier_checkpoints/profile-v2")
        assertEquals(1, profileRoot.listFiles().orEmpty().count { it.name.endsWith(".lock") })
        assertEquals(2, profileRoot.listFiles().orEmpty().count { it.name.endsWith(".cleared") })
    }

    @Test
    fun `expired orphan scopes and tombstones are pruned after bounded retention`() {
        val clearedStore = store(requestId = REQUEST_A)
        val orphanStore = store(requestId = REQUEST_B)
        val clearedResult = result(candidate("https://example.com/prune-a", "prune-a"), ProviderVerificationState.Present)
        val orphanResult = result(candidate("https://example.com/prune-b", "prune-b"), ProviderVerificationState.Present)
        assertTrue(clearedStore.save(clearedResult))
        assertTrue(orphanStore.save(orphanResult))
        assertTrue(ProfileScanCheckpointStore.clearRequest(root, REQUEST_A, syncer))

        val profileRoot = File(root, "dossier_checkpoints/profile-v2")
        val now = System.currentTimeMillis()
        val oldTimestamp = now - ProfileScanCheckpointStore.PROFILE_GUARD_RETENTION_MILLIS - 1L
        val oldTombstone = requireNotNull(
            profileRoot.listFiles().orEmpty().singleOrNull { it.name.endsWith(".cleared") }
        )
        val orphanDirectory = requireNotNull(
            orphanStore.resultFileForTesting(orphanResult.candidate).parentFile?.parentFile
        )
        assertTrue(oldTombstone.setLastModified(oldTimestamp))
        assertTrue(orphanDirectory.setLastModified(oldTimestamp))

        assertTrue(ProfileScanCheckpointStore.pruneExpiredProfileState(root, now, syncer))

        assertFalse(oldTombstone.exists())
        assertFalse(orphanDirectory.exists())
        assertFalse(orphanStore.save(orphanResult))
        assertEquals(1, profileRoot.listFiles().orEmpty().count { it.name.endsWith(".lock") })
    }

    @Test
    fun `legacy profile checkpoint roots are removed by migration cleanup`() {
        val legacy = File(root, "dossier_checkpoints/profile/secret.checkpoint")
        val legacyV1 = File(root, "dossier_checkpoints/profile-v1/secret.checkpoint")
        assertTrue(requireNotNull(legacy.parentFile).mkdirs())
        assertTrue(requireNotNull(legacyV1.parentFile).mkdirs())
        legacy.writeText("legacy-secret")
        legacyV1.writeText("legacy-secret-v1")

        assertTrue(ProfileScanCheckpointStore.clearLegacyProfileData(root, syncer))
        assertFalse(legacy.exists())
        assertFalse(legacyV1.exists())
    }

    @Test
    fun `malformed unsupported and credential URLs are not checkpointed`() {
        val store = store()
        listOf(
            candidate("not a url", "malformed"),
            candidate("ftp://example.com/user", "unsupported"),
            candidate("https://user:secret@example.com/profile", "credentials")
        ).forEach { candidate ->
            assertFalse(store.save(result(candidate, ProviderVerificationState.Present)))
            assertNull(store.load(candidate))
        }
    }

    @Test
    fun `concurrent saves remain readable within one request scope`() {
        val store = store()
        val results = (0 until 24).map { index ->
            result(
                candidate("https://example.com/User/$index", "user$index"),
                ProviderVerificationState.Present,
                extractedText = "result-$index"
            )
        }
        val executor = Executors.newFixedThreadPool(6)
        try {
            val writes = executor.invokeAll(results.map { value -> java.util.concurrent.Callable { store.save(value) } })
            assertTrue(writes.all { it.get() })
        } finally {
            executor.shutdownNow()
        }
        assertEquals(results, results.map { store.load(it.candidate) })
    }

    private fun store(
        requestId: String = REQUEST_A,
        planFingerprint: String = FINGERPRINT
    ): ProfileScanCheckpointStore = ProfileScanCheckpointStore(
        rootDir = root,
        requestId = requestId,
        planFingerprint = planFingerprint,
        crypto = crypto,
        clockMillis = { now },
        dirSyncer = syncer
    )

    private fun candidate(url: String, username: String): UsernameCandidate = UsernameCandidate(
        username = username,
        platform = Platform.Website,
        url = url,
        matchType = UsernameMatchType.Exact,
        confidence = 1.0f,
        providerId = "website"
    )

    private fun result(
        candidate: UsernameCandidate,
        state: ProviderVerificationState?,
        extractedText: String = ""
    ): ProfileScanResult = ProfileScanResult(
        candidate = candidate,
        exists = state == ProviderVerificationState.Present,
        httpStatus = if (state == ProviderVerificationState.Present) 200 else 404,
        displayName = null,
        bio = null,
        links = emptyList(),
        extractedText = extractedText,
        findings = emptyList(),
        confidenceSignals = emptyList(),
        providerVerificationState = state
    )

    companion object {
        private const val REQUEST_A = "11111111-1111-4111-8111-111111111111"
        private const val REQUEST_B = "22222222-2222-4222-8222-222222222222"
        private val FINGERPRINT = "a".repeat(64)
    }
}

private class RecordingDirectorySyncer : DirectorySyncer {
    val syncedDirectories = mutableListOf<File>()
    override fun sync(dir: File) {
        syncedDirectories += dir
    }
}

private class JvmCheckpointCrypto : CheckpointCrypto {
    private val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
    var encryptCalls: Int = 0
    var decryptCalls: Int = 0

    override fun encrypt(plaintext: ByteArray, aad: ByteArray): CheckpointCrypto.Encrypted {
        encryptCalls += 1
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
        decryptCalls += 1
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
