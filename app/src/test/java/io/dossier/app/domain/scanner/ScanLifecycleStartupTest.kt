package io.dossier.app.domain.scanner

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class ScanLifecycleStartupTest {
    private val owner = "11111111-1111-4111-8111-111111111111"
    private val request = "22222222-2222-4222-8222-222222222222"
    private val generation = "33333333-3333-4333-8333-333333333333"

    private val expected = ScanLifecycleRecord(
        ownerId = owner,
        requestId = request,
        generation = generation,
        phase = ScanLifecyclePhase.Running,
        updatedAtEpochMillis = 10L,
        resultReady = false
    )

    @Test
    fun unavailableLookupRetriesWithoutExecutingOrMutatingRetryAction() = runBlocking {
        val gateway = FakeGateway(
            actions = ArrayDeque(
                listOf(
                    ScanReconciliationAction.RetryReconciliation(expected),
                    ScanReconciliationAction.RetryLegacyLookup,
                    ScanReconciliationAction.KeepOrRecover(expected, ScanWorkState.Running)
                )
            )
        )
        val runner = ScanLifecycleStartupReconciler(
            gateway = gateway,
            ioDispatcher = Dispatchers.Unconfined,
            retryDelayMillis = 0L
        )

        val final = runner.reconcileUntilSettled()

        assertEquals(
            ScanReconciliationAction.KeepOrRecover(expected, ScanWorkState.Running),
            final
        )
        assertEquals(3, gateway.reconcileCount)
        assertEquals(listOf(final), gateway.executed)
    }

    @Test
    fun recoveredSuccessGetsOneFollowUpCleanupPass() = runBlocking {
        val recovered = expected.copy(
            phase = ScanLifecyclePhase.Succeeded,
            updatedAtEpochMillis = 11L,
            resultReady = true
        )
        val recover = ScanReconciliationAction.RecoverSucceeded(expected)
        val cleanup = ScanReconciliationAction.CompleteCleanup(recovered)
        val gateway = FakeGateway(actions = ArrayDeque(listOf(recover, cleanup)))
        val runner = ScanLifecycleStartupReconciler(
            gateway = gateway,
            ioDispatcher = Dispatchers.Unconfined,
            retryDelayMillis = 0L
        )

        val final = runner.reconcileUntilSettled()

        assertEquals(cleanup, final)
        assertEquals(2, gateway.reconcileCount)
        assertEquals(listOf(recover, cleanup), gateway.executed)
    }

    @Test
    fun allGatewayWorkRunsOnInjectedIoDispatcher() {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "startup-reconcile-test")
        }
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val gateway = FakeGateway(
                actions = ArrayDeque(
                    listOf(ScanReconciliationAction.KeepOrRecover(expected, ScanWorkState.Running))
                )
            )
            val callerThread = Thread.currentThread().name
            runBlocking {
                ScanLifecycleStartupReconciler(
                    gateway = gateway,
                    ioDispatcher = dispatcher,
                    retryDelayMillis = 0L
                ).reconcileUntilSettled()
            }

            assertTrue(gateway.reconcileThread.get().startsWith("startup-reconcile-test"))
            assertTrue(gateway.executeThread.get().startsWith("startup-reconcile-test"))
            assertTrue(gateway.reconcileThread.get() != callerThread)
            assertTrue(gateway.executeThread.get() != callerThread)
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun startIsSingleFlightEvenAfterTheFirstJobCompletes() {
        val gateway = FakeGateway(
            actions = ArrayDeque(listOf(ScanReconciliationAction.ReenqueueSameUuid(expected)))
        )
        val runner = ScanLifecycleStartupReconciler(
            gateway = gateway,
            ioDispatcher = Dispatchers.Unconfined,
            retryDelayMillis = 0L
        )
        val scope = CoroutineScope(Dispatchers.Unconfined)

        val first = runner.start(scope)
        val second = runner.start(scope)

        assertTrue(first != null)
        assertNull(second)
        assertEquals(1, gateway.reconcileCount)
        assertEquals(
            listOf(ScanReconciliationAction.ReenqueueSameUuid(expected)),
            gateway.executed
        )
    }

    private class FakeGateway(
        private val actions: ArrayDeque<ScanReconciliationAction>
    ) : ScanLifecycleStartupGateway {
        var reconcileCount: Int = 0
            private set
        val executed = mutableListOf<ScanReconciliationAction>()
        val reconcileThread = AtomicReference("")
        val executeThread = AtomicReference("")

        override fun reconcile(): ScanReconciliationAction {
            reconcileCount += 1
            reconcileThread.set(Thread.currentThread().name)
            return actions.removeFirstOrNull() ?: ScanReconciliationAction.DoNotAdopt
        }

        override fun execute(action: ScanReconciliationAction) {
            executeThread.set(Thread.currentThread().name)
            executed += action
        }
    }
}
