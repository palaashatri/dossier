package io.dossier.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.dossier.app.data.local.UsageNoticeStore
import io.dossier.app.domain.case.CaseScanHistoryEntry
import io.dossier.app.domain.case.DossierCase
import io.dossier.app.domain.discovery.ScanMode
import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.FindingType
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.RiskLevel
import io.dossier.app.domain.scanner.BackgroundScanManager
import io.dossier.app.domain.scanner.BackgroundScanResultStore
import io.dossier.app.domain.scanner.ScanLifecyclePhase
import io.dossier.app.domain.scanner.ScanLifecycleRecord
import io.dossier.app.domain.scanner.ScanLifecycleStartup
import io.dossier.app.domain.scanner.ScanLifecycleStore
import io.dossier.app.domain.scanner.ScanLifecycleWriteResult
import io.dossier.app.domain.scanner.ScanSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Verifies that a completed encrypted background result remains reviewable
 * after the Activity is recreated and startup reconciliation runs again.
 *
 * This deliberately does not claim process-death or physical-device coverage:
 * the Compose rule controls Activity recreation inside one instrumentation
 * process. The encrypted file and Android Keystore are still re-read by the
 * recreated screen rather than relying on the previous Activity instance.
 */
@RunWith(AndroidJUnit4::class)
class BackgroundResultActivityRecreationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val context
        get() = composeRule.activity.applicationContext

    @Before
    fun setUp() {
        clearBackgroundState()
        context.getSharedPreferences("dossier-usage-notice", 0)
            .edit()
            .putInt("accepted_version", 1)
            .commit()
        assertTrue(UsageNoticeStore.isAccepted(context))
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        if (composeRule.onAllNodesWithText("CONTINUE").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithText("CONTINUE").performClick()
        }
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText("Start a privacy audit")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    @After
    fun tearDown() {
        clearBackgroundState()
        context.getSharedPreferences("dossier-usage-notice", 0)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun encryptedResultRestoresTruthfulAnalysisStateAfterActivityRecreation() {
        val input = IdentityInput(
            fullName = "Activity Recovery Subject",
            primaryUsername = "activity-recovery"
        )
        val finding = Finding(
            type = FindingType.Email,
            value = "recovery@example.test",
            sourceUrl = "https://recovery.example.test/contact",
            evidenceSnippet = "Encrypted result recovery fixture",
            confidence = 0.9f,
            risk = RiskLevel.High,
            remediation = "Review the public contact detail."
        )
        val history = CaseScanHistoryEntry(
            scanId = "activity-recreation-scan",
            startedAtUtc = "2026-08-25T00:00:00Z",
            completedAtUtc = "2026-08-25T00:05:00Z",
            mode = ScanMode.Deep,
            directProfileProviderCount = 32,
            profileResultCount = 17,
            findingCount = 1,
            breachRecordCount = 0,
            graphEntityCount = 4,
            graphRelationshipCount = 3
        )
        val expectedCase = DossierCase(
            createdAt = "2026-08-25T00:05:00Z",
            subjectName = input.fullName,
            input = input,
            findings = listOf(finding),
            riskLevel = RiskLevel.High,
            scanHistory = listOf(history)
        )
        val ownerId = UUID.randomUUID().toString()
        val lifecycle = ScanLifecycleRecord(
            ownerId = ownerId,
            requestId = UUID.randomUUID().toString(),
            generation = UUID.randomUUID().toString(),
            phase = ScanLifecyclePhase.Succeeded,
            updatedAtEpochMillis = System.currentTimeMillis(),
            resultReady = true,
            errorCode = null
        )

        assertTrue(BackgroundScanResultStore(context).save(ownerId, expectedCase))
        assertEquals(
            ScanLifecycleWriteResult.Saved,
            ScanLifecycleStore(context).publish(lifecycle)
        )
        assertNotNull(BackgroundScanManager.latestResult(context))

        // Activity recreation does not clear this durable file. Replace the
        // singleton state first so the assertion proves the recreated screen
        // restores it instead of merely observing the old in-memory values.
        ScanSession.restoreFromCase(
            DossierCase(
                createdAt = "2026-08-25T00:00:00Z",
                subjectName = "Stale in-memory state",
                input = IdentityInput(fullName = "Stale in-memory state")
            )
        )
        assertEquals("Stale in-memory state", ScanSession.currentInput.value?.fullName)

        // Reset the process-scoped startup guard so this recreation exercises
        // the same lifecycle reconciliation seam used at app startup.
        ScanLifecycleStartup.resetForTesting()
        composeRule.activityRule.scenario.recreate()
        assertTrue("Usage notice should remain accepted across recreation", UsageNoticeStore.isAccepted(context))

        composeRule.waitUntil(timeoutMillis = 20_000) {
            BackgroundScanResultStore(context).load()?.dossierCase == expectedCase
        }
        val managerResult = BackgroundScanManager.latestResult(context)
        assertEquals(expectedCase, managerResult?.dossierCase)
        val hasAnalysis = composeRule
            .onAllNodesWithText("Background analysis")
            .fetchSemanticsNodes()
            .isNotEmpty()
        val hasIdentity = composeRule
            .onAllNodesWithText("Start a privacy audit")
            .fetchSemanticsNodes()
            .isNotEmpty()
        val hasConsent = composeRule
            .onAllNodesWithText("One-time usage notice")
            .fetchSemanticsNodes()
            .isNotEmpty()
        assertTrue(
            "Expected analysis route; analysis=$hasAnalysis identity=$hasIdentity consent=$hasConsent",
            hasAnalysis
        )
        composeRule.onNodeWithText("Background analysis").assertIsDisplayed()
        assertEquals(input, ScanSession.currentInput.value)
        assertEquals(listOf(finding), ScanSession.findings.value)
        assertEquals(listOf(history), ScanSession.scanHistory.value)
        composeRule.onNodeWithText(
            "The latest encrypted transient result is ready for review."
        ).assertIsDisplayed()
        assertTrue(
            composeRule.onAllNodesWithText("OPEN FULL REPORT")
                .fetchSemanticsNodes()
                .isNotEmpty()
        )

        val restored = BackgroundScanResultStore(context).load()?.dossierCase
        assertEquals(expectedCase, restored)
    }

    private fun clearBackgroundState() {
        ScanLifecycleStartup.resetForTesting()
        context.getSharedPreferences("dossier-background-work", 0)
            .edit()
            .clear()
            .commit()
        BackgroundScanResultStore(context).clear()
        ScanSession.purgeSession(context)
        context.getSharedPreferences("dossier-background-work", 0)
            .edit()
            .clear()
            .commit()
        BackgroundScanResultStore(context).clear()
    }
}
