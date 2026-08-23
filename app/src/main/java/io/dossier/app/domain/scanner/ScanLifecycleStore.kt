package io.dossier.app.domain.scanner

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

enum class ScanLifecycleStoreInvalidReason {
    PartialRecord,
    UnknownPhase,
    InvalidRecord,
    InvalidRequestId,
    InvalidGeneration,
    InvalidLegacyOwner,
    InvalidTimestamp,
    StorageReadFailure
}

sealed interface ScanLifecycleReadResult {
    data object Missing : ScanLifecycleReadResult
    data class Available(val record: ScanLifecycleRecord) : ScanLifecycleReadResult
    data class Invalid(val reason: ScanLifecycleStoreInvalidReason) : ScanLifecycleReadResult
    data object StorageFailure : ScanLifecycleReadResult
}

sealed interface ScanLifecycleWriteResult {
    data object Saved : ScanLifecycleWriteResult
    data object Cleared : ScanLifecycleWriteResult
    data object Missing : ScanLifecycleWriteResult
    data object GenerationMismatch : ScanLifecycleWriteResult
    data object RecordMismatch : ScanLifecycleWriteResult
    data class TransitionRejected(val reason: ScanLifecycleRejectionReason) : ScanLifecycleWriteResult
    data object AlreadyPresent : ScanLifecycleWriteResult
    data object NoLegacyOwner : ScanLifecycleWriteResult
    /** SharedPreferences.Editor.commit() returned false; no async apply is used. */
    data object CommitFailed : ScanLifecycleWriteResult
    data class Invalid(val reason: ScanLifecycleStoreInvalidReason) : ScanLifecycleWriteResult
    data object StorageFailure : ScanLifecycleWriteResult
}

/**
 * Synchronous SharedPreferences adapter for the opaque lifecycle record.
 *
 * This store has no access to WorkManager inputData and never serializes
 * identity seeds.  The whole record is written in one [SharedPreferences]
 * commit, and all transition/clear operations are exact-record scoped and
 * generation-bound.
 */
internal class ScanLifecycleStore internal constructor(
    private val preferences: SharedPreferences,
    private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
    private val generationFactory: () -> String = { UUID.randomUUID().toString() }
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    fun read(): ScanLifecycleReadResult = synchronized(STORE_LOCK) {
        readLocked()
    }

    /** Alias for integrations that use load terminology. */
    fun load(): ScanLifecycleReadResult = read()

    /**
     * Publishes all lifecycle fields atomically and removes both legacy
     * markers in the same commit.  A false commit is surfaced distinctly.
     */
    fun publish(record: ScanLifecycleRecord): ScanLifecycleWriteResult = synchronized(STORE_LOCK) {
        when (val current = readLocked()) {
            ScanLifecycleReadResult.Missing -> publishLocked(record)
            is ScanLifecycleReadResult.Available -> ScanLifecycleWriteResult.AlreadyPresent
            is ScanLifecycleReadResult.Invalid -> ScanLifecycleWriteResult.Invalid(current.reason)
            ScanLifecycleReadResult.StorageFailure -> ScanLifecycleWriteResult.StorageFailure
        }
    }

    /** Alias for callers that call the operation save. */
    fun save(record: ScanLifecycleRecord): ScanLifecycleWriteResult = publish(record)

    /**
     * Applies one reducer transition to the exact expected lifecycle record.
     *
     * The store never accepts an arbitrary replacement record.  The reducer
     * owns legal phase changes and derives the next immutable record from the
     * current one, while the exact snapshot check protects same-generation
     * callbacks (including same-millisecond transitions).
     */
    fun transition(
        expected: ScanLifecycleRecord,
        transition: ScanLifecycleTransition,
        nowEpochMillis: Long
    ): ScanLifecycleWriteResult = synchronized(STORE_LOCK) {
        when (val current = readLocked()) {
            ScanLifecycleReadResult.Missing -> ScanLifecycleWriteResult.Missing
            is ScanLifecycleReadResult.Invalid -> ScanLifecycleWriteResult.Invalid(current.reason)
            ScanLifecycleReadResult.StorageFailure -> ScanLifecycleWriteResult.StorageFailure
            is ScanLifecycleReadResult.Available -> when {
                current.record.generation != expected.generation -> ScanLifecycleWriteResult.GenerationMismatch
                current.record != expected -> ScanLifecycleWriteResult.RecordMismatch
                else -> when (val reduced = ScanLifecycleReducer.reduce(
                    current = current.record,
                    expectedGeneration = expected.generation,
                    expectedOwnerId = expected.ownerId,
                    expectedRequestId = expected.requestId,
                    transition = transition,
                    nowEpochMillis = nowEpochMillis
                )) {
                    is ScanLifecycleTransitionResult.Applied -> publishLocked(reduced.record)
                    is ScanLifecycleTransitionResult.Stale ->
                        ScanLifecycleWriteResult.TransitionRejected(reduced.reason)
                    is ScanLifecycleTransitionResult.Rejected ->
                        ScanLifecycleWriteResult.TransitionRejected(reduced.reason)
                }
            }
        }
    }

    /**
     * Compare-and-clear for one exact lifecycle record.
     *
     * Cleanup callbacks must retain the full record they observed.  A
     * generation-only clear could erase a newer transition for that same
     * generation, including one written in the same millisecond.
     */
    @SuppressLint("ApplySharedPref", "UseKtx")
    fun clear(expected: ScanLifecycleRecord): ScanLifecycleWriteResult = synchronized(STORE_LOCK) {
        when (val current = readLocked()) {
            ScanLifecycleReadResult.Missing -> ScanLifecycleWriteResult.Missing
            is ScanLifecycleReadResult.Invalid -> ScanLifecycleWriteResult.Invalid(current.reason)
            ScanLifecycleReadResult.StorageFailure -> ScanLifecycleWriteResult.StorageFailure
            is ScanLifecycleReadResult.Available -> {
                when {
                    current.record.generation != expected.generation ->
                        ScanLifecycleWriteResult.GenerationMismatch
                    current.record != expected -> ScanLifecycleWriteResult.RecordMismatch
                    else -> {
                        val committed = try {
                            preferences.edit()
                                .remove(KEY_FORMAT_VERSION)
                                .remove(KEY_OWNER_ID)
                                .remove(KEY_REQUEST_ID)
                                .remove(KEY_GENERATION)
                                .remove(KEY_PHASE)
                                .remove(KEY_UPDATED_AT)
                                .remove(KEY_RESULT_READY)
                                .remove(KEY_ERROR_CODE)
                                .remove(KEY_LEGACY_ACTIVE_OWNER)
                                .remove(KEY_LEGACY_ACTIVE)
                                .commit()
                        } catch (_: RuntimeException) {
                            false
                        }
                        if (committed) ScanLifecycleWriteResult.Cleared
                        else ScanLifecycleWriteResult.CommitFailed
                    }
                }
            }
        }
    }

    /**
     * Migrates only the canonical legacy owner marker and a caller-provided
     * canonical request reference.  The caller is responsible for having
     * loaded that request from the encrypted checkpoint store; this method
     * never decodes it or any WorkManager data.
     */
    fun migrateLegacyActiveOwner(
        currentEncryptedRequestId: String,
        generation: String = generationFactory(),
        updatedAtEpochMillis: Long = nowEpochMillis()
    ): ScanLifecycleWriteResult = synchronized(STORE_LOCK) {
        if (!ScanLifecycleRecord.isCanonicalUuid(currentEncryptedRequestId)) {
            return@synchronized ScanLifecycleWriteResult.Invalid(
                ScanLifecycleStoreInvalidReason.InvalidRequestId
            )
        }
        if (!ScanLifecycleRecord.isCanonicalUuid(generation)) {
            return@synchronized ScanLifecycleWriteResult.Invalid(
                ScanLifecycleStoreInvalidReason.InvalidGeneration
            )
        }
        if (updatedAtEpochMillis < 0L) {
            return@synchronized ScanLifecycleWriteResult.Invalid(
                ScanLifecycleStoreInvalidReason.InvalidTimestamp
            )
        }

        when (val current = readLocked()) {
            is ScanLifecycleReadResult.Available -> return@synchronized ScanLifecycleWriteResult.AlreadyPresent
            is ScanLifecycleReadResult.Invalid -> return@synchronized ScanLifecycleWriteResult.Invalid(current.reason)
            ScanLifecycleReadResult.StorageFailure -> return@synchronized ScanLifecycleWriteResult.StorageFailure
            ScanLifecycleReadResult.Missing -> Unit
        }

        val legacyOwner = try {
            preferences.getString(KEY_LEGACY_ACTIVE_OWNER, null)
        } catch (_: RuntimeException) {
            return@synchronized ScanLifecycleWriteResult.StorageFailure
        }
        if (legacyOwner == null) {
            val hasLegacyBoolean = try {
                preferences.contains(KEY_LEGACY_ACTIVE)
            } catch (_: RuntimeException) {
                return@synchronized ScanLifecycleWriteResult.StorageFailure
            }
            if (!hasLegacyBoolean) return@synchronized ScanLifecycleWriteResult.NoLegacyOwner
            val committed = try {
                preferences.edit().remove(KEY_LEGACY_ACTIVE).commit()
            } catch (_: RuntimeException) {
                false
            }
            return@synchronized if (committed) ScanLifecycleWriteResult.Cleared
            else ScanLifecycleWriteResult.CommitFailed
        }
        if (!ScanLifecycleRecord.isCanonicalUuid(legacyOwner)) {
            return@synchronized ScanLifecycleWriteResult.Invalid(
                ScanLifecycleStoreInvalidReason.InvalidLegacyOwner
            )
        }

        val record = try {
            ScanLifecycleRecord(
                ownerId = legacyOwner,
                requestId = currentEncryptedRequestId,
                generation = generation,
                phase = ScanLifecyclePhase.Enqueued,
                updatedAtEpochMillis = updatedAtEpochMillis,
                resultReady = false,
                errorCode = null
            )
        } catch (_: IllegalArgumentException) {
            return@synchronized ScanLifecycleWriteResult.Invalid(
                ScanLifecycleStoreInvalidReason.InvalidRecord
            )
        }
        publishLocked(record)
    }

    /** Migration spelling used by lifecycle bootstrap code. */
    fun migrateLegacyIfNeeded(
        currentEncryptedRequestId: String,
        generation: String = generationFactory(),
        updatedAtEpochMillis: Long = nowEpochMillis()
    ): ScanLifecycleWriteResult = migrateLegacyActiveOwner(
        currentEncryptedRequestId,
        generation,
        updatedAtEpochMillis
    )

    private fun readLocked(): ScanLifecycleReadResult {
        val hasAnyField = try {
            LIFECYCLE_KEYS.any(preferences::contains)
        } catch (_: RuntimeException) {
            return ScanLifecycleReadResult.StorageFailure
        }
        if (!hasAnyField) return ScanLifecycleReadResult.Missing

        return try {
            val formatVersion = preferences.getInt(KEY_FORMAT_VERSION, -1)
            val ownerId = preferences.getString(KEY_OWNER_ID, null)
            val requestId = preferences.getString(KEY_REQUEST_ID, null)
            val generation = preferences.getString(KEY_GENERATION, null)
            val phaseName = preferences.getString(KEY_PHASE, null)
            val updatedAt = preferences.getLong(KEY_UPDATED_AT, Long.MIN_VALUE)
            val resultReady = preferences.getBoolean(KEY_RESULT_READY, false)
            val errorCode = preferences.getString(KEY_ERROR_CODE, null)
            if (formatVersion != FORMAT_VERSION ||
                ownerId == null || requestId == null || generation == null || phaseName == null ||
                updatedAt == Long.MIN_VALUE || !preferences.contains(KEY_RESULT_READY)
            ) {
                return ScanLifecycleReadResult.Invalid(ScanLifecycleStoreInvalidReason.PartialRecord)
            }
            val phase = runCatching { ScanLifecyclePhase.valueOf(phaseName) }
                .getOrElse {
                    return ScanLifecycleReadResult.Invalid(ScanLifecycleStoreInvalidReason.UnknownPhase)
                }
            when (val validation = ScanLifecycleRecord.validateFields(
                ownerId = ownerId,
                requestId = requestId,
                generation = generation,
                phase = phase,
                updatedAtEpochMillis = updatedAt,
                resultReady = resultReady,
                errorCode = errorCode
            )) {
                ScanLifecycleValidation.Valid -> ScanLifecycleReadResult.Available(
                    ScanLifecycleRecord(
                        ownerId = ownerId,
                        requestId = requestId,
                        generation = generation,
                        phase = phase,
                        updatedAtEpochMillis = updatedAt,
                        resultReady = resultReady,
                        errorCode = errorCode
                    )
                )
                is ScanLifecycleValidation.Invalid ->
                    ScanLifecycleReadResult.Invalid(ScanLifecycleStoreInvalidReason.InvalidRecord)
            }
        } catch (_: ClassCastException) {
            ScanLifecycleReadResult.Invalid(ScanLifecycleStoreInvalidReason.InvalidRecord)
        } catch (_: RuntimeException) {
            ScanLifecycleReadResult.StorageFailure
        }
    }

    @SuppressLint("ApplySharedPref", "UseKtx")
    private fun publishLocked(record: ScanLifecycleRecord): ScanLifecycleWriteResult {
        return try {
            val committed = preferences.edit()
                .putInt(KEY_FORMAT_VERSION, FORMAT_VERSION)
                .putString(KEY_OWNER_ID, record.ownerId)
                .putString(KEY_REQUEST_ID, record.requestId)
                .putString(KEY_GENERATION, record.generation)
                .putString(KEY_PHASE, record.phase.name)
                .putLong(KEY_UPDATED_AT, record.updatedAtEpochMillis)
                .putBoolean(KEY_RESULT_READY, record.resultReady)
                .putString(KEY_ERROR_CODE, record.errorCode)
                .remove(KEY_LEGACY_ACTIVE_OWNER)
                .remove(KEY_LEGACY_ACTIVE)
                .commit()
            if (committed) ScanLifecycleWriteResult.Saved
            else ScanLifecycleWriteResult.CommitFailed
        } catch (_: RuntimeException) {
            ScanLifecycleWriteResult.StorageFailure
        }
    }

    private companion object {
        const val PREFS_NAME = "dossier-background-work"
        const val FORMAT_VERSION = 1
        const val KEY_FORMAT_VERSION = "scan_lifecycle_format_version"
        const val KEY_OWNER_ID = "scan_lifecycle_owner_id"
        const val KEY_REQUEST_ID = "scan_lifecycle_request_id"
        const val KEY_GENERATION = "scan_lifecycle_generation"
        const val KEY_PHASE = "scan_lifecycle_phase"
        const val KEY_UPDATED_AT = "scan_lifecycle_updated_at"
        const val KEY_RESULT_READY = "scan_lifecycle_result_ready"
        const val KEY_ERROR_CODE = "scan_lifecycle_error_code"
        // Existing marker names are intentionally kept opaque and only
        // removed during an atomic lifecycle publication/clear.
        const val KEY_LEGACY_ACTIVE_OWNER = "active_owner"
        const val KEY_LEGACY_ACTIVE = "active"
        val LIFECYCLE_KEYS = setOf(
            KEY_FORMAT_VERSION,
            KEY_OWNER_ID,
            KEY_REQUEST_ID,
            KEY_GENERATION,
            KEY_PHASE,
            KEY_UPDATED_AT,
            KEY_RESULT_READY,
            KEY_ERROR_CODE
        )
        val STORE_LOCK = Any()
    }
}

/** Descriptive alias for callers that want the Android backing explicit. */
internal typealias AndroidScanLifecycleStore = ScanLifecycleStore
