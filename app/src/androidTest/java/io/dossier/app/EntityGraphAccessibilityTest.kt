package io.dossier.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.dossier.app.domain.model.DossierEntity
import io.dossier.app.domain.model.EntityGraph
import io.dossier.app.domain.model.EntityType
import io.dossier.app.ui.screens.EntityGraphView
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EntityGraphAccessibilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun graphViewAndAccessibleListExposeSelectionState() {
        val graph = EntityGraph(
            entities = listOf(
                DossierEntity(
                    id = "subject",
                    type = EntityType.Person,
                    label = "Ada Lovelace"
                )
            )
        )
        composeRule.setContent { EntityGraphView(graph = graph) }

        val visualTab = composeRule.onNodeWithText("Visual graph").fetchSemanticsNode()
        assertEquals(true, visualTab.config[SemanticsProperties.Selected])
        assertEquals("Selected", visualTab.config[SemanticsProperties.StateDescription])

        val listTab = composeRule.onNodeWithText("Accessible list")
        assertEquals(false, listTab.fetchSemanticsNode().config[SemanticsProperties.Selected])
        listTab.performClick()

        val entityRow = composeRule.onNode(hasText("Person: Ada Lovelace") and hasClickAction())
        assertEquals("Not selected", entityRow.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        entityRow.performClick()
        composeRule.waitForIdle()
        assertEquals(true, entityRow.fetchSemanticsNode().config[SemanticsProperties.Selected])
        assertEquals("Selected", entityRow.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
    }
}
