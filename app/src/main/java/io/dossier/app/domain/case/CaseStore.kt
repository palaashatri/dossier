package io.dossier.app.domain.case

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.dossier.app.domain.discovery.ScanHistoryRuntime
import io.dossier.app.domain.evidence.EvidenceIdPolicy
import io.dossier.app.domain.place.MediaIntelligenceSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypted, versioned, local-only case store. */
class CaseStore(private val context: Context) {

    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }
    private val dir: File
        get() = File(context.filesDir, CASE_DIRECTORY).also { it.mkdirs() }

    /**
     * Initial explicit save. If the coordinator has a terminal lifecycle record
     * for the same normalized identity seed set, attach it to the new encrypted
     * case. Reverse-media results from the same working session are also attached
     * at this explicit save boundary; they are never persisted merely by viewing
     * or analyzing media.
     */
    fun save(case: DossierCase): Boolean = saveInternal(case, attachLatestScanHistory = true)

    private fun saveInternal(
        case: DossierCase,
        attachLatestScanHistory: Boolean
    ): Boolean = runCatching {
        val withSessionMedia = if (attachLatestScanHistory && case.mediaIntelligence.isEmpty) {
            val media = MediaIntelligenceSession.snapshot()
            if (!media.isEmpty) case.copy(mediaIntelligence = media) else case
        } else {
            case
        }
        val withHistory = if (attachLatestScanHistory && withSessionMedia.scanHistory.isEmpty()) {
            ScanHistoryRuntime.latestFor(withSessionMedia.input)?.let { entry ->
                withSessionMedia.copy(scanHistory = listOf(entry))
            } ?: withSessionMedia
        } else {
            withSessionMedia
        }
        val normalized = normalizeCase(withHistory)
        val plaintext = json.encodeToString(normalized).toByteArray(Charsets.UTF_8)
        val envelope = encrypt(plaintext)
        atomicWrite(encryptedFile(case.caseId), json.encodeToString(envelope))
        legacyFile(case.caseId).delete()
        true
    }.getOrDefault(false)

    fun load(caseId: String): DossierCase? {
        val encrypted = encryptedFile(caseId)
        if (encrypted.exists()) {
            return runCatching {
                val envelope = json.decodeFromString<EncryptedCaseEnvelope>(encrypted.readText())
                val plaintext = decrypt(envelope)
                normalizeCase(json.decodeFromString<DossierCase>(plaintext.toString(Charsets.UTF_8)))
            }.getOrNull()
        }

        val legacy = legacyFile(caseId)
        if (!legacy.exists()) return null
        val migrated = runCatching {
            json.decodeFromString<DossierCase>(legacy.readText())
        }.getOrNull() ?: return null
        val normalized = normalizeCase(migrated)
        return if (saveInternal(normalized, attachLatestScanHistory = false)) normalized else migrated
    }

    fun list(): List<DossierCase> = runCatching {
        val caseIds = dir.listFiles().orEmpty()
            .filter { it.extension == ENCRYPTED_EXTENSION || it.extension == LEGACY_EXTENSION }
            .map { it.nameWithoutExtension }
            .distinct()
        caseIds.mapNotNull(::load).sortedByDescending { it.createdAt }
    }.getOrElse { emptyList() }

    /** Persist a user decision without deleting the underlying raw evidence. */
    fun recordCorrection(caseId: String, correction: UserCorrection): Boolean = update(caseId) { current ->
        val normalizedCorrection = correction.copy(
            evidenceId = correction.evidenceId?.let(EvidenceIdPolicy::migrate)
        )
        val retained = current.userCorrections.filterNot { existing ->
            existing.correctionId == normalizedCorrection.correctionId ||
                (existing.evidenceId != null && existing.evidenceId == normalizedCorrection.evidenceId) ||
                (existing.entityId != null && existing.entityId == normalizedCorrection.entityId)
        }
        current.copy(userCorrections = retained + normalizedCorrection)
    }

    fun upsertRemediation(caseId: String, remediation: RemediationRecord): Boolean = update(caseId) { current ->
        val retained = current.remediationRecords.filterNot { it.remediationId == remediation.remediationId }
        current.copy(remediationRecords = retained + remediation)
    }

    fun appendScanHistory(caseId: String, entry: CaseScanHistoryEntry): Boolean = update(caseId) { current ->
        val retained = current.scanHistory.filterNot { it.scanId == entry.scanId }
        current.copy(scanHistory = (retained + entry).sortedBy { it.startedAtUtc })
    }

    fun recordExport(caseId: String, export: CaseExportRecord): Boolean = update(caseId) { current ->
        current.copy(exports = (current.exports + export).distinctBy(CaseExportRecord::exportId))
    }

    fun delete(caseId: String): Boolean = runCatching {
        val encryptedDeleted = !encryptedFile(caseId).exists() || encryptedFile(caseId).delete()
        val legacyDeleted = !legacyFile(caseId).exists() || legacyFile(caseId).delete()
        encryptedDeleted && legacyDeleted
    }.getOrDefault(false)

    fun clear(): Boolean = runCatching {
        dir.listFiles().orEmpty().forEach { file ->
            if (file.extension in setOf(ENCRYPTED_EXTENSION, LEGACY_EXTENSION, TEMP_EXTENSION)) file.delete()
        }
        true
    }.getOrDefault(false)

    private fun update(caseId: String, transform: (DossierCase) -> DossierCase): Boolean {
        val current = load(caseId) ?: return false
        return saveInternal(
            transform(current),
            attachLatestScanHistory = false
        )
    }

    /**
     * Migrates privacy-sensitive metadata without changing the underlying finding
     * values themselves. v3 correction/graph evidence IDs can contain raw values;
     * v4+ stores only deterministic `ev2:` hashes for those IDs. v5 additionally
     * materializes persisted reverse-media clusters into graph nodes/edges.
     */
    internal fun normalizeCase(case: DossierCase): DossierCase {
        val migratedCorrections = case.userCorrections.map { correction ->
            correction.copy(evidenceId = correction.evidenceId?.let(EvidenceIdPolicy::migrate))
        }
        val migratedEntities = case.entityGraph.entities.map { entity ->
            entity.copy(evidenceIds = entity.evidenceIds.map(EvidenceIdPolicy::migrate).distinct())
        }
        val migratedEdges = case.entityGraph.edges.map { edge ->
            edge.copy(
                evidenceIds = edge.evidenceIds.map(EvidenceIdPolicy::migrate).distinct(),
                contradictingEvidenceIds = edge.contradictingEvidenceIds
                    .map(EvidenceIdPolicy::migrate)
                    .distinct()
            )
        }
        val migratedGraph = case.entityGraph.copy(
            entities = migratedEntities,
            edges = migratedEdges
        )
        val mediaGraph = MediaGraphEnricher.enrich(migratedGraph, case.mediaIntelligence)
        return case.copy(
            schemaVersion = DossierCase.CURRENT_SCHEMA_VERSION,
            userCorrections = migratedCorrections,
            entityGraph = mediaGraph
        )
    }

    private fun encrypt(plaintext: ByteArray): EncryptedCaseEnvelope {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plaintext)
        return EncryptedCaseEnvelope(
            formatVersion = ENVELOPE_VERSION,
            caseSchemaVersion = DossierCase.CURRENT_SCHEMA_VERSION,
            createdAtUtc = Instant.now().toString(),
            ivBase64 = Base64.getEncoder().encodeToString(cipher.iv),
            ciphertextBase64 = Base64.getEncoder().encodeToString(ciphertext),
            plaintextSha256 = sha256(plaintext)
        )
    }

    private fun decrypt(envelope: EncryptedCaseEnvelope): ByteArray {
        require(envelope.formatVersion == ENVELOPE_VERSION) {
            "Unsupported encrypted case format ${envelope.formatVersion}."
        }
        val iv = Base64.getDecoder().decode(envelope.ivBase64)
        val ciphertext = Base64.getDecoder().decode(envelope.ciphertextBase64)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        val plaintext = cipher.doFinal(ciphertext)
        require(sha256(plaintext).equals(envelope.plaintextSha256, ignoreCase = true)) {
            "Case integrity check failed."
        }
        return plaintext
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val specification = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(specification)
        return generator.generateKey()
    }

    private fun atomicWrite(target: File, content: String) {
        val temporary = File(target.parentFile, "${target.name}.$TEMP_EXTENSION")
        FileOutputStream(temporary).use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (target.exists() && !target.delete()) error("Unable to replace existing case.")
        if (!temporary.renameTo(target)) {
            temporary.delete()
            error("Unable to commit encrypted case file.")
        }
    }

    private fun encryptedFile(caseId: String) = File(dir, "$caseId.$ENCRYPTED_EXTENSION")
    private fun legacyFile(caseId: String) = File(dir, "$caseId.$LEGACY_EXTENSION")

    @Serializable
    private data class EncryptedCaseEnvelope(
        val formatVersion: Int,
        val caseSchemaVersion: Int,
        val createdAtUtc: String,
        val ivBase64: String,
        val ciphertextBase64: String,
        val plaintextSha256: String
    )

    private companion object {
        const val CASE_DIRECTORY = "dossier_cases"
        const val ENCRYPTED_EXTENSION = "dcase"
        const val LEGACY_EXTENSION = "json"
        const val TEMP_EXTENSION = "tmp"
        const val ENVELOPE_VERSION = 1
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "dossier-case-storage-v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}
