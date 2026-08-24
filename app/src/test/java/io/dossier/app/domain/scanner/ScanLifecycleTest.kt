package io.dossier.app.domain.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ScanLifecycleTest {
    private val owner = "11111111-1111-4111-8111-111111111111"
    private val request = "22222222-2222-4222-8222-222222222222"
    private val generation = "33333333-3333-4333-8333-333333333333"
    private val otherGeneration = "44444444-4444-4444-8444-444444444444"
    private val otherOwner = "55555555-5555-4555-8555-555555555555"

    private fun work(
        state: ScanWorkState,
        id: String = owner
    ) = ScanWorkInfoLookup.Available(ScanWorkInfoSummary(id, state))

    private fun record(
        phase: ScanLifecyclePhase = ScanLifecyclePhase.Running,
        updatedAt: Long = 100L,
        resultReady: Boolean = phase == ScanLifecyclePhase.Succeeded,
        errorCode: String? = when (phase) {
            ScanLifecyclePhase.Failed -> ScanLifecycleErrors.SCAN_EXECUTION_FAILED
            ScanLifecyclePhase.CancelFailed -> ScanLifecycleErrors.CANCEL_REQUEST_FAILED
            else -> null
        }
    ) = ScanLifecycleRecord(
        ownerId = owner,
        requestId = request,
        generation = generation,
        phase = phase,
        updatedAtEpochMillis = updatedAt,
        resultReady = resultReady,
        errorCode = errorCode
    )

    @Test
    fun missingOwnerNeverAdoptsWorkInfo() {
        assertEquals(
            ScanReconciliationAction.DoNotAdopt,
            ScanLifecycleReconciler.plan(
                lifecycle = null,
                workInfo = work(ScanWorkState.Running, otherOwner),
                checkpoint = ScanCheckpointAvailability.Available,
                resultWorkId = otherOwner
            )
        )
    }

    @Test
    fun pendingMissingRowWithCheckpointReenqueuesTheSameUuid() {
        val pending = record(ScanLifecyclePhase.EnqueuePending, resultReady = false)
        val action = ScanLifecycleReconciler.plan(
            lifecycle = pending,
            workInfo = ScanWorkInfoLookup.Missing,
            checkpoint = ScanCheckpointAvailability.Available,
            resultWorkId = null
        )
        assertEquals(ScanReconciliationAction.ReenqueueSameUuid(pending), action)
    }

    @Test
    fun missingWorkRowOnlyReenqueuesPendingAndFailsActiveRows() {
        val pending = record(ScanLifecyclePhase.EnqueuePending, resultReady = false)
        val enqueued = record(ScanLifecyclePhase.Enqueued, resultReady = false)
        val running = record(ScanLifecyclePhase.Running, resultReady = false)

        assertEquals(
            ScanReconciliationAction.ReenqueueSameUuid(pending),
            ScanLifecycleReconciler.plan(
                lifecycle = pending,
                workInfo = ScanWorkInfoLookup.Missing,
                checkpoint = ScanCheckpointAvailability.Available,
                resultWorkId = null
            )
        )
        assertEquals(
            ScanReconciliationAction.FailNoRetry(enqueued, ScanLifecycleErrors.WORK_ROW_MISSING),
            ScanLifecycleReconciler.plan(
                lifecycle = enqueued,
                workInfo = ScanWorkInfoLookup.Missing,
                checkpoint = ScanCheckpointAvailability.Available,
                resultWorkId = null
            )
        )
        assertEquals(
            ScanReconciliationAction.FailNoRetry(running, ScanLifecycleErrors.WORK_ROW_MISSING),
            ScanLifecycleReconciler.plan(
                lifecycle = running,
                workInfo = ScanWorkInfoLookup.Missing,
                checkpoint = ScanCheckpointAvailability.Available,
                resultWorkId = null
            )
        )
    }

    @Test
    fun unavailableWorkInfoReturnsBoundRetryWithoutMutationOrReenqueue() {
        val expected = record(ScanLifecyclePhase.Running, resultReady = true)
        val action = ScanLifecycleReconciler.plan(
            lifecycle = expected,
            workInfo = ScanWorkInfoLookup.Unavailable,
            checkpoint = ScanCheckpointAvailability.Missing,
            resultWorkId = null
        )
        assertEquals(ScanReconciliationAction.RetryReconciliation(expected), action)
        assertTrue(action is ScanReconciliationAction.Bound)
        assertEquals(expected, (action as ScanReconciliationAction.Bound).expected)
        assertTrue(action !is ScanReconciliationAction.ReenqueueSameUuid)
    }

    @Test
    fun reconciliationActionsRemainBoundToThePlannedSnapshot() {
        val pending = record(ScanLifecyclePhase.EnqueuePending, resultReady = false)
        val planned = ScanLifecycleReconciler.plan(
            lifecycle = pending,
            workInfo = ScanWorkInfoLookup.Missing,
            checkpoint = ScanCheckpointAvailability.Available,
            resultWorkId = null
        ) as ScanReconciliationAction.Bound
        val advanced = ScanLifecycleReducer.reduce(
            current = pending,
            expectedGeneration = generation,
            transition = ScanLifecycleTransition.MarkRunning,
            nowEpochMillis = 101L
        ) as ScanLifecycleTransitionResult.Applied

        assertEquals(pending, planned.expected)
        assertTrue(planned.expected != advanced.record)
        assertEquals(owner, planned.ownerId)
    }

    @Test
    fun activeExactRowKeepsOrRecoversWithoutListOrdering() {
        for (state in listOf(ScanWorkState.Enqueued, ScanWorkState.Running, ScanWorkState.Blocked)) {
            assertEquals(
                ScanReconciliationAction.KeepOrRecover(record(), state),
                ScanLifecycleReconciler.plan(
                    lifecycle = record(),
                    workInfo = work(state),
                    checkpoint = ScanCheckpointAvailability.Available,
                    resultWorkId = null
                )
            )
        }
        assertEquals(
            ScanReconciliationAction.DoNotAdopt,
            ScanLifecycleReconciler.plan(
                lifecycle = record(),
                workInfo = work(ScanWorkState.Running, otherOwner),
                checkpoint = ScanCheckpointAvailability.Available,
                resultWorkId = null
            )
        )
    }

    @Test
    fun pendingWorkerCanClaimBeforeEnqueueCallback() {
        val pending = record(ScanLifecyclePhase.EnqueuePending, resultReady = false)
        val transition = ScanLifecycleReducer.reduce(
            current = pending,
            expectedGeneration = generation,
            expectedOwnerId = owner,
            expectedRequestId = request,
            transition = ScanLifecycleTransition.MarkRunning,
            nowEpochMillis = 101L
        ) as ScanLifecycleTransitionResult.Applied

        assertEquals(ScanLifecyclePhase.Running, transition.record.phase)
    }

    @Test
    fun durableCancelIntentNeverReenqueuesAndRetriesOnlyWhileRowIsActive() {
        val cancelling = record(ScanLifecyclePhase.CancelRequested, resultReady = false)
        for (checkpoint in ScanCheckpointAvailability.entries) {
            for (state in listOf(ScanWorkState.Enqueued, ScanWorkState.Running, ScanWorkState.Blocked)) {
                assertEquals(
                    ScanReconciliationAction.RetryCancellation(cancelling),
                    ScanLifecycleReconciler.plan(
                        lifecycle = cancelling,
                        workInfo = work(state),
                        checkpoint = checkpoint,
                        resultWorkId = null
                    )
                )
            }
            assertEquals(
                    ScanReconciliationAction.CancelledTerminal(cancelling),
                ScanLifecycleReconciler.plan(
                    lifecycle = cancelling,
                    workInfo = ScanWorkInfoLookup.Missing,
                    checkpoint = checkpoint,
                    resultWorkId = null
                )
            )
        }

        val cancelFailed = record(
            ScanLifecyclePhase.CancelFailed,
            resultReady = true
        )
        assertEquals(
            ScanReconciliationAction.FailNoRetry(
                cancelFailed,
                ScanLifecycleErrors.WORK_FINISHED_WITHOUT_LIFECYCLE
            ),
            ScanLifecycleReconciler.plan(
                lifecycle = cancelFailed,
                workInfo = ScanWorkInfoLookup.Missing,
                checkpoint = ScanCheckpointAvailability.Available,
                resultWorkId = null
            )
        )
    }

    @Test
    fun externalTerminalCancellationCanCloseAnyActivePhase() {
        for (phase in listOf(
            ScanLifecyclePhase.EnqueuePending,
            ScanLifecyclePhase.Enqueued,
            ScanLifecyclePhase.Running,
            ScanLifecyclePhase.CancelRequested,
            ScanLifecyclePhase.CancelFailed
        )) {
            val current = record(phase, resultReady = false)
            val transition = ScanLifecycleReducer.reduce(
                current = current,
                expectedGeneration = generation,
                transition = ScanLifecycleTransition.MarkCancelled,
                nowEpochMillis = 101L
            ) as ScanLifecycleTransitionResult.Applied
            assertEquals(ScanLifecyclePhase.Cancelled, transition.record.phase)
        }
    }

    @Test
    fun terminalWorkFailureCanCloseCancellationPhasesTruthfully() {
        for (phase in listOf(
            ScanLifecyclePhase.CancelRequested,
            ScanLifecyclePhase.CancelFailed
        )) {
            val current = record(phase, resultReady = false)
            val transition = ScanLifecycleReducer.reduce(
                current = current,
                expectedGeneration = generation,
                transition = ScanLifecycleTransition.MarkFailed(
                    ScanLifecycleErrors.WORK_FINISHED_WITHOUT_LIFECYCLE
                ),
                nowEpochMillis = 101L
            ) as ScanLifecycleTransitionResult.Applied

            assertEquals(ScanLifecyclePhase.Failed, transition.record.phase)
            assertEquals(
                ScanLifecycleErrors.WORK_FINISHED_WITHOUT_LIFECYCLE,
                transition.record.errorCode
            )
        }
    }

    @Test
    fun terminalWorkInfoTakesPrecedenceOverCheckpointFailure() {
        val active = record(ScanLifecyclePhase.Running, resultReady = false)
        assertEquals(
            ScanReconciliationAction.FailedTerminal(
                active,
                ScanLifecycleErrors.WORK_FINISHED_WITHOUT_LIFECYCLE
            ),
            ScanLifecycleReconciler.plan(
                lifecycle = active,
                workInfo = work(ScanWorkState.Failed),
                checkpoint = ScanCheckpointAvailability.StorageFailure,
                resultWorkId = null
            )
        )
        assertEquals(
            ScanReconciliationAction.CancelledTerminal(active),
            ScanLifecycleReconciler.plan(
                lifecycle = active,
                workInfo = work(ScanWorkState.Cancelled),
                checkpoint = ScanCheckpointAvailability.Missing,
                resultWorkId = null
            )
        )
        assertEquals(
            ScanReconciliationAction.RecoverSucceeded(active),
            ScanLifecycleReconciler.plan(
                lifecycle = active,
                workInfo = work(ScanWorkState.Succeeded),
                checkpoint = ScanCheckpointAvailability.Invalid,
                resultWorkId = owner
            )
        )
    }

    @Test
    fun recoveredWorkManagerSuccessIsDurableBeforeCleanup() {
        val active = record(ScanLifecyclePhase.EnqueuePending, resultReady = false)
        val action = ScanLifecycleReconciler.plan(
            lifecycle = active,
            workInfo = work(ScanWorkState.Succeeded),
            checkpoint = ScanCheckpointAvailability.Missing,
            resultWorkId = owner
        )
        assertEquals(ScanReconciliationAction.RecoverSucceeded(active), action)

        val recovered = ScanLifecycleReducer.reduce(
            current = active,
            expectedGeneration = generation,
            expectedOwnerId = owner,
            expectedRequestId = request,
            transition = ScanLifecycleTransition.RecoverSucceeded,
            nowEpochMillis = 101L
        ) as ScanLifecycleTransitionResult.Applied
        assertEquals(ScanLifecyclePhase.Succeeded, recovered.record.phase)
        assertTrue(recovered.record.resultReady)

        assertEquals(
            ScanReconciliationAction.CompleteCleanup(recovered.record),
            ScanLifecycleReconciler.plan(
                lifecycle = recovered.record,
                workInfo = work(ScanWorkState.Succeeded),
                checkpoint = ScanCheckpointAvailability.Missing,
                resultWorkId = owner
            )
        )
    }

    @Test
    fun succeededMatchingResultCompletesCleanup() {
        val succeeded = record(ScanLifecyclePhase.Succeeded)
        assertEquals(
            ScanReconciliationAction.CompleteCleanup(succeeded),
            ScanLifecycleReconciler.plan(
                lifecycle = succeeded,
                workInfo = work(ScanWorkState.Succeeded),
                checkpoint = ScanCheckpointAvailability.Available,
                resultWorkId = owner
            )
        )
    }

    @Test
    fun succeededMissingOrMismatchedResultIsTruthfulAndPreserved() {
        val succeeded = record(ScanLifecyclePhase.Succeeded)
        val missing = ScanLifecycleReconciler.plan(
            lifecycle = succeeded,
            workInfo = ScanWorkInfoLookup.Missing,
            checkpoint = ScanCheckpointAvailability.Available,
            resultWorkId = null
        )
        val mismatch = ScanLifecycleReconciler.plan(
            lifecycle = succeeded,
            workInfo = ScanWorkInfoLookup.Missing,
            checkpoint = ScanCheckpointAvailability.Available,
            resultWorkId = otherOwner
        )
        assertEquals(
            ScanReconciliationAction.TruthfulFailurePreserve(
                succeeded,
                ScanLifecycleErrors.RESULT_MISSING,
                null
            ),
            missing
        )
        assertEquals(
            ScanReconciliationAction.TruthfulFailurePreserve(
                succeeded,
                ScanLifecycleErrors.RESULT_MISMATCH,
                otherOwner
            ),
            mismatch
        )
    }

    @Test
    fun failedAndCancelledRemainDistinct() {
        val failed = record(ScanLifecyclePhase.Failed, resultReady = true)
        val cancelled = record(ScanLifecyclePhase.Cancelled, resultReady = true)
        val failedAction = ScanLifecycleReconciler.plan(
            lifecycle = failed,
            workInfo = work(ScanWorkState.Failed),
            checkpoint = ScanCheckpointAvailability.Missing,
            resultWorkId = null
        )
        assertEquals(
            ScanReconciliationAction.FailedTerminal(
                failed,
                ScanLifecycleErrors.SCAN_EXECUTION_FAILED
            ),
            failedAction
        )
        val cancelledAction = ScanLifecycleReconciler.plan(
            lifecycle = cancelled,
            workInfo = work(ScanWorkState.Cancelled),
            checkpoint = ScanCheckpointAvailability.Missing,
            resultWorkId = null
        )
        assertEquals(
            ScanReconciliationAction.CancelledTerminal(cancelled),
            cancelledAction
        )
        assertTrue((failedAction as ScanReconciliationAction.Bound).resultReady)
        assertTrue((cancelledAction as ScanReconciliationAction.Bound).resultReady)
    }

    @Test
    fun checkpointFailuresAreTypedAndNonRetryable() {
        val active = record()
        for ((availability, code) in listOf(
            ScanCheckpointAvailability.Missing to ScanLifecycleErrors.CHECKPOINT_MISSING,
            ScanCheckpointAvailability.Invalid to ScanLifecycleErrors.CHECKPOINT_INVALID,
            ScanCheckpointAvailability.StorageFailure to ScanLifecycleErrors.CHECKPOINT_STORAGE_FAILURE
        )) {
            assertEquals(
                ScanReconciliationAction.FailNoRetry(active, code),
                ScanLifecycleReconciler.plan(
                    lifecycle = active,
                    workInfo = work(ScanWorkState.Running),
                    checkpoint = availability,
                    resultWorkId = null
                )
            )
        }
    }

    @Test
    fun cleanupPendingIsIdempotentTerminalAction() {
        val cleanup = record(ScanLifecyclePhase.CleanupPending, resultReady = false)
        assertEquals(
            ScanReconciliationAction.CleanupTerminal(cleanup, ScanLifecyclePhase.CleanupPending),
            ScanLifecycleReconciler.plan(
                lifecycle = cleanup,
                workInfo = ScanWorkInfoLookup.Missing,
                checkpoint = ScanCheckpointAvailability.Invalid,
                resultWorkId = null
            )
        )
    }

    @Test
    fun staleGenerationOwnerAndRequestCallbacksAreNoOps() {
        val current = record()
        val staleGeneration = ScanLifecycleReducer.reduce(
            current = current,
            expectedGeneration = otherGeneration,
            transition = ScanLifecycleTransition.MarkRunning,
            nowEpochMillis = 101L
        )
        val staleOwner = ScanLifecycleReducer.reduce(
            current = current,
            expectedGeneration = generation,
            expectedOwnerId = otherOwner,
            transition = ScanLifecycleTransition.MarkRunning,
            nowEpochMillis = 101L
        )
        val staleRequest = ScanLifecycleReducer.reduce(
            current = current,
            expectedGeneration = generation,
            expectedRequestId = otherOwner,
            transition = ScanLifecycleTransition.MarkRunning,
            nowEpochMillis = 101L
        )
        assertTrue(staleGeneration is ScanLifecycleTransitionResult.Stale)
        assertTrue(staleOwner is ScanLifecycleTransitionResult.Stale)
        assertTrue(staleRequest is ScanLifecycleTransitionResult.Stale)
        assertEquals(current, staleGeneration.record)
        assertEquals(current, staleOwner.record)
        assertEquals(current, staleRequest.record)
    }

    @Test
    fun resultPublicationOnlyComesFromRunning() {
        val published = ScanLifecycleReducer.reduce(
            current = record(),
            expectedGeneration = generation,
            transition = ScanLifecycleTransition.PublishResult,
            nowEpochMillis = 101L
        ) as ScanLifecycleTransitionResult.Applied
        assertTrue(published.record.resultReady)
        assertEquals(ScanLifecyclePhase.Running, published.record.phase)

        val succeeded = ScanLifecycleReducer.reduce(
            current = published.record,
            expectedGeneration = generation,
            transition = ScanLifecycleTransition.MarkSucceeded,
            nowEpochMillis = 102L
        ) as ScanLifecycleTransitionResult.Applied
        assertEquals(ScanLifecyclePhase.Succeeded, succeeded.record.phase)

        val terminalPublication = ScanLifecycleReducer.reduce(
            current = succeeded.record,
            expectedGeneration = generation,
            transition = ScanLifecycleTransition.PublishResult,
            nowEpochMillis = 103L
        )
        assertEquals(
            ScanLifecycleRejectionReason.IllegalTransition,
            (terminalPublication as ScanLifecycleTransitionResult.Rejected).reason
        )

        val cancelled = record(ScanLifecyclePhase.Cancelled, resultReady = false)
        val cancelledPublication = ScanLifecycleReducer.reduce(
            current = cancelled,
            expectedGeneration = generation,
            transition = ScanLifecycleTransition.PublishResult,
            nowEpochMillis = 101L
        )
        assertTrue(cancelledPublication is ScanLifecycleTransitionResult.Rejected)
    }

    @Test
    fun publishedResultSurvivesCancellationAndFailureRacesForCleanup() {
        val running = record(ScanLifecyclePhase.Running, resultReady = false)
        val published = ScanLifecycleReducer.reduce(
            current = running,
            expectedGeneration = generation,
            transition = ScanLifecycleTransition.PublishResult,
            nowEpochMillis = 101L
        ) as ScanLifecycleTransitionResult.Applied

        val cancelRequested = ScanLifecycleReducer.reduce(
            current = published.record,
            expectedGeneration = generation,
            transition = ScanLifecycleTransition.RequestCancel,
            nowEpochMillis = 102L
        ) as ScanLifecycleTransitionResult.Applied
        assertTrue(cancelRequested.record.resultReady)

        val cancelled = ScanLifecycleReducer.reduce(
            current = cancelRequested.record,
            expectedGeneration = generation,
            transition = ScanLifecycleTransition.MarkCancelled,
            nowEpochMillis = 103L
        ) as ScanLifecycleTransitionResult.Applied
        assertTrue(cancelled.record.resultReady)

        val cancelledCleanup = ScanLifecycleReducer.reduce(
            current = cancelled.record,
            expectedGeneration = generation,
            transition = ScanLifecycleTransition.BeginCleanup,
            nowEpochMillis = 104L
        ) as ScanLifecycleTransitionResult.Applied
        assertTrue(cancelledCleanup.record.resultReady)

        val failed = ScanLifecycleReducer.reduce(
            current = published.record,
            expectedGeneration = generation,
            transition = ScanLifecycleTransition.MarkFailed(ScanLifecycleErrors.SCAN_EXECUTION_FAILED),
            nowEpochMillis = 102L
        ) as ScanLifecycleTransitionResult.Applied
        assertTrue(failed.record.resultReady)

        val failedCleanup = ScanLifecycleReducer.reduce(
            current = failed.record,
            expectedGeneration = generation,
            transition = ScanLifecycleTransition.BeginCleanup,
            nowEpochMillis = 103L
        ) as ScanLifecycleTransitionResult.Applied
        assertTrue(failedCleanup.record.resultReady)
    }

    @Test
    fun cleanupCanBeginFromEveryTerminalPhase() {
        for (phase in listOf(
            ScanLifecyclePhase.Succeeded,
            ScanLifecyclePhase.Failed,
            ScanLifecyclePhase.Cancelled
        )) {
            val terminal = record(phase, resultReady = true)
            val cleanup = ScanLifecycleReducer.reduce(
                current = terminal,
                expectedGeneration = generation,
                transition = ScanLifecycleTransition.BeginCleanup,
                nowEpochMillis = 101L
            ) as ScanLifecycleTransitionResult.Applied
            assertEquals(ScanLifecyclePhase.CleanupPending, cleanup.record.phase)
            assertTrue(cleanup.record.resultReady)
        }
    }

    @Test
    fun illegalTransitionsAndUnsafeErrorsAreRejected() {
        val directSuccess = ScanLifecycleReducer.reduce(
            current = record(),
            expectedGeneration = generation,
            transition = ScanLifecycleTransition.MarkSucceeded,
            nowEpochMillis = 101L
        )
        assertEquals(ScanLifecycleRejectionReason.ResultNotPublished, (directSuccess as ScanLifecycleTransitionResult.Rejected).reason)

        val terminalRunning = ScanLifecycleReducer.reduce(
            current = record(ScanLifecyclePhase.Failed),
            expectedGeneration = generation,
            transition = ScanLifecycleTransition.MarkRunning,
            nowEpochMillis = 101L
        )
        assertEquals(ScanLifecycleRejectionReason.IllegalTransition, (terminalRunning as ScanLifecycleTransitionResult.Rejected).reason)

        val unsafe = ScanLifecycleReducer.reduce(
            current = record(ScanLifecyclePhase.CancelRequested),
            expectedGeneration = generation,
            transition = ScanLifecycleTransition.MarkCancelFailed("java.lang.Exception: secret"),
            nowEpochMillis = 101L
        )
        assertEquals(ScanLifecycleRejectionReason.UnsafeErrorCode, (unsafe as ScanLifecycleTransitionResult.Rejected).reason)
    }

    @Test
    fun malformedRecordsAndIllegalResultCombinationsFailClosed() {
        assertTrue(
            ScanLifecycleRecord.validateFields(
                ownerId = "not-a-uuid",
                requestId = request,
                generation = generation,
                phase = ScanLifecyclePhase.Running,
                updatedAtEpochMillis = 1L,
                resultReady = false,
                errorCode = null
            ) is ScanLifecycleValidation.Invalid
        )
        assertTrue(
            ScanLifecycleRecord.validateFields(
                ownerId = owner,
                requestId = request,
                generation = generation,
                phase = ScanLifecyclePhase.Succeeded,
                updatedAtEpochMillis = 1L,
                resultReady = false,
                errorCode = null
            ) is ScanLifecycleValidation.Invalid
        )
        assertTrue(
            ScanLifecycleRecord.validateFields(
                ownerId = owner,
                requestId = request,
                generation = generation,
                phase = ScanLifecyclePhase.Failed,
                updatedAtEpochMillis = 1L,
                resultReady = false,
                errorCode = "unsafe details"
            ) is ScanLifecycleValidation.Invalid
        )
        assertFalse(ScanLifecycleRecord.isCanonicalUuid(UUID.randomUUID().toString().uppercase()))
    }
}
