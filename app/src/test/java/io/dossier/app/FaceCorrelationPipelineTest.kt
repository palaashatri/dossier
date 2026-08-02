package io.dossier.app

import io.dossier.app.data.face.FaceCorrelationCalibrationStore
import io.dossier.app.data.face.FaceCorrelationDecision
import io.dossier.app.data.face.FaceCorrelationModelPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceCorrelationPipelineTest {

    @Test
    fun pinnedModelPackUsesHttpsExactHashesAndExpectedSize() {
        val specs = FaceCorrelationModelPack.MODEL_SPECS
        assertEquals(2, specs.size)
        assertTrue(specs.all { it.url.startsWith("https://") })
        assertTrue(specs.all { it.sha256.matches(Regex("[0-9a-f]{64}")) })
        assertTrue(specs.all { it.sizeBytes > 100_000L })
        assertEquals(specs.sumOf { it.sizeBytes }, FaceCorrelationModelPack.EXPECTED_PACK_BYTES)
        assertTrue(FaceCorrelationModelPack.YUNET.license.contains("MIT"))
        assertTrue(FaceCorrelationModelPack.SFACE.license.contains("Apache"))
    }

    @Test
    fun referenceThresholdsRemainExplicitlyUnmeasured() {
        val thresholds = FaceCorrelationCalibrationStore.REFERENCE_THRESHOLDS
        assertFalse(thresholds.measured)
        assertEquals(FaceCorrelationDecision.NO_SUPPORT, thresholds.decision(0.20f))
        assertEquals(FaceCorrelationDecision.MANUAL_REVIEW, thresholds.decision(0.40f))
        assertEquals(FaceCorrelationDecision.HIGH_SIMILARITY, thresholds.decision(0.70f))
        assertTrue(thresholds.summary().contains("not yet Dossier-benchmarked"))
    }

    @Test
    fun measuredCalibrationParsesAndBindsToExactPipeline() {
        val json = """
            {
              "reviewThreshold": 0.41,
              "highSimilarityThreshold": 0.62,
              "sfaceSha256": "${FaceCorrelationModelPack.SFACE.sha256}",
              "yunetSha256": "${FaceCorrelationModelPack.YUNET.sha256}",
              "pipelineVersion": "${FaceCorrelationModelPack.PIPELINE_VERSION}",
              "positivePairCount": 8000,
              "negativePairCount": 200000,
              "reviewFalseMatchRate": 0.01,
              "highFalseMatchRate": 0.0001,
              "reviewTrueMatchRate": 0.91,
              "highTrueMatchRate": 0.72,
              "source": "Identity-disjoint held-out Dossier evaluation"
            }
        """.trimIndent()

        val parsed = FaceCorrelationCalibrationStore.parse(json)

        assertTrue(parsed.measured)
        assertEquals(8_000, parsed.positivePairCount)
        assertEquals(200_000, parsed.negativePairCount)
        assertEquals(FaceCorrelationDecision.MANUAL_REVIEW, parsed.decision(0.50f))
        assertEquals(FaceCorrelationDecision.HIGH_SIMILARITY, parsed.decision(0.70f))
        assertTrue(parsed.summary().contains("held-out pairs"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun measuredCalibrationRejectsLooserHighFalseMatchRate() {
        io.dossier.app.data.face.FaceCorrelationThresholds(
            reviewThreshold = 0.40f,
            highSimilarityThreshold = 0.60f,
            source = "invalid",
            sfaceSha256 = FaceCorrelationModelPack.SFACE.sha256,
            yunetSha256 = FaceCorrelationModelPack.YUNET.sha256,
            pipelineVersion = FaceCorrelationModelPack.PIPELINE_VERSION,
            positivePairCount = 100,
            negativePairCount = 100,
            reviewFalseMatchRate = 0.01f,
            highFalseMatchRate = 0.02f,
            reviewTrueMatchRate = 0.90f,
            highTrueMatchRate = 0.70f,
            measured = true
        )
    }
}
