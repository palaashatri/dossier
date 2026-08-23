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
        saveDetailed(input, deepResearch) is ResumeWriteState.Saved

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

    internal fun saveDetailed(
        input: IdentityInput,
        deepResearch: Boolean
    ): ResumeWriteState = synchronized(STORE_LOCK) {
        return try {
            val inputIssue = validateInput(input)
            if (inputIssue != null) return ResumeWriteState.Invalid(inputIssue)

            val id = idFactory()
            if (!isValidRequestId(id)) return ResumeWriteState.Invalid(ResumeInvalidReason.InvalidRequestId)

            val now = nowMillis()
            val expiresAt = expiresAt(now)
            val point = ResumePoint(
                requestId = id,
                input = input,
                deepResearch = deepResearch,
                scanMode = DiscoveryScanPreferences.selectedMode.value,
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
                scanMode = point.scanMode
            )

            persistRecord(record)
            // A newly submitted request supersedes any legacy plaintext marker.
            deleteLegacyOrThrow()
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

    /** Typed state for callers that need to distinguish absence from corruption. */
    internal fun loadDetailed(): ResumeReadState = synchronized(STORE_LOCK) {
        return try {
            when (val migration = migrateLegacyIfNeeded()) {
                LegacyMigration.None -> loadActiveRecord()
                is LegacyMigration.Migrated -> loadRecord(migration.requestId)
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
            if (recordsDir.exists()) {
                val files = recordsDir.listFiles()
                if (files == null) {
                    failures += ResumeStorageReason.IoFailure
                } else {
                    files.filter { it.name.endsWith(RECORD_EXTENSION) || it.name.endsWith(".$TEMP_EXTENSION") }
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
            try {
                deleteFileDurably(pointerFile, ResumeStorageReason.PointerFailure)
            } catch (error: ResumeCryptoFailure) {
                failures += error.reason
            } catch (_: Exception) {
                failures += ResumeStorageReason.PointerFailure
            }
            try {
                deleteLegacyOrThrow()
            } catch (error: ResumeCryptoFailure) {
                failures += error.reason
            } catch (_: Exception) {
                failures += ResumeStorageReason.LegacyDeletionFailure
            }
            if (!legacyFile.exists()) {
                try {
                    removeEmptyLegacyDirectory()
                } catch (error: ResumeCryptoFailure) {
                    failures += error.reason
                } catch (_: Exception) {
                    failures += ResumeStorageReason.LegacyDeletionFailure
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
        val pointer = readPointer()
            ?: return recoverOrphanRecord() ?: ResumeReadState.Missing
        if (!isValidRequestId(pointer)) {
            return cleanupPointerFailureOr(ResumeInvalidReason.InvalidRequestId)
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
        try {
            deleteFileDurably(recordFile(requestId), ResumeStorageReason.IoFailure)
        } catch (error: ResumeCryptoFailure) {
            failure = error.reason
        } catch (_: Exception) {
            failure = ResumeStorageReason.IoFailure
        }
        try {
            deleteFileDurably(pointerFile, ResumeStorageReason.PointerFailure)
        } catch (error: ResumeCryptoFailure) {
            failure = failure ?: error.reason
        } catch (_: Exception) {
            failure = failure ?: ResumeStorageReason.PointerFailure
        }
        return failure
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
                createdAtEpochMillis = record.createdAtEpochMillis,
                expiresAtEpochMillis = record.expiresAtEpochMillis
            )
        )
    }

    private fun persistRecord(record: ResumeRecord) {
        val priorPointer = readPointer()
        val priorPointerBytes = if (priorPointer != null && isValidRequestId(priorPointer)) {
            pointerFile.readBounded(MAX_POINTER_BYTES)
        } else {
            null
        }
        val priorRecordBackup = if (priorPointer != null && isValidRequestId(priorPointer)) {
            val priorFile = recordFile(priorPointer)
            if (priorFile.exists()) {
                PriorRecordBackup(
                    file = priorFile,
                    bytes = priorFile.readBounded(MAX_ENVELOPE_BYTES),
                    pointerBytes = priorPointerBytes ?: priorPointer.toByteArray(Charsets.UTF_8)
                )
            } else {
                null
            }
        } else {
            null
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
        val target = recordFile(record.requestId)
        val encodedEnvelope = json.encodeToString(envelope).toByteArray(Charsets.UTF_8)
        if (encodedEnvelope.size > MAX_ENVELOPE_BYTES) {
            throw ResumeInvalidOperation(ResumeInvalidReason.RecordTooLarge)
        }
        val targetBackup = if (target.exists()) target.readBounded(MAX_ENVELOPE_BYTES) else null
        try {
            atomicWrite(target, encodedEnvelope)
        } catch (error: Exception) {
            val rollbackFailure = restoreFile(target, targetBackup, ResumeStorageReason.IoFailure)
            val failure = ResumeCryptoFailure(ResumeStorageReason.IoFailure, error)
            rollbackFailure?.let(failure::addSuppressed)
            throw failure
        }

        try {
            atomicWrite(pointerFile, record.requestId.toByteArray(Charsets.UTF_8))
        } catch (error: Exception) {
            val rollbackFailures = mutableListOf<Throwable>()
            try {
                if (priorPointer != null) {
                    atomicWrite(pointerFile, priorPointer.toByteArray(Charsets.UTF_8))
                } else {
                    deleteFileDurably(pointerFile, ResumeStorageReason.PointerFailure)
                }
            } catch (rollback: Throwable) {
                rollbackFailures += rollback
            }
            restoreFile(target, targetBackup, ResumeStorageReason.PointerFailure)
                ?.let(rollbackFailures::add)
            val failure = ResumeCryptoFailure(ResumeStorageReason.PointerFailure, error)
            rollbackFailures.forEach(failure::addSuppressed)
            throw failure
        }
        if (priorRecordBackup != null && priorRecordBackup.file.name != target.name) {
            try {
                deleteFileDurably(priorRecordBackup.file, ResumeStorageReason.IoFailure)
            } catch (error: Exception) {
                val rollbackFailures = mutableListOf<Throwable>()
                try {
                    atomicWrite(priorRecordBackup.file, priorRecordBackup.bytes)
                } catch (rollback: Throwable) {
                    rollbackFailures += rollback
                }
                try {
                    atomicWrite(pointerFile, priorRecordBackup.pointerBytes)
                } catch (rollback: Throwable) {
                    rollbackFailures += rollback
                }
                restoreFile(target, targetBackup, ResumeStorageReason.IoFailure)
                    ?.let(rollbackFailures::add)
                val failure = ResumeCryptoFailure(ResumeStorageReason.IoFailure, error)
                rollbackFailures.forEach(failure::addSuppressed)
                throw failure
            }
        }
    }

    private fun deleteLegacyAndReturn(result: LegacyMigration): LegacyMigration {
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
        if (!legacyFile.exists()) return LegacyMigration.None

        val currentPointer = try {
            readPointer()
        } catch (error: ResumeCryptoFailure) {
            return deleteLegacyAndReturn(LegacyMigration.StorageFailure(error.reason))
        } catch (_: Exception) {
            return deleteLegacyAndReturn(LegacyMigration.StorageFailure(ResumeStorageReason.IoFailure))
        }
        if (currentPointer != null && isValidRequestId(currentPointer)) {
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
        var failure: ResumeStorageReason? = null
        val pointer = try {
            readPointer()
        } catch (error: ResumeCryptoFailure) {
            failure = error.reason
            null
        } catch (_: Exception) {
            failure = ResumeStorageReason.IoFailure
            null
        }
        if (pointer == requestId) {
            try {
                deleteFileDurably(pointerFile, ResumeStorageReason.PointerFailure)
            } catch (error: ResumeCryptoFailure) {
                failure = failure ?: error.reason
            } catch (_: Exception) {
                failure = failure ?: ResumeStorageReason.PointerFailure
            }
        }
        try {
            deleteFileDurably(recordFile(requestId), ResumeStorageReason.IoFailure)
        } catch (error: ResumeCryptoFailure) {
            failure = failure ?: error.reason
        } catch (_: Exception) {
            failure = failure ?: ResumeStorageReason.IoFailure
        }
        return failure
    }

    private fun recoverOrphanRecord(): ResumeReadState? {
        val candidates = encryptedRecordFiles().sortedByDescending { it.lastModified() }
        for (file in candidates) {
            val requestId = file.name.removeSuffix(RECORD_EXTENSION)
            if (!isValidRequestId(requestId)) continue
            when (val state = loadRecord(requestId)) {
                is ResumeReadState.Available -> {
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
        return null
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
        return bytes.toString(Charsets.UTF_8).trim().ifBlank { null }
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

    private fun ensureDirectory(dir: File) {
        if (dir.exists()) {
            if (!dir.isDirectory) throw IOException("Resume path is not a directory")
            return
        }
        if (!dir.mkdirs() && !dir.isDirectory) {
            throw IOException("Unable to create resume directory")
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
        }
    }

    private fun deleteFileDurably(file: File, reason: ResumeStorageReason) {
        if (!file.exists()) return
        if (!file.delete()) {
            throw ResumeCryptoFailure(reason)
        }
        file.parentFile?.let {
            try {
                dirSyncer.sync(it)
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
        return try {
            if (previousBytes == null) {
                deleteFileDurably(target, reason)
            } else {
                atomicWrite(target, previousBytes)
            }
            null
        } catch (error: Throwable) {
            error
        }
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
        val scanMode: ScanMode
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
        const val TEMP_EXTENSION = "tmp"
        const val RECORDS_DIRECTORY = "dossier_scan_checkpoints"
        const val LEGACY_DIRECTORY = "dossier_resume"
        const val LEGACY_FILE_NAME = "dossier_resume.json"
        const val POINTER_FILE_NAME = "active_checkpoint.id"
        const val KEY_ALIAS = "dossier-scan-resume-v1"
        private val REQUEST_ID_PATTERN =
            Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    }

    private val pointerFile: File
        get() = File(recordsDir, POINTER_FILE_NAME)

    private val legacyFile: File
        get() = File(legacyDir, LEGACY_FILE_NAME)
}

internal data class ResumePoint(
    val requestId: String,
    val input: IdentityInput,
    val deepResearch: Boolean,
    val scanMode: ScanMode,
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
