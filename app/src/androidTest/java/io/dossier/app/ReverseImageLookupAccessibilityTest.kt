package io.dossier.app

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.dossier.app.ui.screens.ReverseImageLookupScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReverseImageLookupAccessibilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun videoPickerExposesAnActionDescriptionAndButtonRole() {
        composeRule.setContent {
            ReverseImageLookupScreen(onNavigateToBrowser = {})
        }

        val picker = composeRule
            .onNodeWithContentDescription("Select a video for analysis.")
            .fetchSemanticsNode()

        assertEquals(
            Role.Button,
            picker.config[SemanticsProperties.Role]
        )
    }
}
