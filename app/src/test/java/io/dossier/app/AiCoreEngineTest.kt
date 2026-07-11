package io.dossier.app

import io.dossier.app.data.ai.AiCoreEngine
import io.dossier.app.data.ai.AiCoreStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCoreEngineTest {
    @Test
    fun parsesStructuredPromptResponse() {
        val result = AiCoreEngine.parseAnalysisResponse(
            """
            TEXT: Hotel Central lobby sign
            LANDMARKS: Eiffel Tower, Louvre
            FACE: yes
            """.trimIndent()
        )

        assertEquals("Hotel Central lobby sign", result.extractedText)
        assertEquals(listOf("Eiffel Tower", "Louvre"), result.detectedLandmarks)
        assertTrue(result.containsFace)
    }

    @Test
    fun treatsNoneFieldsAsEmpty() {
        val result = AiCoreEngine.parseAnalysisResponse(
            """
            TEXT: none
            LANDMARKS: none
            FACE: no
            """.trimIndent()
        )

        assertEquals(null, result.extractedText)
        assertEquals(emptyList<String>(), result.detectedLandmarks)
        assertEquals(false, result.containsFace)
    }

    @Test
    fun parsesSingleLineStructuredPromptResponse() {
        val result = AiCoreEngine.parseAnalysisResponse(
            "TEXT: Cafe sign LANDMARKS: Central Park FACE: true"
        )

        assertEquals("Cafe sign", result.extractedText)
        assertEquals(listOf("Central Park"), result.detectedLandmarks)
        assertEquals(true, result.containsFace)
    }

    @Test
    fun statusModelsExposeUserFacingState() {
        assertEquals("Available", AiCoreStatus.Available.label)
        assertTrue(AiCoreStatus.Available.canRun)
        assertEquals("Downloadable", AiCoreStatus.Downloadable.label)
        assertFalse(AiCoreStatus.Downloadable.canRun)
        assertEquals("Error", AiCoreStatus.Error("binding failed").label)
    }
}
