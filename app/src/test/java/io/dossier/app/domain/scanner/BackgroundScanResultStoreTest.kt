package io.dossier.app.domain.scanner

import android.content.Context
import android.content.ContextWrapper
import io.dossier.app.domain.analysis.OsintAnalysisBundle
import io.dossier.app.domain.case.DossierCase
import io.dossier.app.domain.model.DossierEntity
import io.dossier.app.domain.model.EntityGraph
import io.dossier.app.domain.model.EntityType
import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.FindingType
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.RiskLevel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class BackgroundScanResultStoreTest {
    private val root = Files.createTempDirectory("dossier-result-store-test").toFile()

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun atomicEnvelopeReplaceKeepsOneTargetAndSyncsDirectory() {
        val syncer = RecordingDirectorySyncer()
        val context = FakeContext(root)
        val store = BackgroundScanResultStore(context, syncer)
        val target = File(root, "background_scan_latest.dscan")
        target.writeText("old-envelope")

        store.writeEnvelopeAtomically(target, "new-envelope")

        assertEquals("new-envelope", target.readText())
        assertEquals(listOf(root.canonicalFile), syncer.synced.map(File::getCanonicalFile))
        assertFalse(root.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
    }

    @Test
    fun atomicEnvelopeWriteCreatesMissingParent() {
        val syncer = RecordingDirectorySyncer()
        val context = FakeContext(root)
        val store = BackgroundScanResultStore(context, syncer)
        val nested = File(root, "nested/result.dscan")

        store.writeEnvelopeAtomically(nested, "encrypted-envelope")

        assertTrue(nested.isFile)
        assertEquals("encrypted-envelope", nested.readText())
        assertEquals(requireNotNull(nested.parentFile).canonicalFile, syncer.synced.single().canonicalFile)
    }

    @Test
    fun validSaveAndLoadRoundTripsSnapshotWithCaseAndAnalysis() {
        val syncer = RecordingDirectorySyncer()
        val context = FakeContext(root)
        val crypto = JvmBackgroundResultCrypto()
        val store = BackgroundScanResultStore(context, syncer, crypto)

        val case = DossierCase(
            caseId = "test-case-id",
            createdAt = "2026-08-24T00:00:00Z",
            subjectName = "Test Subject",
            input = IdentityInput(fullName = "Test Subject", primaryUsername = "testuser"),
            findings = listOf(
                Finding(
                    type = FindingType.Username,
                    value = "testuser",
                    sourceUrl = "https://example.com/testuser",
                    evidenceSnippet = "Profile snippet",
                    confidence = 0.9f,
                    risk = RiskLevel.Low,
                    remediation = "None needed"
                )
            )
        )
        val analysis = OsintAnalysisBundle()

        val saved = store.save("work-123", case, analysis)
        assertTrue(saved)

        val loaded = store.load()
        assertNotNull(loaded)
        assertEquals("work-123", loaded!!.workId)
        assertEquals(case.caseId, loaded.dossierCase.caseId)
        assertEquals(case.subjectName, loaded.dossierCase.subjectName)
        assertEquals(case.input.fullName, loaded.dossierCase.input.fullName)
        assertEquals(case.findings.size, loaded.dossierCase.findings.size)
        assertEquals(case.findings.first().value, loaded.dossierCase.findings.first().value)
        assertEquals(analysis, loaded.analysis)
        assertTrue(loaded.completedAtUtc.isNotBlank())
    }

    @Test
    fun oversizedEnvelopeFileIsRejectedWithoutThrowing() {
        val syncer = RecordingDirectorySyncer()
        val context = FakeContext(root)
        val crypto = JvmBackgroundResultCrypto()
        val store = BackgroundScanResultStore(context, syncer, crypto)
        val file = File(root, "background_scan_latest.dscan")

        // Write a file that exceeds MAX_ENVELOPE_BYTES (8 MB)
        file.writeBytes(ByteArray((BackgroundScanResultStore.MAX_ENVELOPE_BYTES + 1).toInt()))

        val loaded = store.load()
        assertNull(loaded)
    }

    @Test
    fun oversizedCiphertextBase64IsRejectedWithoutThrowing() {
        val syncer = RecordingDirectorySyncer()
        val context = FakeContext(root)
        val crypto = JvmBackgroundResultCrypto()
        val store = BackgroundScanResultStore(context, syncer, crypto)
        val file = File(root, "background_scan_latest.dscan")

        val oversizedCiphertext = "A".repeat(BackgroundScanResultStore.MAX_CIPHERTEXT_BASE64_CHARS + 1)
        val validIv = Base64.getEncoder().encodeToString(ByteArray(BackgroundScanResultStore.GCM_IV_BYTES))
        val validSha = "0".repeat(BackgroundScanResultStore.SHA_256_HEX_LENGTH)
        file.writeText(
            """{"formatVersion":1,"ivBase64":"$validIv","ciphertextBase64":"$oversizedCiphertext","plaintextSha256":"$validSha"}"""
        )

        val loaded = store.load()
        assertNull(loaded)
    }

    @Test
    fun oversizedPlaintextIsRejectedOnSave() {
        val syncer = RecordingDirectorySyncer()
        val context = FakeContext(root)
        val crypto = JvmBackgroundResultCrypto()
        val store = BackgroundScanResultStore(context, syncer, crypto)

        val hugeSnippet = "X".repeat(BackgroundScanResultStore.MAX_PLAINTEXT_BYTES + 1024)
        val hugeCase = DossierCase(
            caseId = "huge-case",
            createdAt = "2026-08-24T00:00:00Z",
            subjectName = "Huge",
            input = IdentityInput(fullName = "Huge"),
            findings = listOf(
                Finding(
                    type = FindingType.SensitiveSnippet,
                    value = "Snippet",
                    sourceUrl = null,
                    evidenceSnippet = hugeSnippet,
                    confidence = 0.5f,
                    risk = RiskLevel.Low,
                    remediation = ""
                )
            )
        )

        val saved = store.save("work-huge", hugeCase)
        assertFalse(saved)
        assertNull(store.load())
    }

    @Test
    fun oversizedGraphCollectionIsRejectedBeforeEncryption() {
        val syncer = RecordingDirectorySyncer()
        val context = FakeContext(root)
        val crypto = JvmBackgroundResultCrypto()
        val store = BackgroundScanResultStore(context, syncer, crypto)
        val oversizedCase = oversizedGraphCase()

        assertFalse(store.save("work-oversized-graph", oversizedCase))
        assertNull(store.load())
        assertFalse(File(root, BackgroundScanResultStore.FILE_NAME).exists())
    }

    @Test
    fun oversizedGraphCollectionIsRejectedAfterAuthenticatedLoad() {
        val syncer = RecordingDirectorySyncer()
        val context = FakeContext(root)
        val crypto = JvmBackgroundResultCrypto()
        val store = BackgroundScanResultStore(context, syncer, crypto)
        val snapshot = BackgroundScanResultStore.Snapshot(
            workId = "work-oversized-graph-load",
            completedAtUtc = "2026-08-24T00:00:00Z",
            dossierCase = oversizedGraphCase()
        )
        val json = Json { encodeDefaults = true; explicitNulls = false }
        val plaintext = json.encodeToString(snapshot).toByteArray(Charsets.UTF_8)
        val sealed = crypto.encrypt(plaintext)
        File(root, BackgroundScanResultStore.FILE_NAME).writeText(
            """{"formatVersion":1,"ivBase64":"${Base64.getEncoder().encodeToString(sealed.iv)}","ciphertextBase64":"${Base64.getEncoder().encodeToString(sealed.ciphertext)}","plaintextSha256":"${BackgroundScanResultStore.sha256(plaintext)}"}"""
        )

        assertNull(store.load())
    }

    @Test
    fun malformedJsonEnvelopeIsRejectedWithoutThrowing() {
        val syncer = RecordingDirectorySyncer()
        val context = FakeContext(root)
        val crypto = JvmBackgroundResultCrypto()
        val store = BackgroundScanResultStore(context, syncer, crypto)
        val file = File(root, "background_scan_latest.dscan")

        file.writeText("not-valid-json-envelope")
        assertNull(store.load())
    }

    @Test
    fun unsupportedFormatVersionIsRejected() {
        val syncer = RecordingDirectorySyncer()
        val context = FakeContext(root)
        val crypto = JvmBackgroundResultCrypto()
        val store = BackgroundScanResultStore(context, syncer, crypto)
        val file = File(root, "background_scan_latest.dscan")

        val validIv = Base64.getEncoder().encodeToString(ByteArray(BackgroundScanResultStore.GCM_IV_BYTES))
        val validCiphertext = Base64.getEncoder().encodeToString(ByteArray(32))
        val validSha = "0".repeat(BackgroundScanResultStore.SHA_256_HEX_LENGTH)
        file.writeText(
            """{"formatVersion":99,"ivBase64":"$validIv","ciphertextBase64":"$validCiphertext","plaintextSha256":"$validSha"}"""
        )

        assertNull(store.load())
    }

    @Test
    fun corruptedCiphertextFailsAuthenticationAndReturnsNull() {
        val syncer = RecordingDirectorySyncer()
        val context = FakeContext(root)
        val crypto = JvmBackgroundResultCrypto()
        val store = BackgroundScanResultStore(context, syncer, crypto)

        val case = DossierCase(
            caseId = "c1",
            createdAt = "2026-08-24T00:00:00Z",
            subjectName = "Subject",
            input = IdentityInput(fullName = "Subject")
        )
        assertTrue(store.save("work-1", case))

        val file = File(root, "background_scan_latest.dscan")
        val jsonText = file.readText()
        val marker = "\"ciphertextBase64\":\""
        val idx = jsonText.indexOf(marker) + marker.length
        val corruptChar = if (jsonText[idx] == 'A') 'B' else 'A'
        val corruptedJson = jsonText.substring(0, idx) + corruptChar + jsonText.substring(idx + 1)
        file.writeText(corruptedJson)

        val loaded = store.load()
        assertNull(loaded)
    }

    @Test
    fun mismatchedSha256ChecksumFailsIntegrityAndReturnsNull() {
        val syncer = RecordingDirectorySyncer()
        val context = FakeContext(root)
        val crypto = JvmBackgroundResultCrypto()
        val store = BackgroundScanResultStore(context, syncer, crypto)

        val case = DossierCase(
            caseId = "c1",
            createdAt = "2026-08-24T00:00:00Z",
            subjectName = "Subject",
            input = IdentityInput(fullName = "Subject")
        )
        assertTrue(store.save("work-1", case))

        val file = File(root, "background_scan_latest.dscan")
        val jsonText = file.readText()
        val marker = "\"plaintextSha256\":\""
        val idx = jsonText.indexOf(marker) + marker.length
        val corruptChar = if (jsonText[idx] == '0') '1' else '0'
        val corruptedJson = jsonText.substring(0, idx) + corruptChar + jsonText.substring(idx + 1)
        file.writeText(corruptedJson)

        val loaded = store.load()
        assertNull(loaded)
    }

    @Test
    fun invalidIvLengthIsRejectedWithoutThrowing() {
        val syncer = RecordingDirectorySyncer()
        val context = FakeContext(root)
        val crypto = JvmBackgroundResultCrypto()
        val store = BackgroundScanResultStore(context, syncer, crypto)
        val file = File(root, "background_scan_latest.dscan")

        // 8-byte IV instead of 12 bytes
        val shortIv = Base64.getEncoder().encodeToString(ByteArray(8))
        val ciphertext = Base64.getEncoder().encodeToString(ByteArray(32))
        val sha = "0".repeat(BackgroundScanResultStore.SHA_256_HEX_LENGTH)
        file.writeText(
            """{"formatVersion":1,"ivBase64":"$shortIv","ciphertextBase64":"$ciphertext","plaintextSha256":"$sha"}"""
        )

        assertNull(store.load())
    }

    @Test
    fun truncatedCiphertextShorterThanTagIsRejected() {
        val syncer = RecordingDirectorySyncer()
        val context = FakeContext(root)
        val crypto = JvmBackgroundResultCrypto()
        val store = BackgroundScanResultStore(context, syncer, crypto)
        val file = File(root, "background_scan_latest.dscan")

        val validIv = Base64.getEncoder().encodeToString(ByteArray(BackgroundScanResultStore.GCM_IV_BYTES))
        // 10-byte ciphertext (shorter than 16-byte GCM tag)
        val shortCiphertext = Base64.getEncoder().encodeToString(ByteArray(10))
        val sha = "0".repeat(BackgroundScanResultStore.SHA_256_HEX_LENGTH)
        file.writeText(
            """{"formatVersion":1,"ivBase64":"$validIv","ciphertextBase64":"$shortCiphertext","plaintextSha256":"$sha"}"""
        )

        assertNull(store.load())
    }

    @Test
    fun emptyFileFailsClosedAndReturnsNull() {
        val syncer = RecordingDirectorySyncer()
        val context = FakeContext(root)
        val crypto = JvmBackgroundResultCrypto()
        val store = BackgroundScanResultStore(context, syncer, crypto)
        val file = File(root, "background_scan_latest.dscan")
        file.writeBytes(ByteArray(0))

        assertNull(store.load())
    }

    @Test
    fun clearRemovesResultFile() {
        val syncer = RecordingDirectorySyncer()
        val context = FakeContext(root)
        val crypto = JvmBackgroundResultCrypto()
        val store = BackgroundScanResultStore(context, syncer, crypto)

        val case = DossierCase(
            caseId = "c1",
            createdAt = "2026-08-24T00:00:00Z",
            subjectName = "Subject",
            input = IdentityInput(fullName = "Subject")
        )
        assertTrue(store.save("work-1", case))
        val file = File(root, "background_scan_latest.dscan")
        assertTrue(file.exists())

        assertTrue(store.clear())
        assertFalse(file.exists())
        assertNull(store.load())
    }

    private class RecordingDirectorySyncer : DirectorySyncer {
        val synced = mutableListOf<File>()

        override fun sync(dir: File) {
            synced += dir
        }
    }

    private fun oversizedGraphCase(): DossierCase = DossierCase(
        caseId = "oversized-graph",
        createdAt = "2026-08-24T00:00:00Z",
        subjectName = "Oversized",
        input = IdentityInput(fullName = "Oversized"),
        entityGraph = EntityGraph(
            entities = List(BackgroundScanResultStore.MAX_COLLECTION_ITEMS + 1) { index ->
                DossierEntity(
                    id = "entity-$index",
                    type = EntityType.Profile,
                    label = "profile-$index"
                )
            }
        )
    )

    private class FakeContext(private val root: File) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = root
    }

    private class JvmBackgroundResultCrypto : BackgroundResultCrypto {
        private val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")

        override fun encrypt(plaintext: ByteArray): SealedResultPayload {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            return SealedResultPayload(cipher.iv, cipher.doFinal(plaintext))
        }

        override fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            return cipher.doFinal(ciphertext)
        }
    }
}
