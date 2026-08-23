package io.dossier.app.domain.scanner

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.dossier.app.domain.discovery.DiscoveryScanPreferences
import io.dossier.app.domain.discovery.ScanMode
import io.dossier.app.domain.model.IdentityInput
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import java.util.Locale
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class ResumeCryptoFailure(
    val reason: ResumeStorageReason,
    cause: Throwable? = null
) : Exception(cause)

internal class BoundedReadException : Exception()

internal interface DirectorySyncer {
    fun sync(dir: File)
}

internal class AndroidDirectorySyncer : DirectorySyncer {
    override fun sync(dir: File) {
        val fd = android.system.Os.open(
            dir.absolutePath,
            android.system.OsConstants.O_RDONLY,
            0
        )
        try {
            android.system.Os.fsync(fd)
        } finally {
            android.system.Os.close(fd)
        }
    }

}

/**
 * Encrypted local resume state for an interrupted scan.
 *
 * The old [dossier_resume.json] marker is read only by the bounded migration
 * path below. New records contain the complete [IdentityInput] and scan
 * options inside AES-GCM ciphertext. The only unencrypted state is an opaque
 * UUID pointer and UUID-named encrypted record files.
 */
internal class ScanResumeStore internal constructor(
    private val recordsDir: File,
    private val legacyDir: File,
    private val crypto: ResumeCrypto,
    private val nowMillis: () -> Long,
    private val idFactory: () -> String,
    private val dirSyncer: DirectorySyncer
) {

    /** Production constructor. It never uses the JVM test crypto. */
    constructor(context: Context) : this(
        File(context.applicationContext.filesDir, RECORDS_DIRECTORY),
        File(context.applicationContext.filesDir, LEGACY_DIRECTORY),
        AndroidKeystoreResumeCrypto(),
        { System.currentTimeMillis() },
        { UUID.randomUUID().toString() },
        AndroidDirectorySyncer()
    )

    /** JVM seam: callers must provide crypto explicitly; no insecure default exists. */
    internal constructor(
        recordsDir: File,
        crypto: ResumeCrypto,
        nowMillis: () -> Long = { System.currentTimeMillis() },
        idFactory: () -> String = { UUID.randomUUID().toString() },
        legacyDir: File = recordsDir,
        dirSyncer: DirectorySyncer = object : DirectorySyncer { override fun sync(dir: File) {} }
    ) : this(recordsDir, legacyDir, crypto, nowMillis, idFactory, dirSyncer)

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
        coerceInputValues = false
    }

    /** Source-compatible facade used by ScanSession. */
    fun save(input: IdentityInput, deepResearch: Boolean): Boolean =
        saveDetailed(input, deepResearch, false) is ResumeWriteState.Saved

    /**
     * Source-compatible facade used by IdentityScreen/ScanScreen. It retains
     * the historical mode side effect only after a valid encrypted load.
     */
    fun load(): Pair<IdentityInput, Boolean>? = when (val state = loadDetailed()) {
        is ResumeReadState.Available -> {
            DiscoveryScanPreferences.setMode(state.point.scanMode)
            state.point.input to state.point.deepResearch
        }
        else -> null
    }

    /** Source-compatible facade used by ScanSession. */
    fun clear(): Boolean = clearDetailed() is ResumeWriteState.Cleared

    internal fun saveRequestDetailed(
        input: IdentityInput,
        deepResearch: Boolean,
        strongFaceCorrelation: Boolean
    ): ResumeWriteState = saveDetailed(input, deepResearch, strongFaceCorrelation)

    /**
     * Writes an encrypted request record without making it the active request.
     *
     * This is the first half of the checkpoint A -> B hand-off.  The active
     * pointer and any record it names are deliberately left untouched until
     * [promotePreparedRequest] succeeds.
     */
    internal fun prepareRequestDetailed(
        input: IdentityInput,
        deepResearch: Boolean,
        strongFaceCorrelation: Boolean
    ): ResumeWriteState = synchronized(STORE_LOCK) {
        return try {
            val inputIssue = validateInput(input)
            if (inputIssue != null) return ResumeWriteState.Invalid(inputIssue)
            if (hasClearAllGuard()) {
                return ResumeWriteState.StorageFailure(ResumeStorageReason.IoFailure)
            }

            val id = idFactory()
            if (!isValidRequestId(id)) {
                return ResumeWriteState.Invalid(ResumeInvalidReason.InvalidRequestId)
            }

            val now = nowMillis()
            val expiresAt = expiresAt(now)
            val point = ResumePoint(
                requestId = id,
                input = input,
                deepResearch = deepResearch,
                scanMode = DiscoveryScanPreferences.selectedMode.value,
                strongFaceCorrelation = strongFaceCorrelation,
                createdAtEpochMillis = now,
                expiresAtEpochMillis = expiresAt
            )
            val record = ResumeRecord(
                formatVersion = FORMAT_VERSION,
                requestId = point.requestId,
                createdAtEpochMillis = point.createdAtEpochMillis,
                updatedAtEpochMillis = now,
                expiresAtEpochMillis = expiresAt,
                input = point.input,
                deepResearch = point.deepResearch,
                scanMode = point.scanMode,
                strongFaceCorrelation = point.strongFaceCorrelation
            )

            // Capture this before publishing the prepared marker. The marker
            // itself is durable state, so consulting canCreateKey() after it
            // is written would incorrectly make a first fresh save look like
            // an existing-generation recovery and deny keystore key creation.
            discardStaleMarkerOnlyState()
            val allowKeyCreation = canCreateKey()

            // The opaque marker is committed before B. A crash can therefore
            // leave a harmless marker-only entry, but never an unmarked
            // prepared record that generic orphan recovery could activate.
            persistPreparedMarker(record.requestId)
            persistPreparedRecord(record, allowKeyCreation)
            try {
                // Once a new encrypted request exists, retaining the legacy
                // plaintext marker is never necessary for recovery.
                deleteLegacyOrThrow()
            } catch (error: Exception) {
                // If unlink may already have committed and only its directory
                // fsync failed, deleting B as well would destroy both recovery
                // copies. Clean B only while the legacy marker is still
                // observably present; otherwise retain encrypted B for retry.
                if (legacyFile.exists()) {
                    val cleanupReason = cleanupPreparedRecord(record.requestId)
                    if (cleanupReason != null) {
                        throw ResumeCryptoFailure(cleanupReason, error)
                    }
                }
                when (error) {
                    is ResumeCryptoFailure -> throw error
                    else -> throw ResumeCryptoFailure(ResumeStorageReason.LegacyDeletionFailure, error)
                }
            }
            // Keep the existing result type so callers can consume the
            // request id without a second API-specific wrapper.
            ResumeWriteState.Saved(point)
        } catch (error: ResumeCryptoFailure) {
            ResumeWriteState.StorageFailure(error.reason)
        } catch (error: ResumeInvalidOperation) {
            ResumeWriteState.Invalid(error.reason)
        } catch (_: SerializationException) {
            ResumeWriteState.StorageFailure(ResumeStorageReason.SerializationFailure)
        } catch (_: GeneralSecurityException) {
            ResumeWriteState.StorageFailure(ResumeStorageReason.KeyFailure)
        } catch (_: Exception) {
            ResumeWriteState.StorageFailure(ResumeStorageReason.IoFailure)
        }
    }

    /**
     * Loads exactly one encrypted record without consulting the active
     * pointer.  An invalid/expired prepared record may be removed, but the
     * cleanup path never removes a different active generation.
     */
    internal fun loadPreparedRequestDetailed(requestId: String): ResumeReadState = synchronized(STORE_LOCK) {
        if (!isValidRequestId(requestId)) {
            return@synchronized ResumeReadState.Invalid(ResumeInvalidReason.InvalidRequestId)
        }
        if (hasClearAllGuard() || hasClearTombstone(requestId)) {
            return@synchronized ResumeReadState.StorageFailure(ResumeStorageReason.IoFailure)
        }
        return@synchronized try {
            when (val state = loadRecord(requestId)) {
                ResumeReadState.Expired,
                is ResumeReadState.Invalid -> {
                    val cleanupFailure = cleanupPreparedRecord(requestId)
                    cleanupFailure?.let(ResumeReadState::StorageFailure) ?: state
                }
                else -> state
            }
        } catch (error: ResumeCryptoFailure) {
            ResumeReadState.StorageFailure(error.reason)
        } catch (_: Exception) {
            ResumeReadState.StorageFailure(ResumeStorageReason.IoFailure)
        }
    }

    /**
     * Promotes a verified prepared record to the active pointer.  The
     * boolean facade is intentionally conservative: true is returned only
     * after the pointer switch and previous-record deletion both complete.
     */
    internal fun promotePreparedRequest(requestId: String): Boolean =
        promotePreparedRequestDetailed(requestId) is ResumeReadState.Available

    /** Typed promotion result for callers that need the failure reason. */
    internal fun promotePreparedRequestDetailed(requestId: String): ResumeReadState = synchronized(STORE_LOCK) {
        if (!isValidRequestId(requestId)) {
            return@synchronized ResumeReadState.Invalid(ResumeInvalidReason.InvalidRequestId)
        }
        if (hasClearAllGuard() || hasClearTombstone(requestId)) {
            return@synchronized ResumeReadState.StorageFailure(ResumeStorageReason.IoFailure)
        }

        val prepared = try {
            loadRecord(requestId)
        } catch (error: ResumeCryptoFailure) {
            return@synchronized ResumeReadState.StorageFailure(error.reason)
        } catch (_: Exception) {
            return@synchronized ResumeReadState.StorageFailure(ResumeStorageReason.IoFailure)
        }
        when (prepared) {
            is ResumeReadState.Available -> Unit
            ResumeReadState.Expired,
            is ResumeReadState.Invalid -> {
                val cleanupFailure = cleanupPreparedRecord(requestId)
                return@synchronized cleanupFailure?.let(ResumeReadState::StorageFailure) ?: prepared
            }
            ResumeReadState.Missing,
            is ResumeReadState.StorageFailure -> return@synchronized prepared
        }

        val priorPointer = try {
            readPointer()
        } catch (error: ResumeCryptoFailure) {
            return@synchronized ResumeReadState.StorageFailure(error.reason)
        } catch (_: Exception) {
            return@synchronized ResumeReadState.StorageFailure(ResumeStorageReason.PointerFailure)
        }
        if (priorPointer != null && !isValidRequestId(priorPointer)) {
            return@synchronized ResumeReadState.StorageFailure(ResumeStorageReason.PointerFailure)
        }
        if (priorPointer != null &&
            priorPointer != requestId &&
            hasClearTombstone(priorPointer)
        ) {
            // A pointer naming a generation already under explicit clear is
            // not a recoverable prior generation. Do not switch to B while
            // that pair's state is ambiguous.
            return@synchronized ResumeReadState.StorageFailure(ResumeStorageReason.IoFailure)
        }
        if (priorPointer != requestId && !hasValidPreparedMarker(requestId)) {
            return@synchronized ResumeReadState.StorageFailure(ResumeStorageReason.IoFailure)
        }
        val hasUnexpectedRecord = try {
            hasUnexpectedRecoverableRecord(requestId, priorPointer)
        } catch (error: ResumeCryptoFailure) {
            return@synchronized ResumeReadState.StorageFailure(error.reason)
        } catch (_: Exception) {
            return@synchronized ResumeReadState.StorageFailure(ResumeStorageReason.IoFailure)
        }
        if (hasUnexpectedRecord) {
            return@synchronized ResumeReadState.StorageFailure(ResumeStorageReason.IoFailure)
        }
        // A valid prepared record that is already current is safe to treat as
        // an idempotent promotion; importantly, no record is deleted.
        if (priorPointer == requestId) {
            return@synchronized try {
                deleteLegacyOrThrow()
                deletePreparedMarker(requestId)
                prepared
            } catch (error: ResumeCryptoFailure) {
                ResumeReadState.StorageFailure(error.reason)
            } catch (_: Exception) {
                ResumeReadState.StorageFailure(ResumeStorageReason.LegacyDeletionFailure)
            }
        }

        return@synchronized try {
            val priorPointerBytes = try {
                if (pointerFile.exists()) pointerFile.readBounded(MAX_POINTER_BYTES) else null
            } catch (_: BoundedReadException) {
                return@synchronized ResumeReadState.StorageFailure(ResumeStorageReason.PointerFailure)
            } catch (_: Exception) {
                return@synchronized ResumeReadState.StorageFailure(ResumeStorageReason.PointerFailure)
            }
            val priorRecordBackup = if (priorPointer != null) {
                val priorFile = recordFile(priorPointer)
                if (priorFile.exists()) {
                    try {
                        PriorRecordBackup(
                            file = priorFile,
                            bytes = priorFile.readBounded(MAX_ENVELOPE_BYTES),
                            pointerBytes = priorPointerBytes ?: priorPointer.toByteArray(Charsets.UTF_8)
                        )
                    } catch (_: BoundedReadException) {
                        return@synchronized ResumeReadState.StorageFailure(ResumeStorageReason.IoFailure)
                    } catch (_: Exception) {
                        return@synchronized ResumeReadState.StorageFailure(ResumeStorageReason.IoFailure)
                    }
                } else {
                    return@synchronized ResumeReadState.StorageFailure(ResumeStorageReason.IoFailure)
                }
            } else {
                null
            }

            try {
                atomicWrite(pointerFile, requestId.toByteArray(Charsets.UTF_8))
            } catch (error: Exception) {
                // atomicWrite may have moved the file before a directory-sync
                // failure. Restore the prior pointer as far as the filesystem
                // permits, while leaving the prepared record available.
                try {
                    if (priorPointerBytes != null) {
                        atomicWrite(pointerFile, priorPointerBytes)
                    } else {
                        deleteFileDurably(pointerFile, ResumeStorageReason.PointerFailure)
                    }
                } catch (rollback: Throwable) {
                    error.addSuppressed(rollback)
                }
                return@synchronized ResumeReadState.StorageFailure(ResumeStorageReason.PointerFailure)
            }

            if (priorRecordBackup != null && priorRecordBackup.file.name != recordFile(requestId).name) {
                try {
                    deleteFileDurably(priorRecordBackup.file, ResumeStorageReason.IoFailure)
                } catch (error: Throwable) {
                    val rollbackFailures = rollbackPromotion(
                        requestId = requestId,
                        priorPointerBytes = priorPointerBytes,
                        priorRecordBackup = priorRecordBackup
                    )
                    rollbackFailures.forEach(error::addSuppressed)
                    val reason = (error as? ResumeCryptoFailure)?.reason ?: ResumeStorageReason.IoFailure
                    return@synchronized ResumeReadState.StorageFailure(reason)
                }
            }

            try {
                deleteLegacyOrThrow()
                deletePreparedMarker(requestId)
                prepared
            } catch (error: ResumeCryptoFailure) {
                ResumeReadState.StorageFailure(error.reason)
            } catch (_: Exception) {
                ResumeReadState.StorageFailure(ResumeStorageReason.LegacyDeletionFailure)
            }
        } catch (error: ResumeCryptoFailure) {
            ResumeReadState.StorageFailure(error.reason)
        } catch (_: Exception) {
            ResumeReadState.StorageFailure(ResumeStorageReason.IoFailure)
        }
    }

    /**
     * Discards only the exact non-active prepared record.  Any malformed
     * existing pointer causes a fail-closed result because the active
     * generation cannot be identified safely.
     */
    internal fun discardPreparedRequest(requestId: String): Boolean = synchronized(STORE_LOCK) {
        if (!isValidRequestId(requestId)) return@synchronized false
        if (hasClearAllGuard()) return@synchronized false
        return@synchronized try {
            val pointer = readPointer()
            if (pointer != null && !isValidRequestId(pointer)) return@synchronized false
            if (pointer == requestId) return@synchronized false
            persistClearTombstone(requestId)
            deleteFileDurably(recordFile(requestId), ResumeStorageReason.IoFailure)
            deletePreparedMarker(requestId)
            deleteClearTombstone(requestId)
            true
        } catch (_: Exception) {
            false
        }
    }

    internal fun loadRequestDetailed(requestId: String): ResumeReadState = synchronized(STORE_LOCK) {
        if (!isValidRequestId(requestId)) {
            return@synchronized ResumeReadState.Invalid(ResumeInvalidReason.InvalidRequestId)
        }
        if (hasClearAllGuard() || hasClearTombstone(requestId)) {
            return@synchronized ResumeReadState.StorageFailure(ResumeStorageReason.IoFailure)
        }
        val pointer = try {
            readPointer()
        } catch (error: ResumeCryptoFailure) {
            return@synchronized ResumeReadState.StorageFailure(error.reason)
        } catch (_: Exception) {
            return@synchronized ResumeReadState.StorageFailure(ResumeStorageReason.IoFailure)
        }
        if (pointer != requestId) return@synchronized ResumeReadState.Missing

        when (val state = loadRecord(requestId)) {
            ResumeReadState.Expired,
            is ResumeReadState.Invalid -> {
                val cleanupFailure = cleanupActiveRecord(requestId)
                cleanupFailure?.let(ResumeReadState::StorageFailure) ?: state
            }
            ResumeReadState.Missing -> {
                val cleanupFailure = cleanupActiveRecord(requestId)
                cleanupFailure?.let(ResumeReadState::StorageFailure) ?: ResumeReadState.Missing
            }
            else -> state
        }
    }

    /** Removes one exact request generation without touching a different pointer. */
    internal fun clearRequest(requestId: String): Boolean = synchronized(STORE_LOCK) {
        if (!isValidRequestId(requestId)) return@synchronized false
        if (hasClearAllGuard()) return@synchronized false
        return@synchronized try {
            val pointer = readPointer()
            if (pointer != null && !isValidRequestId(pointer)) return@synchronized false
            val guardAlreadyCommitted = hasClearTombstone(requestId)
            val exactRecordExists = recordFile(requestId).exists()
            val exactPreparedMarkerExists = preparedMarkerFile(requestId).exists()
            if (pointer != requestId &&
                !guardAlreadyCommitted &&
                !exactRecordExists &&
                !exactPreparedMarkerExists
            ) {
                return@synchronized true
            }
            // Persist a do-not-recover guard before unlinking either side of
            // the pointer -> record pair. If the clear is interrupted after
            // the pointer is gone, orphan recovery must not resurrect it.
            if (!guardAlreadyCommitted) persistClearTombstone(requestId)
            // Remove the selector first. A directory-sync failure must retain
            // the encrypted record; it must never leave a durable pointer that
            // names a record already deleted by this operation.
            if (pointer == requestId) {
                deleteFileDurably(pointerFile, ResumeStorageReason.PointerFailure)
            }
            deleteFileDurably(recordFile(requestId), ResumeStorageReason.IoFailure)
            deletePreparedMarker(requestId)
            deleteClearTombstone(requestId)
            true
        } catch (_: Exception) {
            false
        }
    }

    internal fun saveDetailed(
        input: IdentityInput,
        deepResearch: Boolean,
        strongFaceCorrelation: Boolean = false
    ): ResumeWriteState = synchronized(STORE_LOCK) {
        val prepared = prepareRequestDetailed(
            input = input,
            deepResearch = deepResearch,
            strongFaceCorrelation = strongFaceCorrelation
        )
        if (prepared !is ResumeWriteState.Saved) return@synchronized prepared

        return@synchronized when (val promoted = promotePreparedRequestDetailed(prepared.point.requestId)) {
            is ResumeReadState.Available -> prepared
            is ResumeReadState.Invalid -> {
                discardPreparedRequest(prepared.point.requestId)
                ResumeWriteState.Invalid(promoted.reason)
            }
            is ResumeReadState.StorageFailure -> {
                // Promotion may have committed B's pointer, or a rollback may
                // only be visible but not durable after a directory-fsync
                // failure. Preserve encrypted B + its prepared marker; exact
                // reconciliation can retry without risking pointer -> missing.
                ResumeWriteState.StorageFailure(promoted.reason)
            }
            ResumeReadState.Expired,
            ResumeReadState.Missing -> {
                discardPreparedRequest(prepared.point.requestId)
                ResumeWriteState.StorageFailure(ResumeStorageReason.IoFailure)
            }
        }
    }

    /** Typed state for callers that need to distinguish absence from corruption. */
    internal fun loadDetailed(): ResumeReadState = synchronized(STORE_LOCK) {
        return try {
            if (hasClearAllGuard()) {
                return@synchronized ResumeReadState.StorageFailure(ResumeStorageReason.IoFailure)
            }
            when (val migration = migrateLegacyIfNeeded()) {
                LegacyMigration.None -> loadActiveRecord()
                is LegacyMigration.Migrated -> {
                    if (hasClearTombstone(migration.requestId)) {
                        ResumeReadState.StorageFailure(ResumeStorageReason.IoFailure)
                    } else {
                        loadRecord(migration.requestId)
                    }
                }
                is LegacyMigration.Invalid -> ResumeReadState.Invalid(migration.reason)
                is LegacyMigration.StorageFailure -> ResumeReadState.StorageFailure(migration.reason)
            }
        } catch (error: ResumeCryptoFailure) {
            ResumeReadState.StorageFailure(error.reason)
        } catch (_: Exception) {
            ResumeReadState.StorageFailure(ResumeStorageReason.IoFailure)
        }
    }

    internal fun clearDetailed(): ResumeWriteState = synchronized(STORE_LOCK) {
        return try {
            val failures = mutableListOf<ResumeStorageReason>()
            val guardReady = try {
                // The global guard is committed before the selector is
                // touched. It blocks every orphan-recovery path while an
                // explicit clear is incomplete.
                persistClearAllGuard()
                true
            } catch (error: ResumeCryptoFailure) {
                failures += error.reason
                false
            } catch (_: Exception) {
                failures += ResumeStorageReason.IoFailure
                false
            }

            val pointerCleared = if (guardReady) {
                try {
                    // Pointer-first preserves the pointer -> record invariant
                    // if either deletion or its directory fsync fails. The
                    // guard above prevents a deleted pointer from making the
                    // encrypted record eligible for orphan recovery.
                    deleteFileDurably(pointerFile, ResumeStorageReason.PointerFailure)
                    true
                } catch (error: ResumeCryptoFailure) {
                    failures += error.reason
                    false
                } catch (_: Exception) {
                    failures += ResumeStorageReason.PointerFailure
                    false
                }
            } else {
                false
            }

            if (pointerCleared && recordsDir.exists()) {
                val files = recordsDir.listFiles()
                if (files == null) {
                    failures += ResumeStorageReason.IoFailure
                } else {
                    files.filter {
                        it.name.endsWith(RECORD_EXTENSION) ||
                            it.name.endsWith(PREPARED_EXTENSION) ||
                            it.name.endsWith(CLEAR_TOMBSTONE_EXTENSION) ||
                            it.name.endsWith(".$TEMP_EXTENSION")
                    }
                        .forEach { file ->
                            try {
                                deleteFileDurably(file, ResumeStorageReason.IoFailure)
                            } catch (error: ResumeCryptoFailure) {
                                failures += error.reason
                            } catch (_: Exception) {
                                failures += ResumeStorageReason.IoFailure
                            }
                        }
                }
            }

            // Keep the legacy fallback recoverable when the encrypted side
            // could not be durably selected for clearing. Once all known
            // encrypted artifacts are gone, remove it as part of the explicit
            // clear transaction.
            if (pointerCleared && failures.isEmpty()) {
                try {
                    deleteLegacyOrThrow()
                } catch (error: ResumeCryptoFailure) {
                    failures += error.reason
                } catch (_: Exception) {
                    failures += ResumeStorageReason.LegacyDeletionFailure
                }
            }
            if (pointerCleared && failures.isEmpty() && !legacyFile.exists()) {
                try {
                    removeEmptyLegacyDirectory()
                } catch (error: ResumeCryptoFailure) {
                    failures += error.reason
                } catch (_: Exception) {
                    failures += ResumeStorageReason.LegacyDeletionFailure
                }
            }
            if (failures.isEmpty()) {
                try {
                    deleteClearAllGuard()
                } catch (error: ResumeCryptoFailure) {
                    failures += error.reason
                } catch (_: Exception) {
                    failures += ResumeStorageReason.IoFailure
                }
            }
            if (failures.isNotEmpty()) {
                ResumeWriteState.StorageFailure(failures.first())
            } else {
                ResumeWriteState.Cleared
            }
        } catch (error: ResumeCryptoFailure) {
            ResumeWriteState.StorageFailure(error.reason)
        } catch (_: Exception) {
            ResumeWriteState.StorageFailure(ResumeStorageReason.IoFailure)
        }
    }

    private fun loadActiveRecord(): ResumeReadState {
        if (hasClearAllGuard()) {
            return ResumeReadState.StorageFailure(ResumeStorageReason.IoFailure)
        }
        val pointer = readPointer()
            ?: return recoverOrphanRecord() ?: ResumeReadState.Missing
        if (!isValidRequestId(pointer)) {
            return cleanupPointerFailureOr(ResumeInvalidReason.InvalidRequestId)
        }
        if (hasClearTombstone(pointer)) {
            // An explicit clear may have removed the pointer but not yet
            // removed the encrypted payload. Never expose that payload while
            // its durable do-not-recover guard remains.
            return ResumeReadState.StorageFailure(ResumeStorageReason.IoFailure)
        }

        return when (val state = loadRecord(pointer)) {
            is ResumeReadState.Expired -> {
                val failure = cleanupActiveRecord(pointer)
                failure?.let(ResumeReadState::StorageFailure) ?: state
            }
            is ResumeReadState.Invalid -> {
                // A malformed/tampered resume must never be retried as plaintext.
                val failure = cleanupActiveRecord(pointer)
                failure?.let(ResumeReadState::StorageFailure) ?: state
            }
            ResumeReadState.Missing -> {
                val failure = cleanupActiveRecord(pointer)
                failure?.let(ResumeReadState::StorageFailure)
                    ?: recoverOrphanRecord()
                    ?: ResumeReadState.Missing
            }
            else -> state
        }
    }

    private fun cleanupPointerFailureOr(reason: ResumeInvalidReason): ResumeReadState {
        return try {
            deleteFileDurably(pointerFile, ResumeStorageReason.PointerFailure)
            ResumeReadState.Invalid(reason)
        } catch (error: ResumeCryptoFailure) {
            ResumeReadState.StorageFailure(error.reason)
        } catch (_: Exception) {
            ResumeReadState.StorageFailure(ResumeStorageReason.PointerFailure)
        }
    }

    private fun cleanupActiveRecord(requestId: String): ResumeStorageReason? {
        var failure: ResumeStorageReason? = null
        // Commit the guard before unlinking either half of the active pair.
        // If any later unlink/fsync is uncertain, the payload remains hidden
        // from orphan recovery until a retry completes the clear.
        try {
            persistClearTombstone(requestId)
        } catch (error: ResumeCryptoFailure) {
            return error.reason
        } catch (_: Exception) {
            return ResumeStorageReason.PointerFailure
        }
        // Clear the selector before the payload. If this is not durably
        // possible, retain the payload and report the pointer failure.
        try {
            deleteFileDurably(pointerFile, ResumeStorageReason.PointerFailure)
        } catch (error: ResumeCryptoFailure) {
            failure = error.reason
        } catch (_: Exception) {
            failure = ResumeStorageReason.PointerFailure
        }
        if (failure != null) return failure
        try {
            deleteFileDurably(recordFile(requestId), ResumeStorageReason.IoFailure)
        } catch (error: ResumeCryptoFailure) {
            failure = error.reason
        } catch (_: Exception) {
            failure = ResumeStorageReason.IoFailure
        }
        if (failure == null) {
            try {
                deletePreparedMarker(requestId)
            } catch (error: ResumeCryptoFailure) {
                failure = error.reason
            } catch (_: Exception) {
                failure = ResumeStorageReason.IoFailure
            }
        }
        if (failure == null) {
            try {
                deleteClearTombstone(requestId)
            } catch (error: ResumeCryptoFailure) {
                failure = error.reason
            } catch (_: Exception) {
                failure = ResumeStorageReason.IoFailure
            }
        }
        return failure
    }

    /** Removes one exact record, clearing the pointer only when it names it. */
    private fun cleanupPreparedRecord(requestId: String): ResumeStorageReason? {
        val pointer = try {
            readPointer()
        } catch (error: ResumeCryptoFailure) {
            return error.reason
        } catch (_: Exception) {
            return ResumeStorageReason.PointerFailure
        }
        if (pointer != null && !isValidRequestId(pointer)) {
            // We cannot establish that this record is not the active one.
            return ResumeStorageReason.PointerFailure
        }
        return if (pointer == requestId) {
            cleanupActiveRecord(requestId)
        } else {
            try {
                persistClearTombstone(requestId)
                deleteFileDurably(recordFile(requestId), ResumeStorageReason.IoFailure)
                deletePreparedMarker(requestId)
                deleteClearTombstone(requestId)
                null
            } catch (error: ResumeCryptoFailure) {
                error.reason
            } catch (_: Exception) {
                ResumeStorageReason.IoFailure
            }
        }
    }

    /**
     * Restores the previous generation after a promotion-side deletion
     * failure.  The returned list is intentionally diagnostic only; callers
     * still report the original storage failure and keep the operation
     * fail-closed.
     */
    private fun rollbackPromotion(
        requestId: String,
        priorPointerBytes: ByteArray?,
        priorRecordBackup: PriorRecordBackup?
    ): List<Throwable> {
        val failures = mutableListOf<Throwable>()
        var priorRecordRestored = priorRecordBackup == null
        if (priorRecordBackup != null) {
            try {
                atomicWrite(priorRecordBackup.file, priorRecordBackup.bytes)
                priorRecordRestored = true
            } catch (error: Throwable) {
                failures += error
                // A read-back after a failed directory fsync only proves the
                // current process view, not crash durability. Keep B and do
                // not restore the pointer when A restoration is uncertain.
                priorRecordRestored = false
            }
        }
        var pointerRestored = false
        // Restore the pointer only after A is known recoverable. Until then,
        // the current pointer and encrypted B record remain a coherent pair.
        if (priorRecordRestored) {
            try {
                if (priorPointerBytes != null) {
                    atomicWrite(pointerFile, priorPointerBytes)
                } else {
                    deleteFileDurably(pointerFile, ResumeStorageReason.PointerFailure)
                }
                pointerRestored = true
            } catch (error: Throwable) {
                failures += error
                // Never infer durable pointer restoration from immediate
                // read-back after a failed directory fsync.
                pointerRestored = false
            }
        }
        // Never delete B while any on-disk pointer may still name B or while
        // restoring A is uncertain. An encrypted orphan is safer than a
        // pointer that references a missing record.
        if (pointerRestored && priorRecordRestored) {
            try {
                deleteFileDurably(recordFile(requestId), ResumeStorageReason.IoFailure)
                deletePreparedMarker(requestId)
            } catch (error: Throwable) {
                failures += error
            }
        }
        return failures
    }

    private fun loadRecord(requestId: String): ResumeReadState {
        if (!isValidRequestId(requestId)) {
            return ResumeReadState.Invalid(ResumeInvalidReason.InvalidRequestId)
        }
        val file = recordFile(requestId)
        if (!file.exists()) return ResumeReadState.Missing

        val envelopeBytes = try {
            file.readBounded(MAX_ENVELOPE_BYTES)
        } catch (_: BoundedReadException) {
            return ResumeReadState.Invalid(ResumeInvalidReason.MalformedEnvelope)
        } catch (_: Exception) {
            return ResumeReadState.StorageFailure(ResumeStorageReason.IoFailure)
        }

        val envelope = try {
            json.decodeFromString<EncryptedEnvelope>(envelopeBytes.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            return ResumeReadState.Invalid(ResumeInvalidReason.MalformedEnvelope)
        }
        if (envelope.formatVersion != FORMAT_VERSION) {
            return ResumeReadState.Invalid(ResumeInvalidReason.UnsupportedVersion)
        }

        val iv = try {
            Base64.getDecoder().decode(envelope.ivBase64)
        } catch (_: IllegalArgumentException) {
            return ResumeReadState.Invalid(ResumeInvalidReason.MalformedEnvelope)
        }
        val ciphertext = try {
            Base64.getDecoder().decode(envelope.ciphertextBase64)
        } catch (_: IllegalArgumentException) {
            return ResumeReadState.Invalid(ResumeInvalidReason.MalformedEnvelope)
        }
        if (iv.size != GCM_IV_BYTES || ciphertext.size <= GCM_TAG_BYTES || ciphertext.size > MAX_RECORD_BYTES + GCM_TAG_BYTES) {
            return ResumeReadState.Invalid(ResumeInvalidReason.MalformedEnvelope)
        }

        val plaintext = try {
            crypto.open(
                SealedResumePayload(iv = iv, ciphertext = ciphertext),
                aad(requestId)
            )
        } catch (_: AEADBadTagException) {
            return ResumeReadState.Invalid(ResumeInvalidReason.AuthenticationFailed)
        } catch (error: ResumeCryptoFailure) {
            return ResumeReadState.StorageFailure(error.reason)
        } catch (_: GeneralSecurityException) {
            return ResumeReadState.StorageFailure(ResumeStorageReason.KeyFailure)
        } catch (_: Exception) {
            return ResumeReadState.Invalid(ResumeInvalidReason.AuthenticationFailed)
        }

        val record = try {
            json.decodeFromString<ResumeRecord>(plaintext.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            return ResumeReadState.Invalid(ResumeInvalidReason.MalformedPayload)
        }
        if (record.formatVersion != FORMAT_VERSION || record.requestId != requestId) {
            return ResumeReadState.Invalid(ResumeInvalidReason.RequestIdMismatch)
        }

        if (!hasValidTimestamps(record)) {
            return ResumeReadState.Invalid(ResumeInvalidReason.InvalidTimestamp)
        }

        if (record.expiresAtEpochMillis <= nowMillis()) {
            return ResumeReadState.Expired
        }
        if (!isValidRequestId(record.requestId) ||
            validateInput(record.input) != null
        ) {
            return ResumeReadState.Invalid(ResumeInvalidReason.InvalidPayload)
        }

        return ResumeReadState.Available(
            ResumePoint(
                requestId = record.requestId,
                input = record.input,
                deepResearch = record.deepResearch,
                scanMode = record.scanMode,
                strongFaceCorrelation = record.strongFaceCorrelation,
                createdAtEpochMillis = record.createdAtEpochMillis,
                expiresAtEpochMillis = record.expiresAtEpochMillis
            )
        )
    }

    /**
     * Persists a prepared record without touching either the active pointer or
     * the record currently selected by that pointer.
     */
    private fun persistPreparedMarker(requestId: String) {
        val currentPointer = readPointer()
        if (currentPointer != null && !isValidRequestId(currentPointer)) {
            throw ResumeCryptoFailure(ResumeStorageReason.PointerFailure)
        }
        if (currentPointer == requestId ||
            recordFile(requestId).exists() ||
            preparedMarkerFile(requestId).exists() ||
            clearTombstoneFile(requestId).exists()
        ) {
            throw ResumeInvalidOperation(ResumeInvalidReason.InvalidRequestId)
        }
        atomicWrite(preparedMarkerFile(requestId), PREPARED_MARKER_BYTES)
    }

    private fun persistPreparedRecord(record: ResumeRecord, allowKeyCreation: Boolean) {
        if (hasClearAllGuard()) {
            throw ResumeCryptoFailure(ResumeStorageReason.IoFailure)
        }
        val currentPointer = readPointer()
        if (currentPointer != null && !isValidRequestId(currentPointer)) {
            throw ResumeCryptoFailure(ResumeStorageReason.PointerFailure)
        }
        if (currentPointer == record.requestId) {
            // A UUID collision must never overwrite the active generation.
            throw ResumeInvalidOperation(ResumeInvalidReason.InvalidRequestId)
        }
        if (currentPointer != null && !recordFile(currentPointer).exists()) {
            throw ResumeCryptoFailure(ResumeStorageReason.IoFailure)
        }
        val target = recordFile(record.requestId)
        if (target.exists()) {
            // A UUID collision must never overwrite another recoverable
            // prepared/orphaned generation.
            throw ResumeInvalidOperation(ResumeInvalidReason.InvalidRequestId)
        }
        val plaintext = json.encodeToString(record).toByteArray(Charsets.UTF_8)
        if (plaintext.size > MAX_RECORD_BYTES) {
            throw ResumeInvalidOperation(ResumeInvalidReason.RecordTooLarge)
        }
        ensureDirectory(recordsDir)
        val sealed = crypto.seal(plaintext, aad(record.requestId), allowKeyCreation)
        if (sealed.iv.size != GCM_IV_BYTES) {
            throw ResumeCryptoFailure(ResumeStorageReason.KeyFailure, IllegalStateException("Invalid IV size"))
        }
        if (sealed.ciphertext.size <= GCM_TAG_BYTES ||
            sealed.ciphertext.size > MAX_RECORD_BYTES + GCM_TAG_BYTES
        ) {
            throw ResumeInvalidOperation(ResumeInvalidReason.RecordTooLarge)
        }
        val envelope = EncryptedEnvelope(
            formatVersion = FORMAT_VERSION,
            ivBase64 = Base64.getEncoder().encodeToString(sealed.iv),
            ciphertextBase64 = Base64.getEncoder().encodeToString(sealed.ciphertext)
        )
        val encodedEnvelope = json.encodeToString(envelope).toByteArray(Charsets.UTF_8)
        if (encodedEnvelope.size > MAX_ENVELOPE_BYTES) {
            throw ResumeInvalidOperation(ResumeInvalidReason.RecordTooLarge)
        }

        val targetBackup: ByteArray? = null
        try {
            atomicWrite(target, encodedEnvelope)
        } catch (error: Exception) {
            val rollbackFailure = restoreFile(target, targetBackup, ResumeStorageReason.IoFailure)
            val failure = ResumeCryptoFailure(ResumeStorageReason.IoFailure, error)
            rollbackFailure?.let(failure::addSuppressed)
            throw failure
        }
    }

    private fun persistRecord(record: ResumeRecord) {
        if (hasClearAllGuard()) {
            throw ResumeCryptoFailure(ResumeStorageReason.IoFailure)
        }
        val priorPointer = readPointer()
        if (pointerFile.exists() && priorPointer == null) {
            throw ResumeCryptoFailure(ResumeStorageReason.PointerFailure)
        }
        if (priorPointer != null && !isValidRequestId(priorPointer)) {
            throw ResumeCryptoFailure(ResumeStorageReason.PointerFailure)
        }
        val priorPointerBytes = if (priorPointer != null) {
            pointerFile.readBounded(MAX_POINTER_BYTES)
        } else {
            null
        }
        val priorRecordBackup: PriorRecordBackup?
        if (priorPointer != null) {
            val priorFile = recordFile(priorPointer)
            if (priorFile.exists()) {
                priorRecordBackup = PriorRecordBackup(
                    file = priorFile,
                    bytes = priorFile.readBounded(MAX_ENVELOPE_BYTES),
                    pointerBytes = priorPointerBytes ?: priorPointer.toByteArray(Charsets.UTF_8)
                )
            } else {
                throw ResumeCryptoFailure(ResumeStorageReason.IoFailure)
            }
        } else {
            priorRecordBackup = null
        }
        val target = recordFile(record.requestId)
        // Never overwrite an active, prepared, or orphaned encrypted
        // generation when a UUID source collides.
        if (target.exists()) {
            throw ResumeInvalidOperation(ResumeInvalidReason.InvalidRequestId)
        }
        val plaintext = json.encodeToString(record).toByteArray(Charsets.UTF_8)
        if (plaintext.size > MAX_RECORD_BYTES) {
            throw ResumeInvalidOperation(ResumeInvalidReason.RecordTooLarge)
        }
        ensureDirectory(recordsDir)
        val allowKeyCreation = canCreateKey()
        val sealed = crypto.seal(plaintext, aad(record.requestId), allowKeyCreation)
        if (sealed.iv.size != GCM_IV_BYTES) {
            throw ResumeCryptoFailure(ResumeStorageReason.KeyFailure, IllegalStateException("Invalid IV size"))
        }
        if (sealed.ciphertext.size <= GCM_TAG_BYTES ||
            sealed.ciphertext.size > MAX_RECORD_BYTES + GCM_TAG_BYTES
        ) {
            throw ResumeInvalidOperation(ResumeInvalidReason.RecordTooLarge)
        }

        val envelope = EncryptedEnvelope(
            formatVersion = FORMAT_VERSION,
            ivBase64 = Base64.getEncoder().encodeToString(sealed.iv),
            ciphertextBase64 = Base64.getEncoder().encodeToString(sealed.ciphertext)
        )
        val encodedEnvelope = json.encodeToString(envelope).toByteArray(Charsets.UTF_8)
        if (encodedEnvelope.size > MAX_ENVELOPE_BYTES) {
            throw ResumeInvalidOperation(ResumeInvalidReason.RecordTooLarge)
        }
        try {
            atomicWrite(target, encodedEnvelope)
        } catch (error: Exception) {
            val rollbackFailure = restoreFile(target, null, ResumeStorageReason.IoFailure)
            val failure = ResumeCryptoFailure(ResumeStorageReason.IoFailure, error)
            rollbackFailure?.let(failure::addSuppressed)
            throw failure
        }

        try {
            atomicWrite(pointerFile, record.requestId.toByteArray(Charsets.UTF_8))
        } catch (error: Exception) {
            val rollbackFailures = rollbackPromotion(
                requestId = record.requestId,
                priorPointerBytes = priorPointerBytes,
                priorRecordBackup = priorRecordBackup
            )
            val failure = ResumeCryptoFailure(ResumeStorageReason.PointerFailure, error)
            rollbackFailures.forEach(failure::addSuppressed)
            throw failure
        }
        if (priorRecordBackup != null && priorRecordBackup.file.name != target.name) {
            try {
                deleteFileDurably(priorRecordBackup.file, ResumeStorageReason.IoFailure)
            } catch (error: Exception) {
                val rollbackFailures = rollbackPromotion(
                    requestId = record.requestId,
                    priorPointerBytes = priorPointerBytes,
                    priorRecordBackup = priorRecordBackup
                )
                val failure = ResumeCryptoFailure(ResumeStorageReason.IoFailure, error)
                rollbackFailures.forEach(failure::addSuppressed)
                throw failure
            }
        }
    }

    private fun deleteLegacyAndReturn(result: LegacyMigration): LegacyMigration {
        // A storage/key/pointer failure means no encrypted replacement has
        // been verified. Preserve the only recoverable legacy marker for a
        // later retry instead of turning a transient failure into data loss.
        if (result is LegacyMigration.StorageFailure) return result
        return try {
            deleteLegacyOrThrow()
            result
        } catch (_: ResumeCryptoFailure) {
            LegacyMigration.StorageFailure(ResumeStorageReason.LegacyDeletionFailure)
        } catch (_: Exception) {
            LegacyMigration.StorageFailure(ResumeStorageReason.LegacyDeletionFailure)
        }
    }

    private fun migrateLegacyIfNeeded(): LegacyMigration {
        if (hasClearAllGuard()) {
            return LegacyMigration.StorageFailure(ResumeStorageReason.IoFailure)
        }
        if (!legacyFile.exists()) return LegacyMigration.None

        val currentPointer = try {
            readPointer()
        } catch (error: ResumeCryptoFailure) {
            return deleteLegacyAndReturn(LegacyMigration.StorageFailure(error.reason))
        } catch (_: Exception) {
            return deleteLegacyAndReturn(LegacyMigration.StorageFailure(ResumeStorageReason.IoFailure))
        }
        if (currentPointer != null && isValidRequestId(currentPointer)) {
            if (hasClearTombstone(currentPointer)) {
                return LegacyMigration.StorageFailure(ResumeStorageReason.IoFailure)
            }
            when (val current = loadRecord(currentPointer)) {
                is ResumeReadState.Available -> return deleteLegacyAndReturn(LegacyMigration.None)
                is ResumeReadState.StorageFailure -> {
                    return deleteLegacyAndReturn(LegacyMigration.StorageFailure(current.reason))
                }
                else -> Unit
            }
        } else if (currentPointer == null) {
            when (val recovered = recoverOrphanRecord()) {
                is ResumeReadState.Available -> return deleteLegacyAndReturn(LegacyMigration.None)
                is ResumeReadState.StorageFailure -> {
                    return deleteLegacyAndReturn(LegacyMigration.StorageFailure(recovered.reason))
                }
                else -> Unit
            }
        }

        val legacyBytes = try {
            legacyFile.readBounded(MAX_LEGACY_BYTES)
        } catch (_: BoundedReadException) {
            return deleteLegacyAndReturn(LegacyMigration.Invalid(ResumeInvalidReason.LegacyTooLarge))
        } catch (_: Exception) {
            return deleteLegacyAndReturn(LegacyMigration.StorageFailure(ResumeStorageReason.IoFailure))
        }

        val marker = try {
            json.decodeFromString<LegacyResumeMarker>(legacyBytes.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            return deleteLegacyAndReturn(LegacyMigration.Invalid(ResumeInvalidReason.MalformedLegacy))
        }
        val legacyInput = IdentityInput(
            fullName = marker.fullName,
            aliases = emptyList(),
            primaryUsername = marker.primaryUsername,
            usernames = marker.usernames,
            emails = marker.emails,
            phones = marker.phones,
            organizations = marker.organizations,
            locations = marker.locations,
            profileUrls = marker.profileUrls,
            selfieUri = null
        )
        val inputIssue = validateInput(legacyInput)
        if (inputIssue != null) {
            return deleteLegacyAndReturn(LegacyMigration.Invalid(inputIssue))
        }

        val now = try {
            nowMillis()
        } catch (_: Exception) {
            return deleteLegacyAndReturn(LegacyMigration.StorageFailure(ResumeStorageReason.IoFailure))
        }
        val expiresAt = try {
            expiresAt(now)
        } catch (error: ResumeInvalidOperation) {
            return deleteLegacyAndReturn(LegacyMigration.Invalid(error.reason))
        }
        val requestId = try {
            idFactory()
        } catch (_: Exception) {
            return deleteLegacyAndReturn(LegacyMigration.StorageFailure(ResumeStorageReason.IoFailure))
        }
        if (!isValidRequestId(requestId)) {
            return deleteLegacyAndReturn(LegacyMigration.Invalid(ResumeInvalidReason.InvalidRequestId))
        }
        val record = ResumeRecord(
            formatVersion = FORMAT_VERSION,
            requestId = requestId,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
            expiresAtEpochMillis = expiresAt,
            input = legacyInput,
            deepResearch = marker.deepResearch,
            scanMode = marker.scanMode
        )

        return try {
            persistRecord(record)
            when (val verified = loadRecord(requestId)) {
                is ResumeReadState.Available -> deleteLegacyAndReturn(LegacyMigration.Migrated(requestId))
                is ResumeReadState.StorageFailure -> {
                    val cleanupFailure = cleanupFailedMigration(requestId)
                    val result = cleanupFailure?.let(LegacyMigration::StorageFailure)
                        ?: LegacyMigration.StorageFailure(verified.reason)
                    deleteLegacyAndReturn(result)
                }
                else -> {
                    val cleanupFailure = cleanupFailedMigration(requestId)
                    val result = cleanupFailure?.let(LegacyMigration::StorageFailure)
                        ?: LegacyMigration.Invalid(ResumeInvalidReason.MigrationVerificationFailed)
                    deleteLegacyAndReturn(result)
                }
            }
        } catch (error: ResumeCryptoFailure) {
            val cleanupFailure = cleanupFailedMigration(requestId)
            val result = cleanupFailure?.let(LegacyMigration::StorageFailure)
                ?: LegacyMigration.StorageFailure(error.reason)
            deleteLegacyAndReturn(result)
        } catch (error: ResumeInvalidOperation) {
            val cleanupFailure = cleanupFailedMigration(requestId)
            val result = cleanupFailure?.let(LegacyMigration::StorageFailure)
                ?: LegacyMigration.Invalid(error.reason)
            deleteLegacyAndReturn(result)
        } catch (_: GeneralSecurityException) {
            val cleanupFailure = cleanupFailedMigration(requestId)
            val result = cleanupFailure?.let(LegacyMigration::StorageFailure)
                ?: LegacyMigration.StorageFailure(ResumeStorageReason.KeyFailure)
            deleteLegacyAndReturn(result)
        } catch (_: Exception) {
            val cleanupFailure = cleanupFailedMigration(requestId)
            val result = cleanupFailure?.let(LegacyMigration::StorageFailure)
                ?: LegacyMigration.StorageFailure(ResumeStorageReason.IoFailure)
            deleteLegacyAndReturn(result)
        }
    }

    private fun cleanupFailedMigration(requestId: String): ResumeStorageReason? {
        val pointer = try {
            readPointer()
        } catch (error: ResumeCryptoFailure) {
            return error.reason
        } catch (_: Exception) {
            return ResumeStorageReason.IoFailure
        }
        try {
            persistClearTombstone(requestId)
        } catch (error: ResumeCryptoFailure) {
            return error.reason
        } catch (_: Exception) {
            return ResumeStorageReason.IoFailure
        }
        if (pointer == requestId) {
            try {
                deleteFileDurably(pointerFile, ResumeStorageReason.PointerFailure)
            } catch (error: ResumeCryptoFailure) {
                return error.reason
            } catch (_: Exception) {
                return ResumeStorageReason.PointerFailure
            }
        }
        return try {
            deleteFileDurably(recordFile(requestId), ResumeStorageReason.IoFailure)
            deleteClearTombstone(requestId)
            null
        } catch (error: ResumeCryptoFailure) {
            error.reason
        } catch (_: Exception) {
            ResumeStorageReason.IoFailure
        }
    }

    /**
     * Promotion may only choose between B and the exact generation selected
     * by the pointer. Any other unguarded encrypted record could be recovered
     * as an orphan after a crash, so promotion fails closed until that state is
     * reconciled. Prepared and explicitly-cleared generations are intentionally
     * excluded because generic recovery already refuses to activate them.
     */
    private fun hasUnexpectedRecoverableRecord(
        requestId: String,
        priorPointer: String?
    ): Boolean {
        return encryptedRecordFiles().any { file ->
            val candidateId = file.name.removeSuffix(RECORD_EXTENSION)
            if (candidateId == requestId || candidateId == priorPointer) {
                false
            } else {
                !hasClearTombstone(candidateId) &&
                    !preparedMarkerFile(candidateId).exists()
            }
        }
    }

    private fun recoverOrphanRecord(): ResumeReadState? {
        if (hasClearAllGuard()) {
            return ResumeReadState.StorageFailure(ResumeStorageReason.IoFailure)
        }
        val candidates = encryptedRecordFiles().sortedByDescending { it.lastModified() }
        var guardedCandidate = false
        val recoverable = mutableListOf<Pair<String, ResumeReadState.Available>>()
        for (file in candidates) {
            val requestId = file.name.removeSuffix(RECORD_EXTENSION)
            if (!isValidRequestId(requestId)) continue
            // Prepared B records are activated only by explicit promotion or
            // lifecycle reconciliation, never by generic orphan ordering.
            if (preparedMarkerFile(requestId).exists()) continue
            if (hasClearTombstone(requestId)) {
                guardedCandidate = true
                continue
            }
            when (val state = loadRecord(requestId)) {
                is ResumeReadState.Available -> {
                    recoverable += requestId to state
                }
                is ResumeReadState.StorageFailure -> return state
                is ResumeReadState.Expired,
                is ResumeReadState.Invalid -> {
                    try {
                        deleteFileDurably(file, ResumeStorageReason.IoFailure)
                    } catch (error: ResumeCryptoFailure) {
                        return ResumeReadState.StorageFailure(error.reason)
                    } catch (_: Exception) {
                        return ResumeReadState.StorageFailure(ResumeStorageReason.IoFailure)
                    }
                }
                is ResumeReadState.Missing -> Unit
            }
        }
        if (recoverable.size > 1) {
            // Never choose a generation by timestamp when two independent
            // unguarded records are valid. Leave the pointer untouched until
            // an explicit reconciliation identifies the intended generation.
            return ResumeReadState.StorageFailure(ResumeStorageReason.IoFailure)
        }
        val single = recoverable.singleOrNull()
        if (single != null) {
            val (requestId, state) = single
            return try {
                atomicWrite(pointerFile, requestId.toByteArray(Charsets.UTF_8))
                state
            } catch (error: Exception) {
                val cleanupFailure = runCatching {
                    deleteFileDurably(pointerFile, ResumeStorageReason.PointerFailure)
                }.exceptionOrNull()
                val failure = ResumeCryptoFailure(ResumeStorageReason.PointerFailure, error)
                cleanupFailure?.let(failure::addSuppressed)
                ResumeReadState.StorageFailure(failure.reason)
            }
        }
        return if (guardedCandidate) {
            ResumeReadState.StorageFailure(ResumeStorageReason.IoFailure)
        } else {
            null
        }
    }

    private fun readPointer(): String? {
        if (!pointerFile.exists()) return null
        val bytes = try {
            pointerFile.readBounded(MAX_POINTER_BYTES)
        } catch (e: BoundedReadException) {
            return "invalid_pointer_oversized"
        } catch (e: Exception) {
            throw ResumeCryptoFailure(ResumeStorageReason.IoFailure, e)
        }
        val pointer = bytes.toString(Charsets.UTF_8).trim()
        return pointer.ifBlank { "invalid_pointer_blank" }
    }

    private fun deleteLegacyOrThrow() {
        if (!legacyFile.exists()) return
        deleteFileDurably(legacyFile, ResumeStorageReason.LegacyDeletionFailure)
        removeEmptyLegacyDirectory()
    }

    private fun removeEmptyLegacyDirectory() {
        if (legacyDir == recordsDir || !legacyDir.exists()) return
        val files = legacyDir.listFiles()
            ?: throw ResumeCryptoFailure(ResumeStorageReason.LegacyDeletionFailure)
        if (files.isEmpty()) {
            if (!legacyDir.delete()) {
                throw ResumeCryptoFailure(ResumeStorageReason.LegacyDeletionFailure)
            }
            legacyDir.parentFile?.let(dirSyncer::sync)
        }
    }

    private fun encryptedRecordFiles(): List<File> {
        if (!recordsDir.exists()) return emptyList()
        val files = recordsDir.listFiles()
        if (files == null) {
            throw ResumeCryptoFailure(ResumeStorageReason.IoFailure)
        }
        return files.filter { file ->
            file.isFile && file.name.endsWith(RECORD_EXTENSION) &&
                isValidRequestId(file.name.removeSuffix(RECORD_EXTENSION))
        }
    }

    private fun recordFile(requestId: String): File {
        if (!isValidRequestId(requestId)) {
            throw ResumeInvalidOperation(ResumeInvalidReason.InvalidRequestId)
        }
        val root = try {
            recordsDir.canonicalFile
        } catch (e: Exception) {
            throw ResumeCryptoFailure(ResumeStorageReason.IoFailure, e)
        }
        val target = try {
            File(root, "$requestId$RECORD_EXTENSION").canonicalFile
        } catch (e: Exception) {
            throw ResumeCryptoFailure(ResumeStorageReason.IoFailure, e)
        }
        if (target.parentFile != root) {
            throw ResumeInvalidOperation(ResumeInvalidReason.InvalidRequestId)
        }
        return target
    }

    private fun preparedMarkerFile(requestId: String): File {
        if (!isValidRequestId(requestId)) {
            throw ResumeInvalidOperation(ResumeInvalidReason.InvalidRequestId)
        }
        val root = try {
            recordsDir.canonicalFile
        } catch (error: Exception) {
            throw ResumeCryptoFailure(ResumeStorageReason.IoFailure, error)
        }
        val target = try {
            File(root, "$requestId$PREPARED_EXTENSION").canonicalFile
        } catch (error: Exception) {
            throw ResumeCryptoFailure(ResumeStorageReason.IoFailure, error)
        }
        if (target.parentFile != root) {
            throw ResumeInvalidOperation(ResumeInvalidReason.InvalidRequestId)
        }
        return target
    }

    private fun hasValidPreparedMarker(requestId: String): Boolean {
        val marker = preparedMarkerFile(requestId)
        if (!marker.exists() || !marker.isFile) return false
        return try {
            marker.readBounded(MAX_PREPARED_MARKER_BYTES)
                .contentEquals(PREPARED_MARKER_BYTES)
        } catch (_: Exception) {
            false
        }
    }

    private fun deletePreparedMarker(requestId: String) {
        deleteFileDurably(preparedMarkerFile(requestId), ResumeStorageReason.IoFailure)
    }

    /**
     * Returns the durable per-request clear guard. Its filename contains only
     * the opaque request UUID already used by the encrypted record; the guard
     * payload contains no identity input or other plaintext metadata.
     */
    private fun clearTombstoneFile(requestId: String): File {
        if (!isValidRequestId(requestId)) {
            throw ResumeInvalidOperation(ResumeInvalidReason.InvalidRequestId)
        }
        val root = try {
            recordsDir.canonicalFile
        } catch (error: Exception) {
            throw ResumeCryptoFailure(ResumeStorageReason.IoFailure, error)
        }
        val target = try {
            File(root, "$requestId$CLEAR_TOMBSTONE_EXTENSION").canonicalFile
        } catch (error: Exception) {
            throw ResumeCryptoFailure(ResumeStorageReason.IoFailure, error)
        }
        if (target.parentFile != root) {
            throw ResumeInvalidOperation(ResumeInvalidReason.InvalidRequestId)
        }
        return target
    }

    private fun hasClearTombstone(requestId: String): Boolean =
        clearTombstoneFile(requestId).exists()

    private fun persistClearTombstone(requestId: String) {
        persistClearGuard(clearTombstoneFile(requestId), ResumeStorageReason.IoFailure)
    }

    private fun deleteClearTombstone(requestId: String) {
        deleteFileDurably(clearTombstoneFile(requestId), ResumeStorageReason.IoFailure)
    }

    private fun hasClearAllGuard(): Boolean = clearAllGuardFile.exists()

    private fun persistClearAllGuard() {
        persistClearGuard(clearAllGuardFile, ResumeStorageReason.IoFailure)
    }

    private fun deleteClearAllGuard() {
        deleteFileDurably(clearAllGuardFile, ResumeStorageReason.IoFailure)
    }

    /**
     * A guard is idempotent only when its exact one-byte marker is present. An
     * unexpected existing path is treated as a fail-closed storage failure;
     * it is never overwritten while encrypted data may still be recoverable.
     */
    private fun persistClearGuard(file: File, reason: ResumeStorageReason) {
        if (file.exists()) {
            if (!file.isFile) throw ResumeCryptoFailure(reason)
            val existing = try {
                file.readBounded(MAX_CLEAR_GUARD_BYTES)
            } catch (error: Exception) {
                throw ResumeCryptoFailure(reason, error)
            }
            if (!existing.contentEquals(CLEAR_GUARD_BYTES)) {
                throw ResumeCryptoFailure(reason)
            }
            return
        }
        try {
            atomicWrite(file, CLEAR_GUARD_BYTES)
        } catch (error: ResumeCryptoFailure) {
            throw error
        } catch (error: Exception) {
            throw ResumeCryptoFailure(reason, error)
        }
    }

    private fun ensureDirectory(dir: File) {
        if (dir.exists()) {
            if (!dir.isDirectory) throw IOException("Resume path is not a directory")
            return
        }
        if (!dir.mkdirs() && !dir.isDirectory) {
            throw IOException("Unable to create resume directory")
        }
    }

    /**
     * A crash can commit a prepared marker before the encrypted record write
     * begins. When that marker is the only recognized state left, remove the
     * opaque marker (and any encrypted-write temporary) before deciding
     * whether a missing keystore key may be created. Never discard it when an
     * encrypted record, pointer, tombstone, global guard, or unknown artifact
     * is present.
     */
    private fun discardStaleMarkerOnlyState() {
        if (!recordsDir.exists()) return
        if (!recordsDir.isDirectory) {
            throw ResumeCryptoFailure(ResumeStorageReason.IoFailure)
        }
        val files = recordsDir.listFiles()
            ?: throw ResumeCryptoFailure(ResumeStorageReason.IoFailure)
        if (pointerFile.exists() || clearAllGuardFile.exists()) return
        if (files.any {
                it.name.endsWith(RECORD_EXTENSION) ||
                    it.name.endsWith(CLEAR_TOMBSTONE_EXTENSION)
            }
        ) {
            return
        }
        val markerFiles = files.filter { it.name.endsWith(PREPARED_EXTENSION) }
        if (markerFiles.isEmpty()) return
        if (markerFiles.any { file ->
                val requestId = file.name.removeSuffix(PREPARED_EXTENSION)
                !isValidRequestId(requestId) || !hasValidPreparedMarker(requestId)
            }
        ) {
            return
        }
        val unknownFiles = files.filter { file ->
            !file.name.endsWith(PREPARED_EXTENSION) &&
                !file.name.endsWith(".$TEMP_EXTENSION")
        }
        if (unknownFiles.isNotEmpty()) return

        markerFiles.forEach { file ->
            deleteFileDurably(file, ResumeStorageReason.IoFailure)
        }
        files.filter { it.name.endsWith(".$TEMP_EXTENSION") }
            .forEach { file ->
                deleteFileDurably(file, ResumeStorageReason.IoFailure)
            }
    }

    private fun canCreateKey(): Boolean {
        if (pointerFile.exists()) return false
        if (!recordsDir.exists()) return true
        if (!recordsDir.isDirectory) {
            throw ResumeCryptoFailure(ResumeStorageReason.IoFailure)
        }
        val files = recordsDir.listFiles()
            ?: throw ResumeCryptoFailure(ResumeStorageReason.IoFailure)
        return files.none { file ->
            file.name.endsWith(RECORD_EXTENSION) || file.name.endsWith(".$TEMP_EXTENSION")
                || file.name.endsWith(PREPARED_EXTENSION)
                || file.name.endsWith(CLEAR_TOMBSTONE_EXTENSION)
                || file.name == CLEAR_GUARD_FILE_NAME
        }
    }

    private fun deleteFileDurably(file: File, reason: ResumeStorageReason) {
        if (!file.exists()) return
        if (!file.delete()) {
            throw ResumeCryptoFailure(reason)
        }
        val parent = file.parentFile
        if (parent != null) {
            try {
                dirSyncer.sync(parent)
            } catch (error: Exception) {
                throw ResumeCryptoFailure(reason, error)
            }
        }
    }

    private fun restoreFile(
        target: File,
        previousBytes: ByteArray?,
        reason: ResumeStorageReason
    ): Throwable? {
        try {
            if (previousBytes == null) {
                deleteFileDurably(target, reason)
            } else {
                atomicWrite(target, previousBytes)
            }
        } catch (error: Throwable) {
            return error
        }
        return null
    }

    private fun expiresAt(now: Long): Long {
        if (now < 0) throw ResumeInvalidOperation(ResumeInvalidReason.InvalidTimestamp)
        return try {
            Math.addExact(now, TTL_MILLIS)
        } catch (error: ArithmeticException) {
            throw ResumeInvalidOperation(ResumeInvalidReason.InvalidTimestamp)
        }
    }

    private fun hasValidTimestamps(record: ResumeRecord): Boolean {
        if (record.createdAtEpochMillis < 0 ||
            record.updatedAtEpochMillis < record.createdAtEpochMillis ||
            record.updatedAtEpochMillis > record.expiresAtEpochMillis
        ) {
            return false
        }
        val expectedExpiry = try {
            Math.addExact(record.createdAtEpochMillis, TTL_MILLIS)
        } catch (_: ArithmeticException) {
            return false
        }
        return record.expiresAtEpochMillis == expectedExpiry
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val parent = target.parentFile ?: throw IOException("Missing target parent")
        ensureDirectory(parent)
        val tempId = UUID.randomUUID().toString()
        val temporary = File(parent, "${target.name}.$tempId.$TEMP_EXTENSION")
        var failure: Exception? = null
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
            dirSyncer.sync(parent)
        } catch (error: Exception) {
            failure = error
        }
        if (temporary.exists() && !temporary.delete()) {
            val cleanupFailure = IOException("Unable to remove temporary resume file")
            if (failure == null) {
                failure = cleanupFailure
            } else {
                failure?.addSuppressed(cleanupFailure)
            }
        }
        if (failure != null) {
            throw failure as Exception
        }
    }

    private fun File.readBounded(maxBytes: Long): ByteArray {
        if (!exists()) throw IOException("Missing file")
        require(maxBytes in 0 until Int.MAX_VALUE.toLong())
        val capacity = (maxBytes + 1L).toInt()
        FileInputStream(this).use { input ->
            val bytes = ByteArray(capacity)
            var count = 0
            while (count < bytes.size) {
                val read = input.read(bytes, count, bytes.size - count)
                if (read < 0) break
                if (read == 0) {
                    val single = input.read()
                    if (single < 0) break
                    bytes[count++] = single.toByte()
                } else {
                    count += read
                }
            }
            if (count > maxBytes) {
                throw BoundedReadException()
            }
            return bytes.copyOf(count)
        }
    }

    private fun aad(requestId: String): ByteArray =
        "$FORMAT_VERSION:$requestId".toByteArray(Charsets.UTF_8)

    private fun validateInput(input: IdentityInput): ResumeInvalidReason? {
        val strings = listOfNotNull(
            input.fullName,
            input.primaryUsername,
            input.selfieUri
        ) + input.aliases + input.usernames + input.emails + input.phones +
            input.organizations + input.locations + input.profileUrls
        if (strings.any { it.length > MAX_FIELD_CHARS }) return ResumeInvalidReason.InputTooLarge
        val lists = listOf(
            input.aliases,
            input.usernames,
            input.emails,
            input.phones,
            input.organizations,
            input.locations,
            input.profileUrls
        )
        if (lists.any { it.size > MAX_LIST_ITEMS }) return ResumeInvalidReason.InputTooLarge
        return null
    }

    private fun isValidRequestId(value: String): Boolean {
        if (!REQUEST_ID_PATTERN.matches(value)) return false
        return runCatching { UUID.fromString(value).toString() == value.lowercase(Locale.ROOT) }
            .getOrDefault(false)
    }

    @Serializable
    private data class ResumeRecord(
        val formatVersion: Int,
        val requestId: String,
        val createdAtEpochMillis: Long,
        val updatedAtEpochMillis: Long,
        val expiresAtEpochMillis: Long,
        val input: IdentityInput,
        val deepResearch: Boolean,
        val scanMode: ScanMode,
        val strongFaceCorrelation: Boolean = false
    )

    private data class PriorRecordBackup(
        val file: File,
        val bytes: ByteArray,
        val pointerBytes: ByteArray
    )

    @Serializable
    private data class EncryptedEnvelope(
        val formatVersion: Int,
        val ivBase64: String,
        val ciphertextBase64: String
    )

    @Serializable
    private data class LegacyResumeMarker(
        val fullName: String,
        val primaryUsername: String? = null,
        val usernames: List<String> = emptyList(),
        val emails: List<String> = emptyList(),
        val phones: List<String> = emptyList(),
        val organizations: List<String> = emptyList(),
        val locations: List<String> = emptyList(),
        val profileUrls: List<String> = emptyList(),
        val deepResearch: Boolean = false,
        val scanMode: ScanMode = ScanMode.Standard
    )

    private sealed interface LegacyMigration {
        data object None : LegacyMigration
        data class Migrated(val requestId: String) : LegacyMigration
        data class Invalid(val reason: ResumeInvalidReason) : LegacyMigration
        data class StorageFailure(val reason: ResumeStorageReason) : LegacyMigration
    }

    private class ResumeInvalidOperation(val reason: ResumeInvalidReason) : Exception()

    internal companion object {
        private val STORE_LOCK = Any()
        const val FORMAT_VERSION = 1
        const val TTL_MILLIS = 24L * 60L * 60L * 1000L
        const val MAX_RECORD_BYTES = 128 * 1024
        const val MAX_ENVELOPE_BYTES = MAX_RECORD_BYTES * 2L
        const val MAX_LEGACY_BYTES = 64 * 1024L
        const val MAX_FIELD_CHARS = 4_096
        const val MAX_LIST_ITEMS = 128
        const val MAX_POINTER_BYTES = 64L
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BYTES = 16
        const val RECORD_EXTENSION = ".dscan"
        const val PREPARED_EXTENSION = ".prepared"
        const val CLEAR_TOMBSTONE_EXTENSION = ".cleared"
        const val TOMBSTONE_EXTENSION = CLEAR_TOMBSTONE_EXTENSION
        const val TEMP_EXTENSION = "tmp"
        const val RECORDS_DIRECTORY = "dossier_scan_checkpoints"
        const val LEGACY_DIRECTORY = "dossier_resume"
        const val LEGACY_FILE_NAME = "dossier_resume.json"
        const val POINTER_FILE_NAME = "active_checkpoint.id"
        private const val MAX_PREPARED_MARKER_BYTES = 8L
        private val PREPARED_MARKER_BYTES = byteArrayOf(1)
        private const val MAX_CLEAR_GUARD_BYTES = 8L
        private val CLEAR_GUARD_BYTES = byteArrayOf(1)
        private const val CLEAR_GUARD_FILE_NAME = "clear.guard"
        const val KEY_ALIAS = "dossier-scan-resume-v1"
        private val REQUEST_ID_PATTERN =
            Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    }

    private val pointerFile: File
        get() = File(recordsDir, POINTER_FILE_NAME)

    private val clearAllGuardFile: File
        get() = File(recordsDir, CLEAR_GUARD_FILE_NAME)

    private val legacyFile: File
        get() = File(legacyDir, LEGACY_FILE_NAME)
}

internal data class ResumePoint(
    val requestId: String,
    val input: IdentityInput,
    val deepResearch: Boolean,
    val scanMode: ScanMode,
    val strongFaceCorrelation: Boolean,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long
)

internal sealed interface ResumeReadState {
    data class Available(val point: ResumePoint) : ResumeReadState
    data object Missing : ResumeReadState
    data object Expired : ResumeReadState
    data class Invalid(val reason: ResumeInvalidReason) : ResumeReadState
    data class StorageFailure(val reason: ResumeStorageReason) : ResumeReadState
}

internal sealed interface ResumeWriteState {
    data class Saved(val point: ResumePoint) : ResumeWriteState
    data object Cleared : ResumeWriteState
    data class Invalid(val reason: ResumeInvalidReason) : ResumeWriteState
    data class StorageFailure(val reason: ResumeStorageReason) : ResumeWriteState
}

internal enum class ResumeInvalidReason {
    InvalidRequestId,
    MalformedEnvelope,
    UnsupportedVersion,
    AuthenticationFailed,
    MalformedPayload,
    RequestIdMismatch,
    InvalidPayload,
    InvalidTimestamp,
    RecordTooLarge,
    InputTooLarge,
    LegacyTooLarge,
    MalformedLegacy,
    MigrationVerificationFailed
}

internal enum class ResumeStorageReason {
    IoFailure,
    PointerFailure,
    LegacyDeletionFailure,
    SerializationFailure,
    KeyFailure,
    KeyUnavailable
}

internal data class SealedResumePayload(
    val iv: ByteArray,
    val ciphertext: ByteArray
)

/** Injectable only for JVM tests; production uses the AndroidKeyStore implementation. */
internal interface ResumeCrypto {
    fun seal(plaintext: ByteArray, aad: ByteArray, allowKeyCreation: Boolean): SealedResumePayload
    fun open(payload: SealedResumePayload, aad: ByteArray): ByteArray
}

private class AndroidKeystoreResumeCrypto : ResumeCrypto {
    override fun seal(
        plaintext: ByteArray,
        aad: ByteArray,
        allowKeyCreation: Boolean
    ): SealedResumePayload {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getKey(allowKeyCreation))
        cipher.updateAAD(aad)
        return SealedResumePayload(cipher.iv, cipher.doFinal(plaintext))
    }

    override fun open(payload: SealedResumePayload, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getKey(createIfMissing = false),
            GCMParameterSpec(GCM_TAG_BITS, payload.iv)
        )
        cipher.updateAAD(aad)
        return cipher.doFinal(payload.ciphertext)
    }

    private fun getKey(createIfMissing: Boolean): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(ScanResumeStore.KEY_ALIAS, null) as? SecretKey)?.let { return it }
        if (!createIfMissing) {
            throw ResumeCryptoFailure(ResumeStorageReason.KeyUnavailable)
        }
        return try {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            generator.init(
                KeyGenParameterSpec.Builder(
                    ScanResumeStore.KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generator.generateKey()
        } catch (error: GeneralSecurityException) {
            throw ResumeCryptoFailure(ResumeStorageReason.KeyFailure, error)
        }
    }

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
