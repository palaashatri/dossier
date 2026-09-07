package io.dossier.app.domain.scanner

import io.dossier.app.domain.discovery.PivotSignalType
import io.dossier.app.domain.model.Platform
import io.dossier.app.domain.model.ProfileScanResult
import io.dossier.app.domain.model.UsernameCandidate
import io.dossier.app.domain.model.UsernameMatchType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class ProfileScannerPivotCompletionTest {
    private lateinit var root: File
    private lateinit var crypto: CheckpointCrypto
    private var now = 10_000L

    @Before
    fun setUp() {
        root = Files.createTempDirectory("dossier-pivot-completion").toFile()
        crypto = TestFrontierCrypto()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `completed pivots persist immediately while results retain queue order`() = runBlocking {
        val config = PivotFrontierConfig(maxTotalPivots = 3)
        val frontier = BoundedPivotFrontier(REQUEST_ID, config, nowMillis = { now })
        val store = PivotFrontierStore(root, REQUEST_ID, crypto, nowMillis = { now })
        val first = offer(frontier, "first")
        val second = offer(frontier, "second")
        val third = offer(frontier, "third")
        assertTrue(store.save(frontier))

        val firstStarted = CompletableDeferred<Unit>()
        val secondPersisted = CompletableDeferred<Unit>()
        val thirdPersisted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val releaseThird = CompletableDeferred<Unit>()

        val scan = async {
            collectPivotFetches(
                pending = listOf(first, second, third),
                frontier = frontier,
                frontierStore = store,
                fetch = { entry ->
                    when (entry.key) {
                        first.key -> {
                            firstStarted.complete(Unit)
                            releaseFirst.await()
                            result(entry)
                        }
                        second.key -> result(entry)
                        third.key -> {
                            releaseThird.await()
                            result(entry)
                        }
                        else -> error("unexpected pivot ${entry.key}")
                    }
                },
                onCompleted = { entry, _ ->
                    when (entry.key) {
                        second.key -> secondPersisted.complete(Unit)
                        third.key -> thirdPersisted.complete(Unit)
                    }
                }
            )
        }

        firstStarted.await()
        secondPersisted.await()
        assertEquals(
            listOf(first.key, third.key),
            store.load(config)!!.pending(10, 1).map { it.key }
        )

        releaseThird.complete(Unit)
        thirdPersisted.await()
        assertEquals(
            listOf(first.key),
            store.load(config)!!.pending(10, 1).map { it.key }
        )

        releaseFirst.complete(Unit)
        val results = scan.await()
        assertEquals(
            listOf(first, second, third).map { it.key },
            results.map { it.candidate.url }
        )
        assertEquals(0, store.load(config)!!.pendingCount)
    }

    @Test
    fun `cancellation leaves unfinished pivots pending and does not mark them complete`() = runBlocking {
        val config = PivotFrontierConfig(maxTotalPivots = 2)
        val frontier = BoundedPivotFrontier(REQUEST_ID, config, nowMillis = { now })
        val store = PivotFrontierStore(root, REQUEST_ID, crypto, nowMillis = { now })
        val first = offer(frontier, "in-flight")
        val second = offer(frontier, "completed")
        assertTrue(store.save(frontier))

        val firstStarted = CompletableDeferred<Unit>()
        val secondPersisted = CompletableDeferred<Unit>()
        val scan = async {
            collectPivotFetches(
                pending = listOf(first, second),
                frontier = frontier,
                frontierStore = store,
                fetch = { entry ->
                    if (entry.key == first.key) {
                        firstStarted.complete(Unit)
                        awaitCancellation()
                    }
                    result(entry)
                },
                onCompleted = { entry, _ ->
                    if (entry.key == second.key) secondPersisted.complete(Unit)
                }
            )
        }

        firstStarted.await()
        secondPersisted.await()
        scan.cancelAndJoin()

        val restored = store.load(config)!!
        assertEquals(listOf(first.key), restored.pending(10, 1).map { it.key })
        assertTrue(second.key in restored.snapshot().completedKeys)
        assertFalse(first.key in restored.snapshot().completedKeys)
    }

    @Test
    fun `only verified completed results become later-depth seeds`() {
        val config = PivotFrontierConfig(maxTotalPivots = 3)
        val frontier = BoundedPivotFrontier(REQUEST_ID, config, nowMillis = { now })
        val verified = offer(frontier, "verified")
        val unverified = offer(frontier, "unverified")
        val absent = offer(frontier, "absent")

        val laterDepthSeeds = verifiedPivotSeeds(
            listOf(
                result(verified, exists = true, verified = true),
                result(unverified, exists = true, verified = false),
                result(absent, exists = false, verified = false)
            )
        )

        assertEquals(listOf(verified.candidate.url), laterDepthSeeds.map { it.candidate.url })
    }

    private fun offer(frontier: BoundedPivotFrontier, username: String): PivotFrontierEntry =
        (frontier.offer(
            candidate = candidate(username),
            depth = 1,
            signalType = PivotSignalType.ExplicitProfileLink
        ) as PivotOffer.Admitted).entry

    private fun candidate(username: String) = UsernameCandidate(
        username = username,
        platform = Platform.GitHub,
        url = "https://example.com/$username",
        matchType = UsernameMatchType.Exact,
        confidence = 0.9f,
        providerId = "github"
    )

    private fun result(
        entry: PivotFrontierEntry,
        exists: Boolean = true,
        verified: Boolean = true
    ) = ProfileScanResult(
        candidate = entry.candidate,
        exists = exists,
        httpStatus = if (exists) 200 else null,
        displayName = entry.candidate.username,
        bio = null,
        links = emptyList(),
        extractedText = "",
        findings = emptyList(),
        confidenceSignals = emptyList(),
        verified = verified,
        verificationStatus = if (verified) "Verified" else "Candidate"
    )

    private class TestFrontierCrypto : CheckpointCrypto {
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
    }
}
