package io.dossier.app.domain.scanner

import io.dossier.app.domain.discovery.ProviderVerificationState
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.Platform
import io.dossier.app.domain.model.ProfileScanResult
import io.dossier.app.domain.model.UsernameCandidate
import io.dossier.app.domain.model.UsernameMatchType
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class ProfileInitialPassExecutorTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("dossier-initial-profile-pass").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `mixed cache queues only misses and preserves candidate order`() = runBlocking {
        val candidates = candidates()
        val cached = result(candidates[1], ProviderVerificationState.NotFound, "cached")
        val access = MapCheckpointAccess().apply { values[key(candidates[1])] = cached }
        val queued = mutableListOf<UsernameCandidate>()
        val fetched = Collections.synchronizedList(mutableListOf<UsernameCandidate>())

        val results = ProfileInitialPassExecutor.execute(
            candidates = candidates,
            checkpoint = access,
            queueMiss = queued::add,
            fetchMiss = { candidate ->
                if (candidate == candidates[0]) delay(20)
                fetched += candidate
                result(candidate, ProviderVerificationState.Present, "fresh-${candidate.username}")
            }
        )

        assertEquals(listOf(candidates[0], candidates[2]), queued)
        assertEquals(setOf(candidates[0], candidates[2]), fetched.toSet())
        assertEquals(candidates, results.map { it.candidate })
        assertEquals("cached", results[1].extractedText)
        assertEquals(setOf(key(candidates[0]), key(candidates[2])), access.savedKeys.toSet())
    }

    @Test
    fun `same request restores stable results while new request refetches`() = runBlocking {
        val candidates = candidates()
        val input = IdentityInput(fullName = "Authorized Subject", usernames = listOf("alpha"))
        val plan = ProfileScanCheckpointStore.planFingerprint(input, false, candidates)
        val crypto = ExecutorJvmCrypto()
        val syncer = object : DirectorySyncer { override fun sync(dir: File) = Unit }
        fun store(requestId: String) = ProfileScanCheckpointStore(
            rootDir = root,
            requestId = requestId,
            planFingerprint = plan,
            crypto = crypto,
            clockMillis = { 1_000L },
            dirSyncer = syncer
        )

        val firstFetches = AtomicInteger()
        val first = ProfileInitialPassExecutor.execute(
            candidates,
            store(REQUEST_A),
            queueMiss = {},
            fetchMiss = { candidate ->
                firstFetches.incrementAndGet()
                result(candidate, ProviderVerificationState.Present, "stable-${candidate.username}")
            }
        )
        assertEquals(candidates.size, firstFetches.get())

        var resumeQueues = 0
        val resumeFetches = AtomicInteger()
        val resumed = ProfileInitialPassExecutor.execute(
            candidates,
            store(REQUEST_A),
            queueMiss = { resumeQueues += 1 },
            fetchMiss = { candidate ->
                resumeFetches.incrementAndGet()
                result(candidate, ProviderVerificationState.Present, "unexpected")
            }
        )
        assertEquals(0, resumeQueues)
        assertEquals(0, resumeFetches.get())
        assertEquals(first, resumed)

        val newRequestFetches = AtomicInteger()
        ProfileInitialPassExecutor.execute(
            candidates,
            store(REQUEST_B),
            queueMiss = {},
            fetchMiss = { candidate ->
                newRequestFetches.incrementAndGet()
                result(candidate, ProviderVerificationState.NotFound, "new-request")
            }
        )
        assertEquals(candidates.size, newRequestFetches.get())
    }

    @Test
    fun `transient result is never restored and is retried`() = runBlocking {
        val candidate = candidates().first()
        val access = MapCheckpointAccess()
        var fetches = 0

        val first = ProfileInitialPassExecutor.execute(
            listOf(candidate),
            access,
            queueMiss = {},
            fetchMiss = {
                fetches += 1
                result(it, ProviderVerificationState.RateLimited, "transient")
            }
        )
        val second = ProfileInitialPassExecutor.execute(
            listOf(candidate),
            access,
            queueMiss = {},
            fetchMiss = {
                fetches += 1
                result(it, ProviderVerificationState.Present, "recovered")
            }
        )

        assertEquals("transient", first.single().extractedText)
        assertEquals("recovered", second.single().extractedText)
        assertEquals(2, fetches)
        assertEquals(listOf(key(candidate)), access.savedKeys)
    }

    @Test
    fun `checkpoint failures degrade to real work without dropping results`() = runBlocking {
        val candidates = candidates()
        val failing = object : ProfileCheckpointAccess {
            override fun load(candidate: UsernameCandidate): ProfileScanResult? = error("read failed")
            override fun save(result: ProfileScanResult): Boolean = error("write failed")
        }
        val queued = mutableListOf<UsernameCandidate>()

        val results = ProfileInitialPassExecutor.execute(
            candidates,
            failing,
            queueMiss = queued::add,
            fetchMiss = { result(it, ProviderVerificationState.Present, "fresh") }
        )

        assertEquals(candidates, queued)
        assertEquals(candidates, results.map { it.candidate })
        assertTrue(results.all { it.extractedText == "fresh" })
    }

    private fun candidates(): List<UsernameCandidate> = listOf(
        candidate("https://example.com/User/A", "a"),
        candidate("https://example.com/User/B", "b"),
        candidate("https://example.com/User/C", "c")
    )

    private fun candidate(url: String, username: String) = UsernameCandidate(
        username = username,
        platform = Platform.Website,
        url = url,
        matchType = UsernameMatchType.Exact,
        confidence = 1.0f,
        providerId = "website"
    )

    private fun result(
        candidate: UsernameCandidate,
        state: ProviderVerificationState,
        text: String
    ) = ProfileScanResult(
        candidate = candidate,
        exists = state == ProviderVerificationState.Present,
        httpStatus = if (state == ProviderVerificationState.Present) 200 else 404,
        displayName = null,
        bio = null,
        links = emptyList(),
        extractedText = text,
        findings = emptyList(),
        confidenceSignals = emptyList(),
        providerVerificationState = state
    )

    private fun key(candidate: UsernameCandidate) =
        ProfileScanCheckpointStore.canonicalCandidateKey(candidate.url)

    companion object {
        private const val REQUEST_A = "11111111-1111-4111-8111-111111111111"
        private const val REQUEST_B = "22222222-2222-4222-8222-222222222222"
    }
}

private class MapCheckpointAccess : ProfileCheckpointAccess {
    val values = ConcurrentHashMap<String, ProfileScanResult>()
    val savedKeys = Collections.synchronizedList(mutableListOf<String>())

    override fun load(candidate: UsernameCandidate): ProfileScanResult? =
        values[ProfileScanCheckpointStore.canonicalCandidateKey(candidate.url)]

    override fun save(result: ProfileScanResult): Boolean {
        val key = ProfileScanCheckpointStore.canonicalCandidateKey(result.candidate.url)
        savedKeys += key
        values[key] = result
        return true
    }
}

private class ExecutorJvmCrypto : CheckpointCrypto {
    private val key = SecretKeySpec(ByteArray(32) { 7 }, "AES")

    override fun encrypt(plaintext: ByteArray, aad: ByteArray): CheckpointCrypto.Encrypted {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad)
        return CheckpointCrypto.Encrypted(
            Base64.getEncoder().encodeToString(cipher.iv),
            Base64.getEncoder().encodeToString(cipher.doFinal(plaintext))
        )
    }

    override fun decrypt(ivBase64: String, ciphertextBase64: String, aad: ByteArray): ByteArray? =
        runCatching {
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
