package io.dossier.app.domain.case

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.dossier.app.domain.discovery.ScanHistoryRuntime
import io.dossier.app.domain.discovery.sanitizeTerminalFailureCode
import io.dossier.app.domain.evidence.EvidenceIdPolicy
import io.dossier.app.domain.evidence.EvidenceRelationshipPolicy
import io.dossier.app.domain.evidence.EvidenceRuntimeCache
import io.dossier.app.domain.graph.GraphEvidenceReconciliationReport
import io.dossier.app.domain.graph.graphEvidenceReconciliation
import io.dossier.app.domain.place.MediaIntelligenceSession
import io.dossier.app.domain.place.MediaIntelligenceSnapshotPolicy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.KeyStore
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Result of applying a validated AI summary to the exact case snapshot analyzed. */
sealed interface CaseAnalysisUpdateResult {
    data object Applied : CaseAnalysisUpdateResult
    data object MissingCase : CaseAnalysisUpdateResult
    data object Conflict : CaseAnalysisUpdateResult
    data object StorageFailure : CaseAnalysisUpdateResult
}

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
     * Initial explicit save attaches the current bounded working-session evidence,
     * media intelligence, and matching terminal scan history. None of these are
     * promoted into persistent Case storage merely by running a scan or lookup.
     */
    fun save(case: DossierCase): Boolean = synchronized(CASE_MUTATION_LOCK) {
        saveInternal(case, attachSessionState = true)
    }

    /**
     * Persist an already-loaded case exactly as supplied. This path never reads
     * process-global evidence, media, or scan-history runtime caches, so a
     * loaded case cannot be grafted with another active subject's session state.
     */
    fun saveExactCase(case: DossierCase): Boolean = synchronized(CASE_MUTATION_LOCK) {
        saveInternal(case, attachSessionState = false)
    }

    /**
     * Persists an already-built case away from the caller's dispatcher. Case
     * serialization, Keystore access, and the fsynced atomic replacement are
     * all blocking operations and must not run from a Compose/main callback.
     */
    suspend fun saveAsync(
        case: DossierCase,
        dispatcher: CoroutineDispatcher = Dispatchers.IO
    ): Boolean = withContext(dispatcher) {
        save(case)
    }

    /** Persists a loaded case exactly as supplied on an IO dispatcher. */
    suspend fun saveExactCaseAsync(
        case: DossierCase,
        dispatcher: CoroutineDispatcher = Dispatchers.IO
    ): Boolean = withContext(dispatcher) {
        saveExactCase(case)
    }

    /**
     * Applies only the validated summary produced from [expectedCase]. The current case is
     * loaded and compared while the same process-wide mutation lock is held, so a correction,
     * remediation, scan-history update, or other persisted change cannot be overwritten by a
     * delayed analysis response.
     */
    fun saveAnalysisIfUnchanged(
        expectedCase: DossierCase,
        validatedSummary: String
    ): CaseAnalysisUpdateResult = synchronized(CASE_MUTATION_LOCK) {
        val summary = validatedSummary.trim()
        if (summary.isBlank()) return@synchronized CaseAnalysisUpdateResult.StorageFailure

        val current = loadUnlocked(expectedCase.caseId)
            ?: return@synchronized CaseAnalysisUpdateResult.MissingCase
        if (analysisFingerprint(current) != analysisFingerprint(expectedCase)) {
            // A concurrent correction/remediation already marks the summary stale. If the
            // intervening update did not, persist only the refresh marker and never replace
            // any current fields with the delayed snapshot.
            val marked = current.aiSummaryNeedsRefresh || saveInternal(
                current.copy(aiSummaryNeedsRefresh = true),
                attachSessionState = false
            )
            return@synchronized if (marked) {
                CaseAnalysisUpdateResult.Conflict
            } else {
                CaseAnalysisUpdateResult.StorageFailure
            }
        }

        if (saveInternal(
                current.copy(
                    aiSummary = summary,
                    aiSummaryNeedsRefresh = false
                ),
                attachSessionState = false
            )
        ) {
            CaseAnalysisUpdateResult.Applied
        } else {
            CaseAnalysisUpdateResult.StorageFailure
        }
    }

    /** Applies validated analysis without doing encrypted read/modify/write work on the caller. */
    suspend fun saveAnalysisIfUnchangedAsync(
        expectedCase: DossierCase,
        validatedSummary: String,
        dispatcher: CoroutineDispatcher = Dispatchers.IO
    ): CaseAnalysisUpdateResult = withContext(dispatcher) {
        saveAnalysisIfUnchanged(expectedCase, validatedSummary)
    }

    private fun saveInternal(
        case: DossierCase,
        attachSessionState: Boolean
    ): Boolean = runCatching {
        val withEvidence = if (attachSessionState && case.evidenceRecords.isEmpty()) {
            val runtimeCollection = EvidenceRuntimeCache.collection.value
            val evidence = runtimeCollection.evidence
                .distinctBy { it.id }
                .take(MAX_EVIDENCE_RECORDS)
            // Keep any explicit assertions already present on the case while attaching
            // the current bounded working-session collection. This avoids dropping a
            // relationship-only case when its runtime evidence cache is empty.
            val relationships = EvidenceRelationshipPolicy.normalize(
                case.evidenceRelationships + runtimeCollection.relationships
            )
            if (evidence.isNotEmpty() || relationships.isNotEmpty()) {
                case.copy(
                    evidenceRecords = evidence,
                    evidenceRelationships = relationships
                )
            } else {
                case
            }
        } else {
            case
        }
        val withSessionMedia = if (attachSessionState && withEvidence.mediaIntelligence.isEmpty) {
            val media = MediaIntelligenceSession.snapshotFor(withEvidence.input)
            if (!media.isEmpty) withEvidence.copy(mediaIntelligence = media) else withEvidence
        } else {
            withEvidence
        }
        val withHistory = if (attachSessionState && withSessionMedia.scanHistory.isEmpty()) {
            ScanHistoryRuntime.latestFor(withSessionMedia.input)?.let { entry ->
                withSessionMedia.copy(scanHistory = listOf(entry))
            } ?: withSessionMedia
        } else {
            withSessionMedia
        }
        val normalized = normalizeCase(withHistory)
        val plaintext = json.encodeToString(normalized).toByteArray(Charsets.UTF_8)
        require(plaintext.size <= MAX_PLAINTEXT_BYTES) {
            "Encrypted case plaintext exceeds the ${MAX_PLAINTEXT_BYTES}-byte limit."
        }
        val envelope = encrypt(plaintext)
        val encodedEnvelope = json.encodeToString(envelope)
        require(encodedEnvelope.toByteArray(Charsets.UTF_8).size <= MAX_ENVELOPE_BYTES) {
            "Encrypted case envelope exceeds the ${MAX_ENVELOPE_BYTES}-byte limit."
        }
        atomicWrite(encryptedFile(case.caseId), encodedEnvelope)
        val legacy = legacyFile(case.caseId)
        if (legacy.exists() && !legacy.delete()) {
            error("Unable to remove legacy plaintext case.")
        }
        true
    }.getOrDefault(false)

    fun load(caseId: String): DossierCase? = synchronized(CASE_MUTATION_LOCK) {
        if (!CaseStoreStoragePolicy.isSafeCaseId(caseId)) return@synchronized null
        loadUnlocked(caseId)
    }

    /**
     * Returns read-only diagnostics for divergence between persisted canonical
     * evidence relationships and graph edges. Neither source is rewritten.
     */
    fun graphEvidenceDiagnostics(caseId: String): GraphEvidenceReconciliationReport? =
        synchronized(CASE_MUTATION_LOCK) {
            if (!CaseStoreStoragePolicy.isSafeCaseId(caseId)) return@synchronized null
            loadUnlocked(caseId)?.graphEvidenceReconciliation()
        }

    /** Loads one encrypted case without doing disk/Keystore work on the caller's dispatcher. */
    suspend fun loadAsync(
        caseId: String,
        dispatcher: CoroutineDispatcher = Dispatchers.IO
    ): DossierCase? = withContext(dispatcher) {
        load(caseId)
    }

    private fun loadUnlocked(caseId: String): DossierCase? {
        if (!CaseStoreStoragePolicy.isSafeCaseId(caseId)) return null
        val encrypted = encryptedFile(caseId)
        if (encrypted.exists()) {
            readEncryptedCase(encrypted)?.let { return it }
        }

        // A process crash can occur after the old target is moved to its backup but before the
        // replacement is committed. Recover the last known-good encrypted case before falling
        // back to legacy plaintext migration.
        val backup = backupFile(caseId)
        if (backup.exists()) {
            readEncryptedCase(backup)?.let { recovered ->
                runCatching {
                    Files.move(backup.toPath(), encrypted.toPath(), REPLACE_EXISTING)
                    syncDirectory(encrypted.parentFile)
                }
                return recovered
            }
        }

        val legacy = legacyFile(caseId)
        if (!legacy.exists()) return null
        val migrated = runCatching {
            json.decodeFromString<DossierCase>(
                readFileBounded(legacy, MAX_PLAINTEXT_BYTES.toLong()).toString(Charsets.UTF_8)
            )
        }.getOrNull() ?: return null
        val normalized = normalizeCase(migrated)
        return if (saveInternal(normalized, attachSessionState = false)) normalized else migrated
    }

    fun list(): List<DossierCase> = synchronized(CASE_MUTATION_LOCK) {
        runCatching {
            val caseIds = dir.listFiles().orEmpty()
                .mapNotNull(::caseIdFromStorageFile)
                .distinct()
            caseIds.mapNotNull(::loadUnlocked).sortedByDescending { it.createdAt }
        }.getOrElse { emptyList() }
    }

    /**
     * Returns saved cases with corrections applied to presentation/scoring
     * fields. The encrypted records returned by [load] remain the raw audit
     * cases; this view keeps their evidence records intact and is safe for UI
     * rendering and export.
     */
    fun listEffective(): List<DossierCase> = list()
        .map { EffectiveCaseProjection.from(it).presentationCase() }

    /** Lists the corrected presentation view without blocking the caller's dispatcher. */
    suspend fun listEffectiveAsync(
        dispatcher: CoroutineDispatcher = Dispatchers.IO
    ): List<DossierCase> = withContext(dispatcher) {
        listEffective()
    }

    /** Returns one corrected presentation case without mutating persisted data. */
    fun loadEffective(caseId: String): DossierCase? = load(caseId)
        ?.let { EffectiveCaseProjection.from(it).presentationCase() }

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
        current.copy(
            userCorrections = retained + normalizedCorrection,
            aiSummary = null,
            aiSummaryNeedsRefresh = true
        )
    }

    /** Records a correction without running encrypted read/modify/write work on the caller. */
    suspend fun recordCorrectionAsync(
        caseId: String,
        correction: UserCorrection,
        dispatcher: CoroutineDispatcher = Dispatchers.IO
    ): Boolean = withContext(dispatcher) {
        recordCorrection(caseId, correction)
    }

    fun upsertRemediation(caseId: String, remediation: RemediationRecord): Boolean = update(caseId) { current ->
        val retained = current.remediationRecords.filterNot { it.remediationId == remediation.remediationId }
        current.copy(
            remediationRecords = retained + remediation,
            aiSummary = null,
            aiSummaryNeedsRefresh = true
        )
    }

    /** Records remediation state without blocking the caller's dispatcher. */
    suspend fun upsertRemediationAsync(
        caseId: String,
        remediation: RemediationRecord,
        dispatcher: CoroutineDispatcher = Dispatchers.IO
    ): Boolean = withContext(dispatcher) {
        upsertRemediation(caseId, remediation)
    }

    fun appendScanHistory(caseId: String, entry: CaseScanHistoryEntry): Boolean = update(caseId) { current ->
        val retained = current.scanHistory.filterNot { it.scanId == entry.scanId }
        current.copy(scanHistory = (retained + entry).sortedBy { it.startedAtUtc })
    }

    fun recordExport(caseId: String, export: CaseExportRecord): Boolean = update(caseId) { current ->
        current.copy(exports = (current.exports + export).distinctBy(CaseExportRecord::exportId))
    }

    fun delete(caseId: String): Boolean = synchronized(CASE_MUTATION_LOCK) {
        if (!CaseStoreStoragePolicy.isSafeCaseId(caseId)) return@synchronized false
        runCatching {
            val plan = CaseStoreStoragePolicy.scopedFiles(
                root = dir,
                caseId = caseId,
                encryptedExtension = ENCRYPTED_EXTENSION,
                legacyExtension = LEGACY_EXTENSION,
                backupExtension = BACKUP_EXTENSION,
                tempExtension = TEMP_EXTENSION
            ) ?: return@runCatching false
            val deleted = CaseStoreStoragePolicy.deleteAll(
                files = plan.files,
                deleteFile = { file -> file.delete() },
                syncDirectory = { syncDirectory(dir) }
            )
            deleted && plan.unsafeFileCount == 0
        }.getOrDefault(false)
    }

    /** Deletes one encrypted case without blocking the caller's dispatcher. */
    suspend fun deleteAsync(
        caseId: String,
        dispatcher: CoroutineDispatcher = Dispatchers.IO
    ): Boolean = withContext(dispatcher) {
        delete(caseId)
    }

    fun clear(): Boolean = synchronized(CASE_MUTATION_LOCK) {
        runCatching {
            val plan = CaseStoreStoragePolicy.clearPlan(
                root = dir,
                extensions = setOf(
                    ENCRYPTED_EXTENSION,
                    LEGACY_EXTENSION,
                    TEMP_EXTENSION,
                    BACKUP_EXTENSION
                )
            ) ?: return@runCatching false
            val deleted = CaseStoreStoragePolicy.deleteAll(
                files = plan.files,
                deleteFile = { file -> file.delete() },
                syncDirectory = { syncDirectory(dir) }
            )
            deleted && plan.unsafeFileCount == 0
        }.getOrDefault(false)
    }

    private fun update(caseId: String, transform: (DossierCase) -> DossierCase): Boolean {
        return synchronized(CASE_MUTATION_LOCK) {
            val current = loadUnlocked(caseId) ?: return@synchronized false
            saveInternal(transform(current), attachSessionState = false)
        }
    }

    /**
     * Normalizes legacy privacy-sensitive identifiers and projects persisted
     * reverse-media evidence into the semantic graph. Missing v5/v6 fields decode
     * to empty defaults, so old encrypted/plaintext cases remain migratable.
     */
    internal fun normalizeCase(case: DossierCase): DossierCase {
        val migrated = CaseEvidenceIdMigration.migrate(case)
        val normalizedMedia = MediaIntelligenceSnapshotPolicy.normalize(migrated.mediaIntelligence)
        val mediaGraph = MediaGraphEnricher.enrich(migrated.entityGraph, normalizedMedia)
        return migrated.copy(
            entityGraph = mediaGraph,
            mediaIntelligence = normalizedMedia,
            scanHistory = migrated.scanHistory.map(::normalizePersistedScanHistoryEntry)
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
        require(envelope.ivBase64.length in 1..MAX_IV_BASE64_CHARS)
        require(envelope.ciphertextBase64.length in 1..MAX_CIPHERTEXT_BASE64_CHARS)
        require(envelope.plaintextSha256.length == SHA_256_HEX_LENGTH)
        val iv = Base64.getDecoder().decode(envelope.ivBase64)
        require(iv.size == GCM_IV_BYTES)
        val ciphertext = Base64.getDecoder().decode(envelope.ciphertextBase64)
        require(ciphertext.size in (GCM_TAG_BYTES + 1)..MAX_CIPHERTEXT_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        val plaintext = cipher.doFinal(ciphertext)
        require(plaintext.size <= MAX_PLAINTEXT_BYTES)
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
        val parent = requireNotNull(target.parentFile) { "Encrypted case must have a parent directory." }
        if (!parent.exists() && !parent.mkdirs()) error("Unable to create encrypted case directory.")
        val temporary = File.createTempFile("${target.name}.", ".$TEMP_EXTENSION", parent)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    ATOMIC_MOVE,
                    REPLACE_EXISTING
                )
            } catch (atomicFailure: IOException) {
                replaceWithBackup(temporary, target, atomicFailure)
            }
            syncDirectory(parent)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun replaceWithBackup(temporary: File, target: File, atomicFailure: IOException) {
        val backup = File(target.parentFile, "${target.name}.$BACKUP_EXTENSION")
        var movedOriginal = false
        try {
            if (target.exists()) {
                if (backup.exists() && !backup.delete()) {
                    error("Unable to prepare encrypted case backup.")
                }
                Files.move(target.toPath(), backup.toPath(), REPLACE_EXISTING)
                movedOriginal = true
            }
            try {
                Files.move(temporary.toPath(), target.toPath(), REPLACE_EXISTING)
            } catch (replacementFailure: IOException) {
                if (movedOriginal) restoreBackup(target, backup, replacementFailure)
                throw replacementFailure
            }
            if (movedOriginal) backup.delete()
        } catch (failure: Exception) {
            if (failure !== atomicFailure) atomicFailure.addSuppressed(failure)
            throw atomicFailure
        }
    }

    private fun restoreBackup(target: File, backup: File, replacementFailure: IOException) {
        try {
            if (target.exists() && !target.delete()) {
                error("Unable to discard incomplete encrypted case replacement.")
            }
            Files.move(backup.toPath(), target.toPath(), REPLACE_EXISTING)
        } catch (restoreFailure: Exception) {
            replacementFailure.addSuppressed(restoreFailure)
        }
    }

    private fun readEncryptedCase(file: File): DossierCase? = runCatching {
        val envelope = json.decodeFromString<EncryptedCaseEnvelope>(
            readFileBounded(file, MAX_ENVELOPE_BYTES).toString(Charsets.UTF_8)
        )
        val plaintext = decrypt(envelope)
        normalizeCase(json.decodeFromString<DossierCase>(plaintext.toString(Charsets.UTF_8)))
    }.getOrNull()

    private fun readFileBounded(file: File, maxBytes: Long): ByteArray {
        require(maxBytes > 0)
        if (!file.exists() || !file.isFile) throw IOException("Missing or invalid case file")
        val length = file.length()
        if (length > maxBytes) {
            throw IOException("Case file size $length exceeds limit of $maxBytes bytes")
        }
        val initialCapacity = if (length > 0) {
            minOf(length + 1L, maxBytes + 1L).toInt()
        } else {
            1024
        }
        val output = ByteArrayOutputStream(initialCapacity)
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) {
                    throw IOException("Case file read exceeded limit of $maxBytes bytes")
                }
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }

    private fun analysisFingerprint(case: DossierCase): String {
        val analysisSnapshot = case.copy(
            aiSummary = null,
            aiSummaryNeedsRefresh = false,
            exports = emptyList()
        )
        return sha256(json.encodeToString(analysisSnapshot).toByteArray(Charsets.UTF_8))
    }

    private fun syncDirectory(directory: File?): Boolean {
        if (directory == null) return true
        return runCatching {
            val fd = android.system.Os.open(
                directory.absolutePath,
                android.system.OsConstants.O_RDONLY,
                0
            )
            try {
                android.system.Os.fsync(fd)
            } finally {
                android.system.Os.close(fd)
            }
            true
        }.getOrDefault(false)
    }

    private fun encryptedFile(caseId: String) = requireNotNull(
        CaseStoreStoragePolicy.confinedPath(dir, caseId, ENCRYPTED_EXTENSION)
    )
    private fun legacyFile(caseId: String) = requireNotNull(
        CaseStoreStoragePolicy.confinedPath(dir, caseId, LEGACY_EXTENSION)
    )

    private fun backupFile(caseId: String) = requireNotNull(
        CaseStoreStoragePolicy.confinedPath(
            dir,
            caseId,
            "$ENCRYPTED_EXTENSION.$BACKUP_EXTENSION"
        )
    )

    private fun caseIdFromStorageFile(file: File): String? = when (file.extension) {
        ENCRYPTED_EXTENSION,
        LEGACY_EXTENSION -> file.name.removeSuffix(".${file.extension}")
        BACKUP_EXTENSION -> file.name.removeSuffix(".$ENCRYPTED_EXTENSION.$BACKUP_EXTENSION")
        else -> null
    }?.takeIf(CaseStoreStoragePolicy::isSafeCaseId)

    @Serializable
    private data class EncryptedCaseEnvelope(
        val formatVersion: Int,
        val caseSchemaVersion: Int,
        val createdAtUtc: String,
        val ivBase64: String,
        val ciphertextBase64: String,
        val plaintextSha256: String
    )

    internal companion object {
        const val CASE_DIRECTORY = "dossier_cases"
        const val ENCRYPTED_EXTENSION = "dcase"
        const val LEGACY_EXTENSION = "json"
        const val TEMP_EXTENSION = "tmp"
        const val BACKUP_EXTENSION = "bak"
        const val ENVELOPE_VERSION = 1
        const val MAX_EVIDENCE_RECORDS = 10_000
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BYTES = 16
        const val MAX_PLAINTEXT_BYTES = 8 * 1024 * 1024
        const val MAX_CIPHERTEXT_BYTES = MAX_PLAINTEXT_BYTES + GCM_TAG_BYTES
        const val MAX_ENVELOPE_BYTES = 12 * 1024 * 1024L
        const val MAX_IV_BASE64_CHARS = 32
        const val MAX_CIPHERTEXT_BASE64_CHARS = ((MAX_CIPHERTEXT_BYTES + 2) / 3) * 4 + 16
        const val SHA_256_HEX_LENGTH = 64
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "dossier-case-storage-v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        val CASE_MUTATION_LOCK = Any()

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}

/**
 * File-scope and deletion policy for encrypted case storage.
 *
 * Case identifiers are normally generated UUIDs, but persisted/legacy data is
 * still an input boundary. Keep every path confined to the canonical case
 * directory and reject malformed identifiers before constructing a File.
 */
internal object CaseStoreStoragePolicy {
    const val MAX_CASE_ID_LENGTH = 128

    data class ClearPlan(
        val files: List<File>,
        val unsafeFileCount: Int
    )

    data class ScopedFilePlan(
        val files: List<File>,
        val unsafeFileCount: Int
    )

    fun isSafeCaseId(value: String): Boolean {
        if (value.length !in 1..MAX_CASE_ID_LENGTH) return false
        if (value == "." || value == "..") return false
        return value.all { character ->
            character in 'a'..'z' ||
                character in 'A'..'Z' ||
                character in '0'..'9' ||
                character == '.' ||
                character == '_' ||
                character == '-'
        }
    }

    fun confinedPath(root: File, caseId: String, suffix: String): File? {
        if (!isSafeCaseId(caseId) || suffix.isBlank() || suffix.contains('/') || suffix.contains('\\')) {
            return null
        }
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return null
        val target = runCatching {
            File(canonicalRoot, "$caseId.$suffix").canonicalFile
        }.getOrNull() ?: return null
        return target.takeIf { it.parentFile == canonicalRoot }
    }

    fun scopedFiles(
        root: File,
        caseId: String,
        encryptedExtension: String,
        legacyExtension: String,
        backupExtension: String,
        tempExtension: String
    ): ScopedFilePlan? {
        val encrypted = confinedPath(root, caseId, encryptedExtension) ?: return null
        val legacy = confinedPath(root, caseId, legacyExtension) ?: return null
        val backup = confinedPath(
            root,
            caseId,
            "$encryptedExtension.$backupExtension"
        ) ?: return null
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return null
        val entries = runCatching { canonicalRoot.listFiles() }.getOrNull()
            ?: return if (!canonicalRoot.exists()) {
                ScopedFilePlan(listOf(encrypted, backup, legacy), unsafeFileCount = 0)
            } else {
                null
            }
        val tempPrefix = "${encrypted.name}."
        val matchingTemporary = entries.filter { entry ->
            entry.name.startsWith(tempPrefix) && entry.name.endsWith(".$tempExtension")
        }
        val temporary = matchingTemporary.filter { entry ->
            runCatching { entry.canonicalFile.parentFile == canonicalRoot }
                .getOrDefault(false)
        }
        return ScopedFilePlan(
            files = listOf(encrypted, backup, legacy) + temporary,
            unsafeFileCount = matchingTemporary.size - temporary.size
        )
    }

    fun clearPlan(root: File, extensions: Set<String>): ClearPlan? {
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return null
        val entries = runCatching { canonicalRoot.listFiles() }.getOrNull()
            ?: return if (!canonicalRoot.exists()) ClearPlan(emptyList(), 0) else null
        val matching = entries.filter { it.extension in extensions }
        val safe = matching.filter { entry ->
            runCatching { entry.canonicalFile.parentFile == canonicalRoot }
                .getOrDefault(false)
        }
        return ClearPlan(
            files = safe,
            unsafeFileCount = matching.size - safe.size
        )
    }

    /** Attempts every file and reports any failed deletion or directory sync. */
    fun deleteAll(
        files: Collection<File>,
        deleteFile: (File) -> Boolean,
        syncDirectory: () -> Boolean
    ): Boolean {
        var success = true
        var changed = false
        files.distinctBy { file ->
            runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
        }.forEach { file ->
            if (!file.exists()) return@forEach
            changed = true
            if (!runCatching { deleteFile(file) }.getOrDefault(false)) {
                success = false
            }
        }
        if (changed && !runCatching { syncDirectory() }.getOrDefault(false)) {
            success = false
        }
        return success
    }
}

internal fun normalizePersistedScanHistoryEntry(
    entry: CaseScanHistoryEntry
): CaseScanHistoryEntry {
    val failed = entry.failed
    return entry.copy(
        directProfileProviderCount = entry.directProfileProviderCount.coerceAtLeast(0),
        profileResultCount = entry.profileResultCount.coerceAtLeast(0),
        findingCount = entry.findingCount.coerceAtLeast(0),
        breachRecordCount = entry.breachRecordCount.coerceAtLeast(0),
        graphEntityCount = entry.graphEntityCount.coerceAtLeast(0),
        graphRelationshipCount = entry.graphRelationshipCount.coerceAtLeast(0),
        cancelled = entry.cancelled && !failed,
        failureCode = if (failed) {
            sanitizeTerminalFailureCode(entry.failureCode) ?: "SCAN_FAILED"
        } else {
            null
        }
    )
}
