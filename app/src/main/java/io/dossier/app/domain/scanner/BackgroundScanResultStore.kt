package io.dossier.app.domain.scanner

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.dossier.app.domain.analysis.OsintAnalysisBundle
import io.dossier.app.domain.case.DossierCase
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted transient storage for durable WorkManager scans.
 *
 * Background results are deliberately separate from CaseStore: finishing a scan
 * never silently creates a user-saved case. The latest result can be restored into
 * ScanSession and promoted only after an explicit Save action.
 */
class BackgroundScanResultStore internal constructor(
    private val context: Context,
    private val directorySyncer: DirectorySyncer,
    private val crypto: BackgroundResultCrypto
) {
    constructor(context: Context) : this(
        context.applicationContext,
        AndroidDirectorySyncer(),
        AndroidKeystoreBackgroundResultCrypto()
    )

    internal constructor(
        context: Context,
        directorySyncer: DirectorySyncer
    ) : this(
        context,
        directorySyncer,
        AndroidKeystoreBackgroundResultCrypto()
    )

    @Serializable
    data class Snapshot(
        val workId: String,
        val completedAtUtc: String,
        val dossierCase: DossierCase,
        val analysis: OsintAnalysisBundle = OsintAnalysisBundle()
    )

    @Serializable
    private data class Envelope(
        val formatVersion: Int,
        val ivBase64: String,
        val ciphertextBase64: String,
        val plaintextSha256: String
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    fun save(
        workId: String,
        dossierCase: DossierCase,
        analysis: OsintAnalysisBundle = OsintAnalysisBundle()
    ): Boolean = runCatching {
        require(workId.isNotBlank())
        val snapshot = Snapshot(
            workId = workId,
            completedAtUtc = Instant.now().toString(),
            dossierCase = dossierCase,
            analysis = analysis
        )
        validateSnapshotShape(snapshot)
        val plaintext = json.encodeToString(snapshot).toByteArray(Charsets.UTF_8)
        if (plaintext.size > MAX_PLAINTEXT_BYTES) return false

        val sealed = crypto.encrypt(plaintext)
        if (sealed.iv.size != GCM_IV_BYTES ||
            sealed.ciphertext.size <= GCM_TAG_BYTES ||
            sealed.ciphertext.size > MAX_CIPHERTEXT_BYTES
        ) {
            return false
        }

        val envelope = Envelope(
            formatVersion = FORMAT_VERSION,
            ivBase64 = Base64.getEncoder().encodeToString(sealed.iv),
            ciphertextBase64 = Base64.getEncoder().encodeToString(sealed.ciphertext),
            plaintextSha256 = sha256(plaintext)
        )
        val encodedEnvelope = json.encodeToString(envelope)
        if (encodedEnvelope.toByteArray(Charsets.UTF_8).size > MAX_ENVELOPE_BYTES) {
            return false
        }
        writeEnvelopeAtomically(resultFile(), encodedEnvelope)
        true
    }.getOrDefault(false)

    fun load(): Snapshot? = runCatching {
        val file = resultFile()
        if (!file.exists() || !file.isFile) return null

        val envelopeBytes = readFileBounded(file, MAX_ENVELOPE_BYTES)
        if (envelopeBytes.isEmpty()) return null

        val envelope = json.decodeFromString<Envelope>(envelopeBytes.toString(Charsets.UTF_8))
        require(envelope.formatVersion == FORMAT_VERSION)
        require(envelope.ivBase64.length in 1..MAX_IV_BASE64_CHARS)
        require(envelope.ciphertextBase64.length in 1..MAX_CIPHERTEXT_BASE64_CHARS)
        require(envelope.plaintextSha256.length == SHA_256_HEX_LENGTH)

        val iv = Base64.getDecoder().decode(envelope.ivBase64)
        require(iv.size == GCM_IV_BYTES)

        val ciphertext = Base64.getDecoder().decode(envelope.ciphertextBase64)
        require(ciphertext.size in (GCM_TAG_BYTES + 1)..MAX_CIPHERTEXT_BYTES)

        val plaintext = crypto.decrypt(iv, ciphertext)
        require(plaintext.size <= MAX_PLAINTEXT_BYTES)
        require(sha256(plaintext).equals(envelope.plaintextSha256, ignoreCase = true))

        val snapshot = json.decodeFromString<Snapshot>(plaintext.toString(Charsets.UTF_8))
        validateSnapshotShape(snapshot)
        require(snapshot.workId.isNotBlank())
        require(snapshot.completedAtUtc.isNotBlank())
        snapshot
    }.getOrNull()

    /**
     * Byte limits bound allocation, but a compact JSON array can still contain
     * an unsafe number of objects or nested references. Validate collection
     * shape on both save and load so transient encrypted results fail closed
     * before they reach ScanSession/Compose.
     */
    private fun validateSnapshotShape(snapshot: Snapshot) {
        val dossierCase = snapshot.dossierCase
        val input = dossierCase.input
        requireCollection("input.aliases", input.aliases.size)
        requireCollection("input.emails", input.emails.size)
        requireCollection("input.phones", input.phones.size)
        requireCollection("input.locations", input.locations.size)
        requireCollection("input.organizations", input.organizations.size)
        requireCollection("input.usernames", input.usernames.size)
        requireCollection("input.profileUrls", input.profileUrls.size)

        requireCollection("case.findings", dossierCase.findings.size)
        requireCollection("case.evidenceRecords", dossierCase.evidenceRecords.size)
        requireCollection("case.profileResults", dossierCase.profileResults.size)
        requireCollection("case.faceMatches", dossierCase.faceMatches.size)
        requireCollection("case.breachDigests", dossierCase.breachDigests.size)
        requireCollection("case.attackPaths", dossierCase.attackPaths.size)
        requireCollection("case.relationshipConfidence", dossierCase.relationshipConfidence.size)
        requireCollection("case.scanHistory", dossierCase.scanHistory.size)
        requireCollection("case.userCorrections", dossierCase.userCorrections.size)
        requireCollection("case.remediationRecords", dossierCase.remediationRecords.size)
        requireCollection("case.exports", dossierCase.exports.size)

        val graph = dossierCase.entityGraph
        requireCollection("graph.entities", graph.entities.size)
        requireCollection("graph.edges", graph.edges.size)
        graph.entities.forEach { entity ->
            requireCollection("graph.entity.sourceUrls", entity.sourceUrls.size)
            requireCollection("graph.entity.evidenceIds", entity.evidenceIds.size)
        }
        graph.edges.forEach { edge ->
            requireCollection("graph.edge.evidenceIds", edge.evidenceIds.size)
            requireCollection("graph.edge.contradictingEvidenceIds", edge.contradictingEvidenceIds.size)
        }
        dossierCase.evidenceRecords.forEach { evidence ->
            requireCollection("evidence.signals", evidence.signals.size)
        }
        dossierCase.profileResults.forEach { profile ->
            requireCollection("profile.links", profile.links.size)
            requireCollection("profile.findings", profile.findings.size)
            requireCollection("profile.confidenceSignals", profile.confidenceSignals.size)
        }

        val media = dossierCase.mediaIntelligence
        require(media.imageResults.size <= MAX_MEDIA_IMAGE_RESULTS)
        require(media.videoResults.size <= MAX_MEDIA_VIDEO_RESULTS)
        media.imageResults.forEach { result ->
            requireCollection("media.image.labels", result.labels.size)
            requireCollection("media.image.webEvidence", result.webEvidence.size)
            requireCollection("media.image.visualMatches", result.visualMatches.size)
            requireCollection("media.image.visualCandidates", result.visualCandidates.size)
            requireCollection("media.image.visualClusters", result.visualClusters.size)
            result.visualClusters.forEach { cluster ->
                requireCollection("media.image.cluster.members", cluster.memberCandidateIds.size)
            }
        }
        media.videoResults.forEach { result ->
            requireCollection("media.video.labels", result.labels.size)
            requireCollection("media.video.webEvidence", result.webEvidence.size)
            requireCollection("media.video.frameSummaries", result.frameSummaries.size)
            result.frameSummaries.forEach { frame ->
                requireCollection("media.video.frame.labels", frame.labels.size)
            }
        }

        val identitySurface = snapshot.analysis.identitySurface
        requireCollection("analysis.identitySurface.entries", identitySurface.entries.size)
        val behavioral = snapshot.analysis.behavioral
        require(behavioral.hourlyActivityUtc.size == HOURS_PER_DAY)
        requireCollection("analysis.behavioral.timezoneHypotheses", behavioral.timezoneHypotheses.size)
        requireCollection("analysis.behavioral.topics", behavioral.topics.size)
        requireCollection("analysis.behavioral.crossSourceStyle", behavioral.crossSourceStyle.size)
        val interaction = snapshot.analysis.interactionGraph
        requireCollection("analysis.interaction.edges", interaction.edges.size)
        requireCollection("analysis.interaction.influenceNodes", interaction.influenceNodes.size)
        requireCollection("analysis.interaction.clusters", interaction.clusters.size)
        interaction.clusters.forEach { cluster ->
            requireCollection("analysis.interaction.cluster.nodes", cluster.nodes.size)
        }
    }

    private fun requireCollection(label: String, size: Int) {
        require(size in 0..MAX_COLLECTION_ITEMS) {
            "$label exceeds transient-result item limit"
        }
    }

    fun clear(): Boolean = runCatching {
        val file = resultFile()
        !file.exists() || file.delete()
    }.getOrDefault(false)

    private fun resultFile(): File = File(context.filesDir, FILE_NAME)

    private fun readFileBounded(file: File, maxBytes: Long): ByteArray {
        if (!file.exists() || !file.isFile) throw IOException("Missing or invalid file")
        val length = file.length()
        if (length > maxBytes) {
            throw IOException("File size $length exceeds limit of $maxBytes bytes")
        }
        val initialCapacity = if (length > 0) minOf(length + 1L, maxBytes + 1L).toInt() else 1024
        val out = ByteArrayOutputStream(initialCapacity)
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) {
                    throw IOException("Read exceeded limit of $maxBytes bytes")
                }
                out.write(buffer, 0, read)
            }
        }
        return out.toByteArray()
    }

    /**
     * Atomically replaces the encrypted envelope without unlinking the last
     * known-good result first. The file and its parent directory are both
     * synced so a reported success survives power loss.
     */
    internal fun writeEnvelopeAtomically(target: File, content: String) {
        val parent = target.parentFile ?: throw IOException("Missing result parent")
        if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("Unable to create result directory")
        }
        val temp = File(parent, "${target.name}.${UUID.randomUUID()}.tmp")
        var failure: Exception? = null
        try {
            FileOutputStream(temp).use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
            directorySyncer.sync(parent)
        } catch (error: Exception) {
            failure = error
        }
        if (temp.exists() && !temp.delete()) {
            val cleanup = IOException("Unable to remove temporary result file")
            if (failure == null) failure = cleanup else failure?.addSuppressed(cleanup)
        }
        failure?.let { throw it }
    }

    internal companion object {
        const val FILE_NAME = "background_scan_latest.dscan"
        const val FORMAT_VERSION = 1
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BYTES = 16
        const val GCM_TAG_BITS = 128
        const val MAX_PLAINTEXT_BYTES = 4 * 1024 * 1024 // 4 MB
        const val MAX_CIPHERTEXT_BYTES = MAX_PLAINTEXT_BYTES + GCM_TAG_BYTES
        const val MAX_ENVELOPE_BYTES = 8 * 1024 * 1024L // 8 MB
        const val SHA_256_HEX_LENGTH = 64
        const val MAX_IV_BASE64_CHARS = 32
        const val MAX_CIPHERTEXT_BASE64_CHARS = ((MAX_CIPHERTEXT_BYTES + 2) / 3) * 4 + 16
        const val MAX_COLLECTION_ITEMS = 10_000
        const val MAX_MEDIA_IMAGE_RESULTS = 12
        const val MAX_MEDIA_VIDEO_RESULTS = 6
        const val HOURS_PER_DAY = 24

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}

internal data class SealedResultPayload(
    val iv: ByteArray,
    val ciphertext: ByteArray
)

internal interface BackgroundResultCrypto {
    fun encrypt(plaintext: ByteArray): SealedResultPayload
    fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray
}

private class AndroidKeystoreBackgroundResultCrypto : BackgroundResultCrypto {
    override fun encrypt(plaintext: ByteArray): SealedResultPayload {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        return SealedResultPayload(cipher.iv, cipher.doFinal(plaintext))
    }

    override fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(BackgroundScanResultStore.GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "dossier-background-scan-v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
