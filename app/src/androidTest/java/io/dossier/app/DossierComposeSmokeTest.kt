package io.dossier.app

import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.fetchSemanticsNodes
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DossierComposeSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

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
        acceptConsent()

        identityField(index = 2).performTextInput("dossier_compose_test")
        composeRule.onNodeWithText("Continue").assertIsEnabled().performClick()

        waitForText("2. Add corroborating signals")
        composeRule.onNodeWithText("Continue").performClick()

        waitForText("3. Add direct sources")
        composeRule.onNodeWithText("Review usernames").performClick()

        waitForText("Username Discovery")
        composeRule.onNodeWithText("Username Discovery").assertIsDisplayed()
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

    private fun acceptConsent() {
        composeRule.onNodeWithText("I understand — continue").performClick()
        waitForText("Start a privacy audit")
    }

    private fun identityField(index: Int) =
        composeRule.onAllNodes(hasSetTextAction())[index]

    private fun openTab(label: String) {
        composeRule
            .onNode(hasContentDescription(label) and hasClickAction())
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
