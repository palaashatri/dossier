package io.dossier.app

import io.dossier.app.data.face.FaceEmbeddingCalibrationCalculator
import io.dossier.app.data.face.FaceEmbeddingCalibrationSample
import io.dossier.app.data.face.FaceEmbeddingCalibrationStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceEmbeddingThresholdsJsonTest {
    @Test
    fun calibrationJson_roundTripsThroughParser() {
        val thresholds = FaceEmbeddingCalibrationCalculator.calibrate(
            samples = listOf(
                FaceEmbeddingCalibrationSample(0.91f, true),
                FaceEmbeddingCalibrationSample(0.83f, true),
                FaceEmbeddingCalibrationSample(0.76f, true),
                FaceEmbeddingCalibrationSample(0.42f, false),
                FaceEmbeddingCalibrationSample(0.33f, false),
                FaceEmbeddingCalibrationSample(0.21f, false),
                FaceEmbeddingCalibrationSample(0.11f, false)
            ),
            modelSha256 = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
            source = "unit-eval \"quoted\"",
            reviewMaxFalseAcceptRate = 0.25f,
            samePersonMaxFalseAcceptRate = 0f
        )

        val json = thresholds.toCalibrationJson()
        val parsed = FaceEmbeddingCalibrationStore.parseCalibrationJson(json)

        assertEquals(thresholds.modelSha256.lowercase(), parsed.modelSha256)
        assertEquals(thresholds.positivePairCount, parsed.positivePairCount)
        assertEquals(thresholds.negativePairCount, parsed.negativePairCount)
        assertEquals(thresholds.source, parsed.source)
        assertEquals(thresholds.reviewThreshold, parsed.reviewThreshold, 0.0001f)
        assertEquals(thresholds.samePersonThreshold, parsed.samePersonThreshold, 0.0001f)
        assertTrue(parsed.reviewThreshold <= parsed.samePersonThreshold)
    }
}
