package io.dossier.app.domain.scanner

import java.util.UUID

/**
 * Durable state for one opaque background-scan generation.
 *
 * This record intentionally contains no identity input, WorkManager inputData,
 * or result payload.  The three UUIDs are references only; the encrypted
 * request/checkpoint and result stores remain separate concerns.
 */
enum class ScanLifecyclePhase {
    EnqueuePending,
    Enqueued,
    Running,
    /** Durable user intent to stop execution at a WorkManager boundary. */
    Pausing,
    /** WorkManager has stopped the exact owner and the checkpoint is retained. */
    Paused,
    CancelRequested,
    CancelFailed,
    Succeeded,
    Failed,
    Cancelled,
    CleanupPending;

    companion object {
        // Upper-case aliases keep integration call sites unambiguous while the
        // persisted names remain the contract's readable phase names.
        val ENQUEUE_PENDING: ScanLifecyclePhase = EnqueuePending
        val ENQUEUED: ScanLifecyclePhase = Enqueued
        val RUNNING: ScanLifecyclePhase = Running
        val PAUSING: ScanLifecyclePhase = Pausing
        val PAUSED: ScanLifecyclePhase = Paused
        val CANCEL_REQUESTED: ScanLifecyclePhase = CancelRequested
        val CANCEL_FAILED: ScanLifecyclePhase = CancelFailed
        val SUCCEEDED: ScanLifecyclePhase = Succeeded
        val FAILED: ScanLifecyclePhase = Failed
        val CANCELLED: ScanLifecyclePhase = Cancelled
        val CLEANUP_PENDING: ScanLifecyclePhase = CleanupPending
    }
}

/** Safe, non-sensitive failure identifiers allowed in durable lifecycle state. */
object ScanLifecycleErrors {
    const val CANCEL_REQUEST_FAILED = "CANCEL_REQUEST_FAILED"
    const val WORK_ENQUEUE_FAILED = "WORK_ENQUEUE_FAILED"
    const val RESULT_MISSING = "RESULT_MISSING"
    const val RESULT_MISMATCH = "RESULT_MISMATCH"
    const val WORK_FINISHED_WITHOUT_LIFECYCLE = "WORK_FINISHED_WITHOUT_LIFECYCLE"
    const val CHECKPOINT_MISSING = "CHECKPOINT_MISSING"
    const val CHECKPOINT_INVALID = "CHECKPOINT_INVALID"
    const val CHECKPOINT_STORAGE_FAILURE = "CHECKPOINT_STORAGE_FAILURE"
    const val LIFECYCLE_STORAGE_FAILURE = "LIFECYCLE_STORAGE_FAILURE"
    const val CLEANUP_FAILED = "CLEANUP_FAILED"
    const val CANCELLED = "CANCELLED"

    // Existing worker codes are included so the later manager integration can
    // persist its already-reviewed taxonomy without copying unsafe text.
    const val LEGACY_WORK_DATA_UNSUPPORTED = "LEGACY_WORK_DATA_UNSUPPORTED"
    const val MISSING_SECURE_REQUEST_REFERENCE = "MISSING_SECURE_REQUEST_REFERENCE"
    const val SECURE_REQUEST_RECORD_MISSING = "SECURE_REQUEST_RECORD_MISSING"
    const val SECURE_REQUEST_RECORD_EXPIRED = "SECURE_REQUEST_RECORD_EXPIRED"
    const val SECURE_REQUEST_RECORD_INVALID = "SECURE_REQUEST_RECORD_INVALID"
    const val SECURE_REQUEST_STORAGE_UNAVAILABLE = "SECURE_REQUEST_STORAGE_UNAVAILABLE"
    const val STALE_WORK_REQUEST = "STALE_WORK_REQUEST"
    const val SNAPSHOT_UNAVAILABLE = "SNAPSHOT_UNAVAILABLE"
    const val RESULT_PERSISTENCE_FAILED = "RESULT_PERSISTENCE_FAILED"
    const val SCAN_EXECUTION_FAILED = "SCAN_EXECUTION_FAILED"
    const val ACTIVE_MARKER_PERSISTENCE_FAILED = "ACTIVE_MARKER_PERSISTENCE_FAILED"
    const val WORK_ROW_MISSING = "WORK_ROW_MISSING"

    /** Immutable allowlist; callers cannot extend it with arbitrary text. */
    val SAFE_ERROR_CODES: Set<String> = setOf(
        CANCEL_REQUEST_FAILED,
        WORK_ENQUEUE_FAILED,
        RESULT_MISSING,
        RESULT_MISMATCH,
        WORK_FINISHED_WITHOUT_LIFECYCLE,
        CHECKPOINT_MISSING,
        CHECKPOINT_INVALID,
        CHECKPOINT_STORAGE_FAILURE,
        LIFECYCLE_STORAGE_FAILURE,
        CLEANUP_FAILED,
        CANCELLED,
        LEGACY_WORK_DATA_UNSUPPORTED,
        MISSING_SECURE_REQUEST_REFERENCE,
        SECURE_REQUEST_RECORD_MISSING,
        SECURE_REQUEST_RECORD_EXPIRED,
        SECURE_REQUEST_RECORD_INVALID,
        SECURE_REQUEST_STORAGE_UNAVAILABLE,
        STALE_WORK_REQUEST,
        SNAPSHOT_UNAVAILABLE,
        RESULT_PERSISTENCE_FAILED,
        SCAN_EXECUTION_FAILED,
        ACTIVE_MARKER_PERSISTENCE_FAILED,
        WORK_ROW_MISSING
    )

    internal fun isSafe(code: String?): Boolean = code != null && code in SAFE_ERROR_CODES
}

enum class ScanLifecycleValidationReason {
    InvalidOwnerId,
    InvalidRequestId,
    InvalidGeneration,
    InvalidTimestamp,
    IllegalResultCombination,
    UnsafeErrorCode,
    MissingErrorCode
}

sealed interface ScanLifecycleValidation {
    data object Valid : ScanLifecycleValidation
    data class Invalid(val reason: ScanLifecycleValidationReason) : ScanLifecycleValidation
}

/**
 * Immutable, generation-bound lifecycle record.  Construction fails closed;
 * persistence adapters can use [validateFields] before constructing a record.
 */
data class ScanLifecycleRecord(
    val ownerId: String,
    val requestId: String,
    val generation: String,
    val phase: ScanLifecyclePhase,
    val updatedAtEpochMillis: Long,
    val resultReady: Boolean,
    val errorCode: String? = null
) {
    init {
        when (val validation = validateFields(
            ownerId = ownerId,
            requestId = requestId,
            generation = generation,
            phase = phase,
            updatedAtEpochMillis = updatedAtEpochMillis,
            resultReady = resultReady,
            errorCode = errorCode
        )) {
            ScanLifecycleValidation.Valid -> Unit
            is ScanLifecycleValidation.Invalid ->
                throw IllegalArgumentException("Invalid scan lifecycle record: ${validation.reason}")
        }
    }

    companion object {
        fun validateFields(
            ownerId: String,
            requestId: String,
            generation: String,
            phase: ScanLifecyclePhase,
            updatedAtEpochMillis: Long,
            resultReady: Boolean,
            errorCode: String?
        ): ScanLifecycleValidation {
            if (!isCanonicalUuid(ownerId)) return ScanLifecycleValidation.Invalid(
                ScanLifecycleValidationReason.InvalidOwnerId
            )
            if (!isCanonicalUuid(requestId)) return ScanLifecycleValidation.Invalid(
                ScanLifecycleValidationReason.InvalidRequestId
            )
            if (!isCanonicalUuid(generation)) return ScanLifecycleValidation.Invalid(
                ScanLifecycleValidationReason.InvalidGeneration
            )
            if (updatedAtEpochMillis < 0L) return ScanLifecycleValidation.Invalid(
                ScanLifecycleValidationReason.InvalidTimestamp
            )
            if (errorCode != null && !ScanLifecycleErrors.isSafe(errorCode)) {
                return ScanLifecycleValidation.Invalid(ScanLifecycleValidationReason.UnsafeErrorCode)
            }

            val legalCombination = when (phase) {
                ScanLifecyclePhase.EnqueuePending,
                ScanLifecyclePhase.Enqueued -> !resultReady && errorCode == null
                // Pause is a durable intent/state, not a terminal failure.
                // A publication may win the race with cancellation, so both
                // phases deliberately retain resultReady without clearing it.
                ScanLifecyclePhase.Pausing,
                ScanLifecyclePhase.Paused -> errorCode == null
                // A result marker may already be durable when cancellation or
                // failure wins the race with publication.  Keep it visible so
                // cleanup can still remove the orphan result safely.
                ScanLifecyclePhase.CancelRequested -> errorCode == null
                // A worker publishes the opaque result marker while still
                // Running; the following MarkSucceeded transition seals it.
                ScanLifecyclePhase.Running -> errorCode == null
                ScanLifecyclePhase.CancelFailed -> errorCode != null
                ScanLifecyclePhase.Succeeded -> resultReady && errorCode == null
                ScanLifecyclePhase.Failed -> errorCode != null
                ScanLifecyclePhase.Cancelled -> errorCode == null
                // Cleanup can be entered from any terminal state.  A success
                // retains resultReady until its result is durably cleaned up.
                ScanLifecyclePhase.CleanupPending -> true
            }
            if (!legalCombination) {
                return ScanLifecycleValidation.Invalid(ScanLifecycleValidationReason.IllegalResultCombination)
            }
            if ((phase == ScanLifecyclePhase.CancelFailed || phase == ScanLifecyclePhase.Failed) &&
                errorCode == null
            ) {
                return ScanLifecycleValidation.Invalid(ScanLifecycleValidationReason.MissingErrorCode)
            }
            return ScanLifecycleValidation.Valid
        }

        internal fun isCanonicalUuid(value: String): Boolean =
            runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
    }
}

/** Events accepted by the pure lifecycle reducer. */
sealed interface ScanLifecycleTransition {
    data object MarkEnqueued : ScanLifecycleTransition
    data object MarkRunning : ScanLifecycleTransition
    /** Records pause intent before asking WorkManager to stop the owner. */
    data object RequestPause : ScanLifecycleTransition
    /** Commits Paused only after exact WorkManager cancellation/absence. */
    data object MarkPaused : ScanLifecycleTransition
    data object RequestCancel : ScanLifecycleTransition
    data class MarkCancelFailed(val errorCode: String = ScanLifecycleErrors.CANCEL_REQUEST_FAILED) :
        ScanLifecycleTransition
    data object MarkCancelled : ScanLifecycleTransition
    data class MarkFailed(val errorCode: String) : ScanLifecycleTransition
    /** Publishes the opaque result marker while the worker is still Running. */
    data object PublishResult : ScanLifecycleTransition
    /** Moves Running to Succeeded after result publication. */
    data object MarkSucceeded : ScanLifecycleTransition
    /**
     * Crash-recovery transition used only after reconciliation verifies both
     * an exact-id WorkInfo.SUCCEEDED row and an encrypted result owned by that
     * same WorkManager UUID.
     */
    data object RecoverSucceeded : ScanLifecycleTransition
    data object BeginCleanup : ScanLifecycleTransition

    companion object {
        val Enqueued: ScanLifecycleTransition = MarkEnqueued
        val Running: ScanLifecycleTransition = MarkRunning
        val Pausing: ScanLifecycleTransition = RequestPause
        val Paused: ScanLifecycleTransition = MarkPaused
        val CancelRequested: ScanLifecycleTransition = RequestCancel
        val Cancelled: ScanLifecycleTransition = MarkCancelled
        val Succeeded: ScanLifecycleTransition = MarkSucceeded
        val CleanupPending: ScanLifecycleTransition = BeginCleanup
    }
}

enum class ScanLifecycleRejectionReason {
    StaleGeneration,
    StaleOwner,
    StaleRequest,
    InvalidExpectation,
    TimestampRegression,
    IllegalTransition,
    ResultNotPublished,
    UnsafeErrorCode,
    MissingErrorCode
}

sealed interface ScanLifecycleTransitionResult {
    val record: ScanLifecycleRecord

    data class Applied(override val record: ScanLifecycleRecord) : ScanLifecycleTransitionResult
    /** A stale callback is deliberately a pure no-op and retains the record. */
    data class Stale(
        override val record: ScanLifecycleRecord,
        val reason: ScanLifecycleRejectionReason
    ) : ScanLifecycleTransitionResult

    data class Rejected(
        override val record: ScanLifecycleRecord,
        val reason: ScanLifecycleRejectionReason
    ) : ScanLifecycleTransitionResult
}

/** Pure generation/owner/request compare-and-transition reducer. */
object ScanLifecycleReducer {
    fun reduce(
        current: ScanLifecycleRecord,
        expectedGeneration: String,
        transition: ScanLifecycleTransition,
        nowEpochMillis: Long,
        expectedOwnerId: String? = null,
        expectedRequestId: String? = null
    ): ScanLifecycleTransitionResult {
        if (!ScanLifecycleRecord.isCanonicalUuid(expectedGeneration)) {
            return ScanLifecycleTransitionResult.Stale(
                current,
                ScanLifecycleRejectionReason.InvalidExpectation
            )
        }
        if (expectedGeneration != current.generation) {
            return ScanLifecycleTransitionResult.Stale(
                current,
                ScanLifecycleRejectionReason.StaleGeneration
            )
        }
        if (expectedOwnerId != null && expectedOwnerId != current.ownerId) {
            return ScanLifecycleTransitionResult.Stale(current, ScanLifecycleRejectionReason.StaleOwner)
        }
        if (expectedRequestId != null && expectedRequestId != current.requestId) {
            return ScanLifecycleTransitionResult.Stale(current, ScanLifecycleRejectionReason.StaleRequest)
        }
        if (nowEpochMillis < current.updatedAtEpochMillis) {
            return ScanLifecycleTransitionResult.Rejected(
                current,
                ScanLifecycleRejectionReason.TimestampRegression
            )
        }

        fun applied(
            phase: ScanLifecyclePhase = current.phase,
            resultReady: Boolean = current.resultReady,
            errorCode: String? = null
        ): ScanLifecycleTransitionResult {
            val next = try {
                ScanLifecycleRecord(
                    ownerId = current.ownerId,
                    requestId = current.requestId,
                    generation = current.generation,
                    phase = phase,
                    updatedAtEpochMillis = nowEpochMillis,
                    resultReady = resultReady,
                    errorCode = errorCode
                )
            } catch (_: IllegalArgumentException) {
                return ScanLifecycleTransitionResult.Rejected(
                    current,
                    ScanLifecycleRejectionReason.IllegalTransition
                )
            }
            return ScanLifecycleTransitionResult.Applied(next)
        }

        fun reject(reason: ScanLifecycleRejectionReason) =
            ScanLifecycleTransitionResult.Rejected(current, reason)

        return when (transition) {
            ScanLifecycleTransition.MarkEnqueued -> when (current.phase) {
                ScanLifecyclePhase.EnqueuePending -> applied(ScanLifecyclePhase.Enqueued)
                ScanLifecyclePhase.Enqueued -> applied(ScanLifecyclePhase.Enqueued, errorCode = null)
                else -> reject(ScanLifecycleRejectionReason.IllegalTransition)
            }

            ScanLifecycleTransition.MarkRunning -> when (current.phase) {
                // WorkManager may start the row before the asynchronous enqueue
                // Operation callback advances EnqueuePending to Enqueued.
                ScanLifecyclePhase.EnqueuePending,
                ScanLifecyclePhase.Enqueued -> applied(ScanLifecyclePhase.Running)
                // An already-running callback is harmless and idempotent.
                ScanLifecyclePhase.Running -> applied(ScanLifecyclePhase.Running, current.resultReady)
                else -> reject(ScanLifecycleRejectionReason.IllegalTransition)
            }

            ScanLifecycleTransition.RequestPause -> when (current.phase) {
                ScanLifecyclePhase.EnqueuePending,
                ScanLifecyclePhase.Enqueued,
                ScanLifecyclePhase.Running -> applied(ScanLifecyclePhase.Pausing)
                // A repeated pause callback is idempotent and must not clear a
                // result marker published while the first request was in flight.
                ScanLifecyclePhase.Pausing -> applied(ScanLifecyclePhase.Pausing)
                else -> reject(ScanLifecycleRejectionReason.IllegalTransition)
            }

            ScanLifecycleTransition.MarkPaused -> when (current.phase) {
                ScanLifecyclePhase.Pausing -> applied(
                    phase = ScanLifecyclePhase.Paused,
                    resultReady = current.resultReady,
                    errorCode = null
                )
                ScanLifecyclePhase.Paused -> applied(
                    phase = ScanLifecyclePhase.Paused,
                    resultReady = current.resultReady,
                    errorCode = null
                )
                else -> reject(ScanLifecycleRejectionReason.IllegalTransition)
            }

            ScanLifecycleTransition.RequestCancel -> when (current.phase) {
                ScanLifecyclePhase.EnqueuePending,
                ScanLifecyclePhase.Enqueued,
                ScanLifecyclePhase.Running,
                ScanLifecyclePhase.Pausing,
                ScanLifecyclePhase.Paused,
                ScanLifecyclePhase.CancelFailed -> applied(ScanLifecyclePhase.CancelRequested)
                ScanLifecyclePhase.CancelRequested -> applied(ScanLifecyclePhase.CancelRequested)
                else -> reject(ScanLifecycleRejectionReason.IllegalTransition)
            }

            is ScanLifecycleTransition.MarkCancelFailed -> {
                if (!ScanLifecycleErrors.isSafe(transition.errorCode)) {
                    reject(ScanLifecycleRejectionReason.UnsafeErrorCode)
                } else if (current.phase == ScanLifecyclePhase.CancelRequested) {
                    applied(ScanLifecyclePhase.CancelFailed, errorCode = transition.errorCode)
                } else {
                    reject(ScanLifecycleRejectionReason.IllegalTransition)
                }
            }

            ScanLifecycleTransition.MarkCancelled -> when (current.phase) {
                // A terminal WorkInfo.CANCELLED is authoritative even when the
                // cancellation originated outside Dossier's manager.
                ScanLifecyclePhase.EnqueuePending,
                ScanLifecyclePhase.Enqueued,
                ScanLifecyclePhase.Running,
                ScanLifecyclePhase.Pausing,
                ScanLifecyclePhase.CancelRequested,
                ScanLifecyclePhase.CancelFailed -> applied(
                    ScanLifecyclePhase.Cancelled,
                    errorCode = null
                )
                else -> reject(ScanLifecycleRejectionReason.IllegalTransition)
            }

            is ScanLifecycleTransition.MarkFailed -> {
                when {
                    !ScanLifecycleErrors.isSafe(transition.errorCode) ->
                        reject(ScanLifecycleRejectionReason.UnsafeErrorCode)
                    current.phase in setOf(
                        ScanLifecyclePhase.EnqueuePending,
                        ScanLifecyclePhase.Enqueued,
                        ScanLifecyclePhase.Running,
                        ScanLifecyclePhase.Pausing,
                        ScanLifecyclePhase.CancelRequested,
                        ScanLifecyclePhase.CancelFailed
                    ) -> applied(
                        ScanLifecyclePhase.Failed,
                        errorCode = transition.errorCode
                    )
                    else -> reject(ScanLifecycleRejectionReason.IllegalTransition)
                }
            }

            ScanLifecycleTransition.PublishResult -> {
                if (current.phase != ScanLifecyclePhase.Running &&
                    current.phase != ScanLifecyclePhase.Pausing
                ) {
                    reject(ScanLifecycleRejectionReason.IllegalTransition)
                } else if (current.resultReady) {
                    // A duplicate publication callback cannot change the
                    // generation or publish from a terminal/cancelled state.
                    applied(current.phase, resultReady = true, errorCode = null)
                } else {
                    applied(current.phase, resultReady = true, errorCode = null)
                }
            }

            ScanLifecycleTransition.MarkSucceeded -> {
                when {
                    current.phase != ScanLifecyclePhase.Running &&
                        current.phase != ScanLifecyclePhase.Pausing ->
                        reject(ScanLifecycleRejectionReason.IllegalTransition)
                    !current.resultReady ->
                        reject(ScanLifecycleRejectionReason.ResultNotPublished)
                    else -> applied(ScanLifecyclePhase.Succeeded, resultReady = true, errorCode = null)
                }
            }

            ScanLifecycleTransition.RecoverSucceeded -> when (current.phase) {
                ScanLifecyclePhase.EnqueuePending,
                ScanLifecyclePhase.Enqueued,
                ScanLifecyclePhase.Running,
                ScanLifecyclePhase.Pausing,
                ScanLifecyclePhase.Paused,
                ScanLifecyclePhase.CancelRequested,
                ScanLifecyclePhase.CancelFailed -> applied(
                    ScanLifecyclePhase.Succeeded,
                    resultReady = true,
                    errorCode = null
                )
                ScanLifecyclePhase.Succeeded -> applied(
                    ScanLifecyclePhase.Succeeded,
                    resultReady = true,
                    errorCode = null
                )
                else -> reject(ScanLifecycleRejectionReason.IllegalTransition)
            }

            ScanLifecycleTransition.BeginCleanup -> when (current.phase) {
                ScanLifecyclePhase.Succeeded,
                ScanLifecyclePhase.Failed,
                ScanLifecyclePhase.Cancelled,
                // Paused owns no active WorkManager execution, so an
                // explicit purge may retire its retained checkpoint/result.
                ScanLifecyclePhase.Paused -> applied(
                    ScanLifecyclePhase.CleanupPending,
                    resultReady = current.resultReady,
                    errorCode = current.errorCode
                )
                ScanLifecyclePhase.CleanupPending -> applied(
                    ScanLifecyclePhase.CleanupPending,
                    resultReady = current.resultReady,
                    errorCode = current.errorCode
                )
                else -> reject(ScanLifecycleRejectionReason.IllegalTransition)
            }
        }
    }
}

/** WorkManager state projection that deliberately excludes inputData/outputData. */
enum class ScanWorkState {
    Enqueued,
    Running,
    Blocked,
    Succeeded,
    Failed,
    Cancelled;

    companion object {
        val ENQUEUED: ScanWorkState = Enqueued
        val RUNNING: ScanWorkState = Running
        val BLOCKED: ScanWorkState = Blocked
        val SUCCEEDED: ScanWorkState = Succeeded
        val FAILED: ScanWorkState = Failed
        val CANCELLED: ScanWorkState = Cancelled
    }
}

/** Exact WorkInfo summary used by reconciliation; no unique-work list ordering. */
data class ScanWorkInfoSummary(
    val id: String,
    val state: ScanWorkState
)

/**
 * Typed result of looking up one exact WorkManager UUID.
 *
 * Missing is authoritative absence.  Unavailable means the lookup could not
 * establish either presence or absence, so reconciliation must retry without
 * mutating lifecycle state or re-enqueuing work.
 */
sealed interface ScanWorkInfoLookup {
    data class Available(val summary: ScanWorkInfoSummary) : ScanWorkInfoLookup
    data object Missing : ScanWorkInfoLookup
    data object Unavailable : ScanWorkInfoLookup

    companion object {
        val MISSING: ScanWorkInfoLookup = Missing
        val UNAVAILABLE: ScanWorkInfoLookup = Unavailable
    }
}

typealias ScanWorkInfoLookupResult = ScanWorkInfoLookup

enum class ScanCheckpointAvailability {
    Available,
    Missing,
    Invalid,
    StorageFailure;

    companion object {
        val AVAILABLE: ScanCheckpointAvailability = Available
        val MISSING: ScanCheckpointAvailability = Missing
        val INVALID: ScanCheckpointAvailability = Invalid
        val STORAGE_FAILURE: ScanCheckpointAvailability = StorageFailure
    }
}

sealed interface ScanReconciliationAction {
    /** There is no persisted owner, so no WorkInfo may be adopted. */
    data object DoNotAdopt : ScanReconciliationAction

    /**
     * An exact WorkManager lookup for a canonical legacy owner was
     * unavailable before a generation-bound lifecycle record could be
     * published.  Startup must retry without reading or mutating checkpoint
     * state; unlike [RetryReconciliation], there is deliberately no invented
     * lifecycle snapshot to bind here.
     */
    data object RetryLegacyLookup : ScanReconciliationAction

    /** Every mutating/reconciliation action is bound to the exact snapshot it observed. */
    sealed interface Bound : ScanReconciliationAction {
        val expected: ScanLifecycleRecord
        val ownerId: String get() = expected.ownerId
        val resultReady: Boolean get() = expected.resultReady
    }

    data class ReenqueueSameUuid(override val expected: ScanLifecycleRecord) : Bound
    data class KeepOrRecover(
        override val expected: ScanLifecycleRecord,
        val state: ScanWorkState
    ) : Bound
    /** WorkManager is still active while a durable pause request is pending. */
    data class RetryPause(override val expected: ScanLifecycleRecord) : Bound
    /** Exact owner is gone/cancelled; commit Paused through the reducer. */
    data class PausedTerminal(override val expected: ScanLifecycleRecord) : Bound
    /** Paused work is intentionally inert until an explicit resume call. */
    data class KeepPaused(override val expected: ScanLifecycleRecord) : Bound
    data class RetryCancellation(override val expected: ScanLifecycleRecord) : Bound
    data class RetryReconciliation(override val expected: ScanLifecycleRecord) : Bound
    /** Persist RecoverSucceeded before any terminal cleanup is attempted. */
    data class RecoverSucceeded(override val expected: ScanLifecycleRecord) : Bound
    data class CompleteCleanup(override val expected: ScanLifecycleRecord) : Bound
    data class TruthfulFailurePreserve(
        override val expected: ScanLifecycleRecord,
        val errorCode: String,
        val resultWorkId: String?
    ) : Bound
    data class FailedTerminal(
        override val expected: ScanLifecycleRecord,
        val errorCode: String
    ) : Bound
    data class CancelledTerminal(override val expected: ScanLifecycleRecord) : Bound
    data class FailNoRetry(
        override val expected: ScanLifecycleRecord,
        val errorCode: String
    ) : Bound
    data class CleanupTerminal(
        override val expected: ScanLifecycleRecord,
        val phase: ScanLifecyclePhase
    ) : Bound
}

object ScanLifecycleReconciler {
    fun plan(
        lifecycle: ScanLifecycleRecord?,
        workInfo: ScanWorkInfoLookup,
        checkpoint: ScanCheckpointAvailability,
        resultWorkId: String?
    ): ScanReconciliationAction {
        // A missing owner is never inferred from a WorkInfo row.  This is the
        // key guard against adopting another generation from unique work.
        lifecycle ?: return ScanReconciliationAction.DoNotAdopt

        // An unavailable lookup proves neither presence nor absence.  It must
        // never mutate the lifecycle or enqueue work based on an assumption.
        if (workInfo is ScanWorkInfoLookup.Unavailable) {
            return ScanReconciliationAction.RetryReconciliation(lifecycle)
        }

        val work = (workInfo as? ScanWorkInfoLookup.Available)?.summary

        // A present but different row is not the requested generation.  Do
        // not use list order or state to adopt it.
        if (work != null && work.id != lifecycle.ownerId) {
            return ScanReconciliationAction.DoNotAdopt
        }

        // A matching encrypted result is stronger than a cancellation race:
        // it proves this exact owner published before WorkManager disappeared.
        // Promote it to Succeeded rather than ever hiding or replacing it.
        if (lifecycle.phase in setOf(ScanLifecyclePhase.Pausing, ScanLifecyclePhase.Paused) &&
            resultWorkId == lifecycle.ownerId
        ) {
            return ScanReconciliationAction.RecoverSucceeded(lifecycle)
        }

        // Pausing/Paused are intentionally checkpoint-tolerant.  A pause
        // retains encrypted resume state and does not turn a missing
        // checkpoint into a failure until an explicit resume is attempted.
        if (lifecycle.phase == ScanLifecyclePhase.Paused) {
            return when (work?.state) {
                ScanWorkState.Enqueued,
                ScanWorkState.Running,
                ScanWorkState.Blocked -> ScanReconciliationAction.RetryPause(lifecycle)
                else -> ScanReconciliationAction.KeepPaused(lifecycle)
            }
        }

        // While pausing, terminal WorkManager cancellation is the only point
        // at which Paused may be committed. Active work must be cancelled
        // again; a failed/finished work row flows through the ordinary
        // terminal handling below.
        if (lifecycle.phase == ScanLifecyclePhase.Pausing &&
            (workInfo is ScanWorkInfoLookup.Missing || work?.state == ScanWorkState.Cancelled)
        ) {
            return ScanReconciliationAction.PausedTerminal(lifecycle)
        }
        if (lifecycle.phase == ScanLifecyclePhase.Pausing &&
            work?.state in setOf(ScanWorkState.Enqueued, ScanWorkState.Running, ScanWorkState.Blocked)
        ) {
            return ScanReconciliationAction.RetryPause(lifecycle)
        }

        when (lifecycle.phase) {
            ScanLifecyclePhase.Succeeded -> {
                return when {
                    resultWorkId == lifecycle.ownerId ->
                        ScanReconciliationAction.CompleteCleanup(lifecycle)
                    resultWorkId == null -> ScanReconciliationAction.TruthfulFailurePreserve(
                        lifecycle,
                        ScanLifecycleErrors.RESULT_MISSING,
                        null
                    )
                    else -> ScanReconciliationAction.TruthfulFailurePreserve(
                        lifecycle,
                        ScanLifecycleErrors.RESULT_MISMATCH,
                        resultWorkId
                    )
                }
            }

            ScanLifecyclePhase.Failed ->
                return ScanReconciliationAction.FailedTerminal(
                    lifecycle,
                    lifecycle.errorCode ?: ScanLifecycleErrors.WORK_FINISHED_WITHOUT_LIFECYCLE
                )

            ScanLifecyclePhase.Cancelled ->
                return ScanReconciliationAction.CancelledTerminal(lifecycle)

            ScanLifecyclePhase.CleanupPending ->
                return ScanReconciliationAction.CleanupTerminal(lifecycle, lifecycle.phase)

            else -> Unit
        }

        // WorkManager terminal state is authoritative and must not be masked
        // by a missing/expired checkpoint that is no longer needed to execute.
        when (work?.state) {
            ScanWorkState.Succeeded -> {
                return when {
                    resultWorkId == lifecycle.ownerId ->
                        ScanReconciliationAction.RecoverSucceeded(lifecycle)
                    resultWorkId == null -> ScanReconciliationAction.TruthfulFailurePreserve(
                        lifecycle,
                        ScanLifecycleErrors.RESULT_MISSING,
                        null
                    )
                    else -> ScanReconciliationAction.TruthfulFailurePreserve(
                        lifecycle,
                        ScanLifecycleErrors.RESULT_MISMATCH,
                        resultWorkId
                    )
                }
            }
            ScanWorkState.Failed -> return ScanReconciliationAction.FailedTerminal(
                lifecycle,
                lifecycle.errorCode ?: ScanLifecycleErrors.WORK_FINISHED_WITHOUT_LIFECYCLE
            )
            ScanWorkState.Cancelled -> return ScanReconciliationAction.CancelledTerminal(lifecycle)
            else -> Unit
        }

        // Durable cancellation intent does not depend on the execution
        // checkpoint remaining readable. Never turn a requested cancellation
        // into a checkpoint failure or resurrect it as runnable work.
        if (lifecycle.phase == ScanLifecyclePhase.CancelRequested ||
            lifecycle.phase == ScanLifecyclePhase.CancelFailed
        ) {
            return when {
                workInfo is ScanWorkInfoLookup.Missing && lifecycle.phase == ScanLifecyclePhase.CancelRequested ->
                    ScanReconciliationAction.CancelledTerminal(lifecycle)
                workInfo is ScanWorkInfoLookup.Missing && lifecycle.phase == ScanLifecyclePhase.CancelFailed ->
                    ScanReconciliationAction.FailNoRetry(
                        lifecycle,
                        ScanLifecycleErrors.WORK_FINISHED_WITHOUT_LIFECYCLE
                    )
                work != null -> ScanReconciliationAction.RetryCancellation(lifecycle)
                else -> ScanReconciliationAction.DoNotAdopt
            }
        }

        // Authoritative absence can only resurrect an EnqueuePending row.  A
        // row already marked Enqueued/Running is a truthful missing-work
        // failure, never an implicit new enqueue.
        if (workInfo is ScanWorkInfoLookup.Missing) {
            return when (lifecycle.phase) {
                ScanLifecyclePhase.EnqueuePending -> when (checkpoint) {
                    ScanCheckpointAvailability.Available ->
                        ScanReconciliationAction.ReenqueueSameUuid(lifecycle)
                    ScanCheckpointAvailability.Missing -> ScanReconciliationAction.FailNoRetry(
                        lifecycle,
                        ScanLifecycleErrors.CHECKPOINT_MISSING
                    )
                    ScanCheckpointAvailability.Invalid -> ScanReconciliationAction.FailNoRetry(
                        lifecycle,
                        ScanLifecycleErrors.CHECKPOINT_INVALID
                    )
                    ScanCheckpointAvailability.StorageFailure -> ScanReconciliationAction.FailNoRetry(
                        lifecycle,
                        ScanLifecycleErrors.CHECKPOINT_STORAGE_FAILURE
                    )
                }
                ScanLifecyclePhase.Enqueued,
                ScanLifecyclePhase.Running -> ScanReconciliationAction.FailNoRetry(
                    lifecycle,
                    ScanLifecycleErrors.WORK_ROW_MISSING
                )
                else -> ScanReconciliationAction.DoNotAdopt
            }
        }

        // An active/pending record cannot be resumed without a valid encrypted
        // checkpoint.  These are typed, non-retryable failures; callers must
        // preserve the evidence and surface the limitation.
        when (checkpoint) {
            ScanCheckpointAvailability.Available -> Unit
            ScanCheckpointAvailability.Missing ->
                return ScanReconciliationAction.FailNoRetry(
                    lifecycle,
                    ScanLifecycleErrors.CHECKPOINT_MISSING
                )
            ScanCheckpointAvailability.Invalid ->
                return ScanReconciliationAction.FailNoRetry(
                    lifecycle,
                    ScanLifecycleErrors.CHECKPOINT_INVALID
                )
            ScanCheckpointAvailability.StorageFailure ->
                return ScanReconciliationAction.FailNoRetry(
                    lifecycle,
                    ScanLifecycleErrors.CHECKPOINT_STORAGE_FAILURE
                )
        }

        return when (work?.state) {
            ScanWorkState.Enqueued,
            ScanWorkState.Running,
            ScanWorkState.Blocked -> {
                if (lifecycle.phase == ScanLifecyclePhase.CancelRequested ||
                    lifecycle.phase == ScanLifecyclePhase.CancelFailed
                ) {
                    ScanReconciliationAction.RetryCancellation(lifecycle)
                } else {
                    ScanReconciliationAction.KeepOrRecover(lifecycle, work.state)
                }
            }

            // These duplicate the earlier terminal handling so malformed future
            // call paths remain fail-closed instead of throwing in production.
            ScanWorkState.Succeeded -> when {
                resultWorkId == lifecycle.ownerId ->
                    ScanReconciliationAction.RecoverSucceeded(lifecycle)
                resultWorkId == null -> ScanReconciliationAction.TruthfulFailurePreserve(
                    lifecycle,
                    ScanLifecycleErrors.RESULT_MISSING,
                    null
                )
                else -> ScanReconciliationAction.TruthfulFailurePreserve(
                    lifecycle,
                    ScanLifecycleErrors.RESULT_MISMATCH,
                    resultWorkId
                )
            }
            ScanWorkState.Failed -> ScanReconciliationAction.FailedTerminal(
                lifecycle,
                lifecycle.errorCode ?: ScanLifecycleErrors.WORK_FINISHED_WITHOUT_LIFECYCLE
            )
            ScanWorkState.Cancelled -> ScanReconciliationAction.CancelledTerminal(lifecycle)
            null -> ScanReconciliationAction.DoNotAdopt
        }
    }
}
