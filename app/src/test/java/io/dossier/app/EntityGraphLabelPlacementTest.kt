package io.dossier.app

import androidx.compose.ui.unit.Constraints
import io.dossier.app.domain.model.DossierEdge
import io.dossier.app.domain.model.DossierEntity
import io.dossier.app.domain.model.EntityGraph
import io.dossier.app.domain.model.EntityType
import io.dossier.app.ui.screens.clampGraphLabelPlacement
import io.dossier.app.ui.screens.centeredGraphScrollOffset
import io.dossier.app.ui.screens.graphLabelIds
import io.dossier.app.ui.screens.layoutGraph
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityGraphLabelPlacementTest {
    @Test
    fun initialGraphScrollCentersSubjectWithinMeasuredRange() {
        assertEquals(
            500,
            centeredGraphScrollOffset(
                contentCoordinatePx = 800f,
                viewportSizePx = 600f,
                maxScrollPx = 1_000
            )
        )
    }

    @Test
    fun initialGraphScrollClampsWhenSubjectIsNearContentEdge() {
        assertEquals(
            0,
            centeredGraphScrollOffset(
                contentCoordinatePx = 40f,
                viewportSizePx = 600f,
                maxScrollPx = 1_000
            )
        )
    }

    @Test
    fun measuredLabelNearRightEdgeIsClampedWithoutChangingItsWidth() {
        val placement = clampGraphLabelPlacement(
            nodeCenterX = 196f,
            nodeBaselineY = 46f,
            labelWidth = 120f,
            canvasWidth = 200f,
            canvasHeight = 100f,
            fontAscent = -8f,
            fontDescent = 2f,
            edgePadding = 8f
        )

        assertEquals(132f, placement.centerX, 0.001f)
        assertEquals(46f, placement.baselineY, 0.001f)
    }

    @Test
    fun measuredLabelNearTopEdgeIsClampedToTheFontBounds() {
        val placement = clampGraphLabelPlacement(
            nodeCenterX = 40f,
            nodeBaselineY = 3f,
            labelWidth = 60f,
            canvasWidth = 200f,
            canvasHeight = 100f,
            fontAscent = -8f,
            fontDescent = 2f,
            edgePadding = 8f
        )

        assertEquals(40f, placement.centerX, 0.001f)
        assertEquals(16f, placement.baselineY, 0.001f)
    }

    @Test
    fun denseGraphCapsDefaultVisualLabelsButKeepsSubject() {
        val graph = EntityGraph(
            entities = listOf(
                DossierEntity("subject", EntityType.Person, "Synthetic subject", confidence = 1f)
            ) + (1..12).map { index ->
                DossierEntity(
                    id = "entity-$index",
                    type = EntityType.Website,
                    label = "https://example.test/item/$index",
                    confidence = index / 12f
                )
            }
        )

        val labels = graphLabelIds(graph, adjacency = emptyMap(), selectedId = null)

        assertEquals(7, labels.size)
        assertTrue("subject" in labels)
    }

    @Test
    fun selectedGraphLabelsSubjectSelectedNodeAndItsNeighborhood() {
        val graph = EntityGraph(
            entities = listOf(
                DossierEntity("subject", EntityType.Person, "Synthetic subject", confidence = 1f),
                DossierEntity("selected", EntityType.Profile, "https://example.test/selected", confidence = 0.9f),
                DossierEntity("neighbor", EntityType.Email, "contact@example.test", confidence = 0.8f),
                DossierEntity("unrelated", EntityType.Website, "https://example.test/unrelated", confidence = 1f)
            ),
            edges = listOf(
                DossierEdge("selected", "neighbor", "mentions")
            )
        )

        val labels = graphLabelIds(
            graph,
            adjacency = mapOf("selected" to setOf("neighbor")),
            selectedId = "selected"
        )

        assertTrue("subject" in labels)
        assertTrue("selected" in labels)
        assertTrue("neighbor" in labels)
        assertTrue("unrelated" !in labels)
    }

    @Test
    fun denseGraphLayoutStaysWithinComposeConstraintDimension() {
        val graph = EntityGraph(
            entities = listOf(
                DossierEntity("subject", EntityType.Person, "Synthetic subject", confidence = 1f)
            ) + (1..2_000).map { index ->
                DossierEntity(
                    id = "entity-$index",
                    type = EntityType.Website,
                    label = "https://example.test/item/$index"
                )
            }
        )

        val density = 3f
        val layout = layoutGraph(graph, density)
        val width = layout.width.value * density
        val height = layout.height.value * density
        val widthPx = width.roundToInt()
        val heightPx = height.roundToInt()

        assertTrue("width=$width", width <= 32_766f)
        assertTrue("height=$height", height <= 32_766f)
        assertEquals(graph.entities.size, layout.positions.size)
        assertTrue(layout.positions.values.all { it.x >= 0f && it.x <= layout.width.value })
        assertTrue(layout.positions.values.all { it.y >= 0f && it.y <= layout.height.value })
        val constraints = Constraints.fixed(widthPx, heightPx)
        assertEquals(widthPx, constraints.maxWidth)
        assertEquals(heightPx, constraints.maxHeight)
    }
}
