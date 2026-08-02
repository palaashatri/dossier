package io.dossier.app

import io.dossier.app.ui.screens.faceConsistencySummary
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportUiSemanticsTest {

    @Test
    fun noSelfieSummaryDoesNotImplyFailureOrIdentityProof() {
        val text = faceConsistencySummary(
            hasSelfie = false,
            faceMatchCount = 0,
            calibratedMatchCount = 0
        )
        assertTrue(text.contains("No reference photo"))
        assertFalse(text.contains("failed", ignoreCase = true))
        assertFalse(text.contains("same person", ignoreCase = true))
    }

    @Test
    fun unmeasuredVisualScoresRemainNonIdentityEvidence() {
        val text = faceConsistencySummary(
            hasSelfie = true,
            faceMatchCount = 4,
            calibratedMatchCount = 0
        )
        assertTrue(text.contains("not treated as identity evidence"))
        assertTrue(text.contains("measured calibration"))
    }

    @Test
    fun measuredVisualSummaryStillDeniesOwnershipProof() {
        val text = faceConsistencySummary(
            hasSelfie = true,
            faceMatchCount = 5,
            calibratedMatchCount = 2
        )
        assertTrue(text.contains("supporting evidence"))
        assertTrue(text.contains("not ownership proof"))
    }
}
