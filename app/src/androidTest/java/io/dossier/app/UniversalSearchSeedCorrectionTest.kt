package io.dossier.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.dossier.app.data.local.UsageNoticeStore
import io.dossier.app.domain.discovery.DiscoveryScanPreferences
import io.dossier.app.domain.scanner.ScanSession
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UniversalSearchSeedCorrectionTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        UsageNoticeStore.reset(composeRule.activity)
        DiscoveryScanPreferences.reset()
        ScanSession.tempInput = null
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
    }

    @After
    fun tearDown() {
        UsageNoticeStore.reset(composeRule.activity)
        DiscoveryScanPreferences.reset()
        ScanSession.tempInput = null
    }

    @Test
    fun ambiguousTextCanBeCorrectedFromUsernameToName() {
        composeRule.onNodeWithText("CONTINUE").performClick()
        composeRule.onAllNodes(hasSetTextAction())[0].performTextInput("jane")

        composeRule.onNodeWithText("Detected: Username").assertIsDisplayed()
        closeSoftKeyboard()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Correct detected seed type").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Name").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Name").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Using: Name").assertIsDisplayed()
        composeRule.onNodeWithText("SEARCH").performClick()
        assertEquals("jane", ScanSession.tempInput?.fullName)
    }
}
