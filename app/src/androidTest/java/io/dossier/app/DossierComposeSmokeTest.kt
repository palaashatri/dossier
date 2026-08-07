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
import io.dossier.app.data.platform.ProviderCatalogV2
import io.dossier.app.domain.discovery.DiscoveryScanPreferences
import io.dossier.app.domain.discovery.ScanMode
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DossierComposeSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @After
    fun resetDiscoveryPreferences() {
        DiscoveryScanPreferences.reset()
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
    fun scanDepthUsesReviewedProviderPlanAndCanSelectDeep() {
        navigateToUsernameReview()

        val standardProfileCount = ProviderCatalogV2
            .legacyProfileDefinitions(ScanMode.Standard)
            .size
        val deepProfileCount = ProviderCatalogV2
            .legacyProfileDefinitions(ScanMode.Deep)
            .size

        composeRule.onNodeWithText("Standard").performScrollTo().assertIsDisplayed()
        composeRule
            .onNodeWithText("$standardProfileCount profile providers")
            .performScrollTo()
            .assertIsDisplayed()

        composeRule.onNodeWithText("Deep").performScrollTo().performClick()
        composeRule
            .onNodeWithText("$deepProfileCount profile providers")
            .performScrollTo()
            .assertIsDisplayed()

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
