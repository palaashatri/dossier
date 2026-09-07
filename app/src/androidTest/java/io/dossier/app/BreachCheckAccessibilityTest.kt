package io.dossier.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.dossier.app.ui.screens.BreachCheckScreen
import io.dossier.app.ui.theme.DossierTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BreachCheckAccessibilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun screenTitleIsExposedAsHeadingAndRunActionIsButton() {
        composeRule.setContent {
            DossierTheme(darkTheme = false) {
                BreachCheckScreen(onNavigateToBrowser = {})
            }
        }

        val title = composeRule.onNodeWithText("Breach and exposure check").fetchSemanticsNode()
        assertTrue(title.config.contains(SemanticsProperties.Heading))

        val run = composeRule.onNodeWithText("Run check").fetchSemanticsNode()
        assertEquals(Role.Button, run.config[SemanticsProperties.Role])
    }
}
