package io.dossier.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.dossier.app.data.local.UsageNoticeStore
import io.dossier.app.domain.case.CaseStore
import io.dossier.app.domain.scanner.BackgroundScanResultStore
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
    }

    @Test
    fun receiverSeedsEncryptedCaseAndTransientResult() {
        val intent = Intent(ACTION)
            .setComponent(ComponentName(context, "io.dossier.app.uiTest.VisualQaFixtureReceiver"))

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
    }

    private companion object {
        const val ACTION = "io.dossier.app.action.SEED_VISUAL_QA_FIXTURE"
        const val FIXED_WORK_ID = "00000000-0000-4000-8000-000000000100"
        const val FIXED_CASE_ID = "00000000-0000-4000-8000-000000000200"
    }
}
