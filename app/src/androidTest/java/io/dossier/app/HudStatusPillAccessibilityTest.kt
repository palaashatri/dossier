package io.dossier.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.dossier.app.ui.components.HudLevel
import io.dossier.app.ui.components.HudStatusPill
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HudStatusPillAccessibilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun severityIsExposedBeyondStatusColor() {
        val expectedDescriptions = mapOf(
            HudLevel.OK to "Positive status",
            HudLevel.WARN to "Warning status",
            HudLevel.CRIT to "Critical status",
            HudLevel.INFO to "Informational status"
        )

        expectedDescriptions.forEach { (level, expectedDescription) ->
            composeRule.setContent {
                HudStatusPill(text = "STATUS", level = level)
            }

            val node = composeRule.onNodeWithText("STATUS").fetchSemanticsNode()
            assertEquals(
                "HUD severity must be available to assistive technology for $level",
                expectedDescription,
                node.config[SemanticsProperties.StateDescription]
            )
        }
    }
}
