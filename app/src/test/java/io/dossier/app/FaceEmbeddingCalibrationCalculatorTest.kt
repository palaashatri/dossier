package io.dossier.app

import io.dossier.app.data.face.FaceEmbeddingCalibrationCalculator
import io.dossier.app.data.face.FaceEmbeddingCalibrationSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceEmbeddingCalibrationCalculatorTest {
    @Test
    fun computesThresholdsFromLabeledScores() {
        val samples = listOf(
            FaceEmbeddingCalibrationSample(score = 0.91f, samePerson = true),
            FaceEmbeddingCalibrationSample(score = 0.83f, samePerson = true),
            FaceEmbeddingCalibrationSample(score = 0.76f, samePerson = true),
            FaceEmbeddingCalibrationSample(score = 0.42f, samePerson = false),
            FaceEmbeddingCalibrationSample(score = 0.33f, samePerson = false),
            FaceEmbeddingCalibrationSample(score = 0.21f, samePerson = false),
            FaceEmbeddingCalibrationSample(score = 0.11f, samePerson = false)
        )

        val thresholds = FaceEmbeddingCalibrationCalculator.calibrate(
            samples = samples,
            modelSha256 = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
            source = "unit-eval",
            reviewMaxFalseAcceptRate = 0.25f,
            samePersonMaxFalseAcceptRate = 0f
        )

        assertTrue(thresholds.reviewThreshold <= thresholds.samePersonThreshold)
        assertEquals(3, thresholds.positivePairCount)
        assertEquals(4, thresholds.negativePairCount)
        assertTrue(thresholds.reviewFalseAcceptRate <= 0.25f)
        assertEquals(0f, thresholds.samePersonFalseAcceptRate, 0.0001f)
    }
}
