package io.dossier.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.dossier.app.domain.case.CaseStore
import io.dossier.app.domain.case.DossierCase
import io.dossier.app.domain.case.RemediationStatus
import io.dossier.app.domain.case.UserCorrectionDecision
import io.dossier.app.domain.discovery.DiscoveryScanPreferences
import io.dossier.app.domain.discovery.ScanHistoryRuntime
import io.dossier.app.domain.discovery.ScanId
import io.dossier.app.domain.discovery.ScanMode
import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.FindingType
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.RiskLevel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class DossierComposeSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @After
    fun resetTestState() {
        DiscoveryScanPreferences.reset()
        CaseStore(composeRule.activity).clear()
    }

    @Test
    fun appLaunchesAtConsentGate() {
        composeRule.onNodeWithText("Dossier").assertIsDisplayed()
        composeRule.onNodeWithText("Before you begin").assertIsDisplayed()
        composeRule.onNodeWithText("I understand — continue").assertIsDisplayed()
    }

    @Test
    fun acceptingConsentOpensIdentitySetupAndRemovesGate() {
        acceptConsent()

        composeRule.onNodeWithText("Start a privacy audit").assertIsDisplayed()
        composeRule.onNodeWithText("Before you begin").assertDoesNotExist()
    }

    @Test
    fun identityContinueIsDisabledWithoutASignal() {
        acceptConsent()

        composeRule.onNodeWithText("Continue").assertIsNotEnabled()
        composeRule
            .onNodeWithText("Enter at least one identity signal to continue.")
            .assertIsDisplayed()
    }

    @Test
    fun invalidEmailShowsValidationAndKeepsContinueDisabled() {
        acceptConsent()

        identityField(index = 3).performTextInput("not-an-email")

        composeRule
            .onNodeWithText("Check invalid email input: not-an-email")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Continue").assertIsNotEnabled()
    }

    @Test
    fun validUsernameCompletesIdentityWizardAndOpensUsernameReview() {
        navigateToUsernameReview()
        composeRule.onNodeWithText("Username Discovery").assertIsDisplayed()
    }

    @Test
    fun scanDepthCanSelectDeepAndUpdatesRuntimePreference() {
        navigateToUsernameReview()

        composeRule.onNodeWithText("Standard").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Deep").performScrollTo().performClick()

        composeRule.runOnIdle {
            check(DiscoveryScanPreferences.selectedMode.value == ScanMode.Deep)
        }
    }

    @Test
    fun bottomNavigationOpensBreachCasesAndEngineScreens() {
        acceptConsent()

        openTab("Breaches")
        waitForText("Breach and exposure check")
        composeRule.onNodeWithText("Breach and exposure check").assertIsDisplayed()

        openTab("Cases")
        waitForText("Saved cases")
        composeRule.onNodeWithText("Saved cases").assertIsDisplayed()

        openTab("Engines")
        waitForText("AI Engine Configuration")
        composeRule.onNodeWithText("AI Engine Configuration").assertIsDisplayed()
    }

    @Test
    fun savedCaseReviewPersistsScanHistoryCorrectionAndRemediationState() {
        val store = CaseStore(composeRule.activity)
        val input = IdentityInput(fullName = "Jane Example")

        ScanHistoryRuntime.scanStarted(
            scanId = ScanId("case-scan-one"),
            input = input,
            mode = ScanMode.Deep,
            directProfileProviderCount = 61,
            occurredAt = Instant.parse("2026-08-08T00:00:00Z")
        )
        ScanHistoryRuntime.scanFinished(
            scanId = ScanId("case-scan-one"),
            occurredAt = Instant.parse("2026-08-08T00:03:00Z"),
            cancelled = false,
            profileResultCount = 12,
            findingCount = 1,
            breachRecordCount = 0,
            graphEntityCount = 4,
            graphRelationshipCount = 3
        )

        val savedCase = DossierCase(
            createdAt = "2026-08-08 00:03",
            subjectName = "Jane Example",
            input = input,
            findings = listOf(
                Finding(
                    type = FindingType.Email,
                    value = "jane@example.test",
                    sourceUrl = "https://example.test/contact",
                    evidenceSnippet = "Public contact page",
                    confidence = 0.9f,
                    risk = RiskLevel.High,
                    remediation = "Remove the public contact detail or restrict its visibility."
                )
            ),
            riskLevel = RiskLevel.High
        )
        check(store.save(savedCase))
        check(store.load(savedCase.caseId)?.scanHistory?.singleOrNull()?.scanId == "case-scan-one")

        // A newer run for the same seeds must not be silently grafted onto the
        // already-saved old case when the user later edits corrections/actions.
        ScanHistoryRuntime.scanStarted(
            scanId = ScanId("case-scan-two"),
            input = input,
            mode = ScanMode.Standard,
            directProfileProviderCount = 40,
            occurredAt = Instant.parse("2026-08-08T01:00:00Z")
        )
        ScanHistoryRuntime.scanFinished(
            scanId = ScanId("case-scan-two"),
            occurredAt = Instant.parse("2026-08-08T01:01:00Z"),
            cancelled = false,
            profileResultCount = 5,
            findingCount = 0,
            breachRecordCount = 0,
            graphEntityCount = 2,
            graphRelationshipCount = 1
        )

        acceptConsent()
        openTab("Cases")
        waitForText("Review newer case")

        composeRule
            .onNodeWithText("Share redacted case report")
            .performScrollTo()
            .assertIsDisplayed()

        composeRule.onNodeWithText("Mine").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            store.load(savedCase.caseId)?.let { loaded ->
                loaded.userCorrections.any {
                    it.decision == UserCorrectionDecision.ThisIsMe
                } && loaded.scanHistory.size == 1 &&
                    loaded.scanHistory.single().scanId == "case-scan-one"
            } == true
        }

        composeRule.onNodeWithText("Start").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            store.load(savedCase.caseId)?.let { loaded ->
                loaded.remediationRecords.any {
                    it.status == RemediationStatus.InProgress
                } && loaded.scanHistory.size == 1 &&
                    loaded.scanHistory.single().scanId == "case-scan-one"
            } == true
        }
    }

    private fun navigateToUsernameReview() {
        acceptConsent()

        identityField(index = 2).performTextInput("dossier_compose_test")
        composeRule.onNodeWithText("Continue").assertIsEnabled().performClick()

        waitForText("2. Add corroborating signals")
        composeRule.onNodeWithText("Continue").performClick()

        waitForText("3. Add direct sources")
        composeRule.onNodeWithText("Review usernames").performClick()

        waitForText("Username Discovery")
    }

    private fun acceptConsent() {
        composeRule.onNodeWithText("I understand — continue").performClick()
        waitForText("Start a privacy audit")
    }

    private fun identityField(index: Int) =
        composeRule.onAllNodes(hasSetTextAction())[index]

    private fun openTab(label: String) {
        composeRule
            .onNode(
                matcher = hasAnyDescendant(hasText(label)) and hasClickAction(),
                useUnmergedTree = true
            )
            .performClick()
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule
                .onAllNodesWithText(text)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }
}
