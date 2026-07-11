package io.dossier.app

import io.dossier.app.ui.screens.faceConsistencySummary
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceConsistencySummaryTest {
    @Test
    fun summarizesMissingSelfie() {
        val text = faceConsistencySummary(hasSelfie = false, faceMatchCount = 0, calibratedMatchCount = 0)
        assertTrue(text.contains("Provide a selfie", ignoreCase = true))
    }

    @Test
    fun summarizesUncalibratedScores() {
        val text = faceConsistencySummary(hasSelfie = true, faceMatchCount = 3, calibratedMatchCount = 0)
        assertTrue(text.contains("not treated as identity evidence", ignoreCase = true))
    }

    @Test
    fun summarizesCalibratedMatches() {
        val text = faceConsistencySummary(hasSelfie = true, faceMatchCount = 4, calibratedMatchCount = 2)
        assertTrue(text.contains("2 calibrated", ignoreCase = true))
    }
}
