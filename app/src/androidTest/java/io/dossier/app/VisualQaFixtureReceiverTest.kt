package io.dossier.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.dossier.app.data.local.UsageNoticeStore
import io.dossier.app.domain.case.CaseStore
import io.dossier.app.domain.discovery.ScanCoordinatorRuntime
import io.dossier.app.domain.scanner.BackgroundScanManager
import io.dossier.app.domain.scanner.BackgroundScanResultStore
import io.dossier.app.domain.scanner.ScanLifecyclePhase
import io.dossier.app.domain.scanner.ScanLifecycleReadResult
import io.dossier.app.domain.scanner.ScanLifecycleRecord
import io.dossier.app.domain.scanner.ScanLifecycleStore
import io.dossier.app.domain.scanner.ScanLifecycleWriteResult
import io.dossier.app.uiTest.VisualQaFixtureReceiver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies the uiTest-only adb fixture uses the production encrypted stores. */
@RunWith(AndroidJUnit4::class)
class VisualQaFixtureReceiverTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun clearFixtureState() {
        BackgroundScanResultStore(context).clear()
        CaseStore(context).clear()
        UsageNoticeStore.reset(context)
        ScanCoordinatorRuntime.resetForTests()
    }

    @Test
    fun receiverSeedsEncryptedCaseAndTransientResult() {
        val intent = Intent(ACTION)
            .setComponent(ComponentName(context, "io.dossier.app.uiTest.VisualQaFixtureReceiver"))

        // Seed a stale terminal owner first. The fixture reset must remove it
        // or the production visibility projection will reject the new owner.
        context.getSharedPreferences("dossier-background-work", 0)
            .edit()
            .clear()
            .commit()
        val staleLifecycle = ScanLifecycleRecord(
            ownerId = "00000000-0000-4000-8000-000000000301",
            requestId = "00000000-0000-4000-8000-000000000302",
            generation = "00000000-0000-4000-8000-000000000303",
            phase = ScanLifecyclePhase.Succeeded,
            updatedAtEpochMillis = System.currentTimeMillis(),
            resultReady = true
        )
        assertEquals(
            ScanLifecycleWriteResult.Saved,
            ScanLifecycleStore(context).publish(staleLifecycle)
        )

        // Invoke the receiver directly so this test remains deterministic and
        // does not depend on the instrumentation/test package's broadcast
        // dispatch policy. The merged uiTest manifest is the adb boundary.
        VisualQaFixtureReceiver().onReceive(context, intent)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        assertTrue(UsageNoticeStore.isAccepted(context))
        val savedCase = CaseStore(context).load(FIXED_CASE_ID)
        assertNotNull(savedCase)
        assertEquals("Jane Example", savedCase?.subjectName)
        assertEquals("jane@example.test", savedCase?.input?.emails?.single())

        val snapshot = BackgroundScanResultStore(context).load()
        assertNotNull(snapshot)
        assertEquals(FIXED_WORK_ID, snapshot?.workId)
        assertEquals(FIXED_CASE_ID, snapshot?.dossierCase?.caseId)
        assertTrue(snapshot?.analysis?.identitySurface?.entries?.isNotEmpty() == true)
        assertEquals(ScanLifecycleReadResult.Missing, ScanLifecycleStore(context).read())
        assertEquals(FIXED_WORK_ID, BackgroundScanManager.latestResult(context)?.workId)
    }

    @Test
    fun receiverSeedsTruthfulProviderProgressForVisualQa() {
        val intent = Intent(ACTION_PROVIDER_PROGRESS)
            .setComponent(ComponentName(context, "io.dossier.app.uiTest.VisualQaFixtureReceiver"))

        VisualQaFixtureReceiver().onReceive(context, intent)

        val snapshot = ScanCoordinatorRuntime.snapshot.value
        assertEquals(12, snapshot.scheduledProviderCount)
        assertEquals(10, snapshot.startedProviderCount)
        assertEquals(7, snapshot.completedProviderCount)
        assertEquals(2, snapshot.unavailableProviderCount)
    }

    private companion object {
        const val ACTION = "io.dossier.app.action.SEED_VISUAL_QA_FIXTURE"
        const val ACTION_PROVIDER_PROGRESS = "io.dossier.app.action.SHOW_PROVIDER_PROGRESS_QA"
        const val FIXED_WORK_ID = "00000000-0000-4000-8000-000000000100"
        const val FIXED_CASE_ID = "00000000-0000-4000-8000-000000000200"
    }
}
