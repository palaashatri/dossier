package io.dossier.app.domain.scanner

import io.dossier.app.domain.discovery.PivotAdmissionDecision
import io.dossier.app.domain.discovery.PivotSignalType
import io.dossier.app.domain.model.Platform
import io.dossier.app.domain.model.UsernameCandidate
import io.dossier.app.domain.model.UsernameMatchType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

class PivotFrontierTest {
    private lateinit var root: File
    private lateinit var crypto: TestFrontierCrypto
    private var now = 10_000L

    @Before
    fun setUp() {
        root = Files.createTempDirectory("dossier-pivot-frontier").toFile()
        crypto = TestFrontierCrypto()
        now = 10_000L
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `per signal and total budgets produce inspectable rejection diagnostics`() {
        val frontier = BoundedPivotFrontier(
            REQUEST_ID,
            PivotFrontierConfig(
                maxTotalPivots = 1,
                perSignalBudgets = PivotFrontierConfig.defaultSignalBudgets()
                    .toMutableMap()
                    .apply { this[PivotSignalType.ExplicitProfileLink] = 1 }
            ),
            { now }
        )

        assertTrue(frontier.offer(candidate("one"), 1, PivotSignalType.ExplicitProfileLink) is PivotOffer.Admitted)
        val totalRejected = frontier.offer(candidate("two"), 1, PivotSignalType.ExplicitProfileLink)
        assertTrue(totalRejected is PivotOffer.Rejected)
        assertTrue((totalRejected as PivotOffer.Rejected).diagnostic.reason.contains("budget"))
        assertEquals(1, frontier.admittedCount)
        assertEquals(1, frontier.rejectedCount)
        assertEquals(2, frontier.decisionDiagnostics.size)
    }

    @Test
    fun `weak signal rejection does not poison later corroborated evidence`() {
        val frontier = BoundedPivotFrontier(REQUEST_ID, PivotFrontierConfig(), { now })
        val weak = frontier.offer(
            candidate("support"),
            depth = 1,
            signalType = PivotSignalType.CommonUsername,
            confidence = 0.60f,
            corroboratingEvidenceCount = 1
        )
        assertTrue(weak is PivotOffer.Rejected)

        val corroborated = frontier.offer(
            candidate("support"),
            depth = 1,
            signalType = PivotSignalType.CommonUsername,
            confidence = 0.80f,
            corroboratingEvidenceCount = 2
        )
        assertTrue(corroborated is PivotOffer.Admitted)
        assertEquals(1, frontier.pendingCount)
        assertTrue(frontier.decisionDiagnostics.any { !it.admitted })
        assertTrue(frontier.decisionDiagnostics.any { it.admitted })
    }

    @Test
    fun `depth and unsafe URL are rejected without queueing`() {
        val frontier = BoundedPivotFrontier(REQUEST_ID, PivotFrontierConfig(), { now })
        val tooDeep = frontier.offer(candidate("deep"), 3, PivotSignalType.ExplicitProfileLink)
        val unsafe = frontier.offer(
            candidate("unsafe", url = "file:///private/unsafe"),
            1,
            PivotSignalType.ExplicitProfileLink
        )

        assertTrue(tooDeep is PivotOffer.Rejected)
        assertTrue(unsafe is PivotOffer.Rejected)
        assertEquals(0, frontier.pendingCount)
        assertEquals(2, frontier.rejectedCount)
    }

    @Test
    fun `configured depth loop drains exact depths and honors shared budget`() {
        val config = PivotFrontierConfig(
            maxDepth = 3,
            maxTotalPivots = 3
        )
        val frontier = BoundedPivotFrontier(REQUEST_ID, config, { now })

        (1..3).forEach { depth ->
            assertTrue(
                frontier.offer(
                    candidate("depth-$depth"),
                    depth = depth,
                    signalType = PivotSignalType.ExplicitProfileLink
                ) is PivotOffer.Admitted
            )
        }
        val overBudget = frontier.offer(
            candidate("depth-over-budget"),
            depth = 3,
            signalType = PivotSignalType.ExplicitProfileLink
        )
        assertTrue(overBudget is PivotOffer.Rejected)
        assertTrue((overBudget as PivotOffer.Rejected).diagnostic.reason.contains("budget"))

        (1..3).forEach { depth ->
            val pending = frontier.pendingAtDepth(maxEntries = 1, depth = depth)
            assertEquals(1, pending.size)
            assertEquals(depth, pending.single().depth)
            frontier.complete(pending.single().key)
        }
        assertEquals(0, frontier.pendingCount)
    }

    @Test
    fun `persisted pending entry keeps configured depth for resume`() {
        val config = PivotFrontierConfig(maxDepth = 3)
        val store = PivotFrontierStore(root, REQUEST_ID, crypto, nowMillis = { now })
        val frontier = BoundedPivotFrontier(REQUEST_ID, config, nowMillis = { now })
        val entry = (frontier.offer(
            candidate("resume-depth-three"),
            depth = 3,
            signalType = PivotSignalType.ExplicitProfileLink
        ) as PivotOffer.Admitted).entry

        assertTrue(store.save(frontier))
        val restored = store.load(config)
        assertNotNull(restored)
        assertTrue(restored!!.pendingAtDepth(maxEntries = 1, depth = 1).isEmpty())
        assertEquals(entry.key, restored.pendingAtDepth(maxEntries = 1, depth = 3).single().key)
    }

    @Test
    fun `pending entries survive encrypted store round trip until acknowledged`() {
        val store = PivotFrontierStore(root, REQUEST_ID, crypto, nowMillis = { now })
        val config = PivotFrontierConfig()
        val frontier = BoundedPivotFrontier(REQUEST_ID, config, nowMillis = { now })
        val entry = (frontier.offer(candidate("pending"), 1, PivotSignalType.ExplicitProfileLink)
            as PivotOffer.Admitted).entry
        assertTrue(store.save(frontier))

        val envelope = store.frontierFileForTesting().readText()
        assertFalse(envelope.contains(entry.candidate.url))
        val restored = store.load(config)
        assertNotNull(restored)
        assertEquals(1, restored!!.pendingCount)
        assertEquals(entry.key, restored.pending(1, 1).single().key)

        restored.complete(entry.key)
        assertTrue(store.save(restored))
        assertEquals(0, store.load(config)!!.pendingCount)
    }

    @Test
    fun `store rejects tampering and request scope reuse`() {
        val store = PivotFrontierStore(root, REQUEST_ID, crypto, nowMillis = { now })
        val frontier = BoundedPivotFrontier(REQUEST_ID, PivotFrontierConfig())
        frontier.offer(candidate("tamper"), 1, PivotSignalType.ExplicitProfileLink)
        assertTrue(store.save(frontier))
        val file = store.frontierFileForTesting()
        file.writeText(file.readText().replace("A", "B"))
        assertNull(store.load(PivotFrontierConfig()))

        val other = PivotFrontierStore(root, OTHER_REQUEST_ID, crypto, nowMillis = { now })
        assertNull(other.load(PivotFrontierConfig()))
    }

    @Test
    fun `expired frontier fails closed`() {
        val store = PivotFrontierStore(root, REQUEST_ID, crypto, nowMillis = { now })
        val frontier = BoundedPivotFrontier(REQUEST_ID, PivotFrontierConfig())
        frontier.offer(candidate("expired"), 1, PivotSignalType.ExplicitProfileLink)
        assertTrue(store.save(frontier))
        now += 7L * 24L * 60L * 60L * 1000L + 1L
        assertNull(store.load(PivotFrontierConfig()))
    }

    @Test
    fun `clear tombstone prevents a late worker from recreating request state`() {
        val store = PivotFrontierStore(root, REQUEST_ID, crypto, nowMillis = { now })
        val frontier = BoundedPivotFrontier(REQUEST_ID, PivotFrontierConfig())
        frontier.offer(candidate("retired"), 1, PivotSignalType.ExplicitProfileLink)
        assertTrue(store.save(frontier))

        assertTrue(store.clear())
        assertFalse(store.frontierFileForTesting().exists())
        assertFalse(store.save(frontier))
        assertNull(store.load(PivotFrontierConfig()))
    }

    @Test
    fun `global clear retires every known request frontier`() {
        val first = PivotFrontierStore(root, REQUEST_ID, crypto, nowMillis = { now })
        val second = PivotFrontierStore(root, OTHER_REQUEST_ID, crypto, nowMillis = { now })
        val firstFrontier = BoundedPivotFrontier(REQUEST_ID, PivotFrontierConfig())
        val secondFrontier = BoundedPivotFrontier(OTHER_REQUEST_ID, PivotFrontierConfig())
        firstFrontier.offer(candidate("first"), 1, PivotSignalType.ExplicitProfileLink)
        secondFrontier.offer(candidate("second"), 1, PivotSignalType.ExplicitProfileLink)
        assertTrue(first.save(firstFrontier))
        assertTrue(second.save(secondFrontier))

        assertTrue(
            PivotFrontierStore.clearAll(
                rootDir = root,
                dirSyncer = object : DirectorySyncer {
                    override fun sync(dir: File) = Unit
                }
            )
        )
        assertFalse(first.frontierFileForTesting().exists())
        assertFalse(second.frontierFileForTesting().exists())
        assertFalse(first.save(firstFrontier))
        assertFalse(second.save(secondFrontier))
    }

    private fun candidate(
        username: String,
        url: String = "https://example.com/$username"
    ) = UsernameCandidate(
        username = username,
        platform = Platform.GitHub,
        url = url,
        matchType = UsernameMatchType.FuzzyVariant,
        confidence = 0.70f,
        providerId = "github"
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
        private const val OTHER_REQUEST_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    }
}
