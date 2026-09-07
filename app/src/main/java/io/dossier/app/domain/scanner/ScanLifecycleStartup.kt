package io.dossier.app.domain.scanner

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Narrow seam between process-startup orchestration and the Android stores.
 *
 * Implementations must return an action from the generation-bound lifecycle
 * reconciler and execute that exact action through the lifecycle store CAS
 * path.  The startup runner deliberately does not inspect WorkManager data;
 * [BackgroundScanManager] owns that projection.
 */
internal interface ScanLifecycleStartupGateway {
    fun reconcile(): ScanReconciliationAction

    fun execute(action: ScanReconciliationAction)
}

/**
 * Runs one lifecycle reconciliation on an injected dispatcher.
 *
 * Startup is intentionally single-flight: a second Activity instance in the
 * same process cannot enqueue the same fixed WorkRequest UUID again.  An
 * unavailable WorkManager lookup is retried without executing the retry action
 * (and therefore without changing lifecycle state or enqueueing work).
 */
internal class ScanLifecycleStartupReconciler(
    private val gateway: ScanLifecycleStartupGateway,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val retryDelayMillis: Long = DEFAULT_RETRY_DELAY_MILLIS
) {
    private val started = AtomicBoolean(false)

    init {
        require(retryDelayMillis >= 0L) { "retryDelayMillis must not be negative" }
    }

    /**
     * Starts a process-scoped job.  A null return means this instance already
     * has an active or completed startup run.
     */
    fun start(scope: CoroutineScope): Job? {
        if (!started.compareAndSet(false, true)) return null
        return scope.launch(ioDispatcher) { reconcileUntilSettled() }
    }

    /**
     * Deterministic suspend seam for JVM tests and application bootstrap.
     * Store reads, exact WorkManager lookup, and action execution all run on
     * [ioDispatcher], even when the caller is currently on the main thread.
     */
    suspend fun reconcileUntilSettled(): ScanReconciliationAction {
        var recoveryFollowUps = 0
        while (currentCoroutineContext().isActive) {
            val action = withContext(ioDispatcher) { gateway.reconcile() }
            if (action is ScanReconciliationAction.RetryReconciliation ||
                action is ScanReconciliationAction.RetryLegacyLookup
            ) {
                // RetryReconciliation is an explicit no-mutation action.  Do
                // not pass it to execute(), which prevents a failed lookup
                // from accidentally changing durable state.
                if (retryDelayMillis > 0L) delay(retryDelayMillis)
                continue
            }

            withContext(ioDispatcher) { gateway.execute(action) }
            // RecoverSucceeded is a durable intermediate transition. Allow
            // exactly one follow-up pass so a matching result can shed its
            // now-unneeded checkpoint/lifecycle metadata while the encrypted
            // result itself remains available to Analysis. The bound prevents
            // an unbounded loop if the CAS or storage write did not apply.
            if (action is ScanReconciliationAction.RecoverSucceeded && recoveryFollowUps == 0) {
                recoveryFollowUps += 1
                continue
            }
            return action
        }

        // Cancellation is the only way out of the retry loop.  Do not invent
        // an action or mutate lifecycle state after cancellation.
        throw kotlinx.coroutines.CancellationException("Startup reconciliation cancelled")
    }

    internal fun resetForTesting() {
        started.set(false)
    }

    private companion object {
        const val DEFAULT_RETRY_DELAY_MILLIS = 1_000L
    }
}

/**
 * Process-scoped startup entry point.  It owns no identity data and keeps the
 * reconciliation work off the Activity/main thread.  A process may create
 * multiple Activity instances, but only one startup run is launched.
 */
internal object ScanLifecycleStartup {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private var job: Job? = null

    fun start(context: Context) {
        val appContext = context.applicationContext
        synchronized(lock) {
            // Keep the guard for the whole process, including after a
            // successful no-op/terminal run.  A recreated Activity must not
            // replay a stale EnqueuePending action.
            if (!started.compareAndSet(false, true)) return

            val runner = ScanLifecycleStartupReconciler(
                gateway = object : ScanLifecycleStartupGateway {
                    private var checkpointMaintenanceAttempted = false

                    override fun reconcile(): ScanReconciliationAction {
                        if (!checkpointMaintenanceAttempted) {
                            checkpointMaintenanceAttempted = true
                            if (!ProfileScanCheckpointStore.retireLegacyAndPrune(appContext)) {
                                ScanSession.markBackgroundFailure(ScanLifecycleErrors.CLEANUP_FAILED)
                            }
                        }
                        return BackgroundScanManager.reconcile(appContext)
                    }

                    override fun execute(action: ScanReconciliationAction) {
                        BackgroundScanManager.executeReconciliation(appContext, action)
                    }
                }
            )
            job = runner.start(scope)
        }
    }

    /** Test-only process reset; production never clears a startup run. */
    internal fun resetForTesting() {
        synchronized(lock) {
            job?.cancel()
            job = null
            started.set(false)
        }
    }

}
