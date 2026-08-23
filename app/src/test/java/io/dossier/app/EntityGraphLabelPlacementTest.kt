package io.dossier.app

import io.dossier.app.ui.screens.clampGraphLabelPlacement
import org.junit.Assert.assertEquals
import org.junit.Test

class EntityGraphLabelPlacementTest {
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
}
