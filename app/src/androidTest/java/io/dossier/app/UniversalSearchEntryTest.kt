package io.dossier.app

import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.dossier.app.data.local.UsageNoticeStore
import io.dossier.app.domain.discovery.DiscoveryScanPreferences
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UniversalSearchEntryTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun resetBeforeEach() {
        UsageNoticeStore.reset(composeRule.activity)
        DiscoveryScanPreferences.reset()
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
    }

    @After
    fun resetAfterEach() {
        UsageNoticeStore.reset(composeRule.activity)
        DiscoveryScanPreferences.reset()
    }

    @Test
    fun acceptingConsentOpensUniversalSearchInsteadOfIdentityWizard() {
        composeRule.onNodeWithText("CONTINUE").performClick()

        composeRule.onNodeWithText("Search name, username, phone, email or URL").assertIsDisplayed()
        composeRule.onNodeWithText("Camera").assertIsDisplayed()
        composeRule.onNodeWithText("Choose photo").assertIsDisplayed()
        composeRule.onNodeWithText("Start a privacy audit").assertDoesNotExist()
    }

    @Test
    fun universalSearchShowsLocalSeedDetectionAndEnablesSearch() {
        composeRule.onNodeWithText("CONTINUE").performClick()

        composeRule.onNodeWithText("SEARCH").assertIsNotEnabled()
        composeRule.onAllNodes(hasSetTextAction())[0].performTextInput("Jane Example")

        composeRule.onNodeWithText("Detected: Name").assertIsDisplayed()
        composeRule.onNodeWithText("SEARCH").assertIsEnabled()
    }
}
