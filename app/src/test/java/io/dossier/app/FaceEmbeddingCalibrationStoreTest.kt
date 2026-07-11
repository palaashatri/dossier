package io.dossier.app

import io.dossier.app.data.face.FaceEmbeddingCalibrationStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceEmbeddingCalibrationStoreTest {
    @Test
    fun parsesCalibratedThresholdsFromJson() {
        val thresholds = FaceEmbeddingCalibrationStore.parseCalibrationJson(
            """
            {
              "reviewThreshold": 0.42,
              "samePersonThreshold": 0.71,
              "modelSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "positivePairCount": 200,
              "negativePairCount": 1000,
              "reviewFalseAcceptRate": 0.02,
              "samePersonFalseAcceptRate": 0.001,
              "reviewTrueAcceptRate": 0.98,
              "samePersonTrueAcceptRate": 0.91,
              "source": "arcface-lfw-eval"
            }
            """.trimIndent()
        )

        assertEquals(0.42f, thresholds.reviewThreshold, 0.0001f)
        assertEquals(0.71f, thresholds.samePersonThreshold, 0.0001f)
        assertEquals("arcface-lfw-eval", thresholds.source)
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", thresholds.modelSha256)
        assertEquals(200, thresholds.positivePairCount)
        assertEquals(1000, thresholds.negativePairCount)
        assertEquals(0.001f, thresholds.samePersonFalseAcceptRate, 0.0001f)
        assertTrue(thresholds.isReviewScore(0.5f))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvertedThresholds() {
        FaceEmbeddingCalibrationStore.parseCalibrationJson(
            """
            {
              "review_threshold": 0.8,
              "same_person_threshold": 0.7,
              "model_sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
              "positive_pair_count": 100,
              "negative_pair_count": 100,
              "review_false_accept_rate": 0.01,
              "same_person_false_accept_rate": 0.0,
              "review_true_accept_rate": 0.9,
              "same_person_true_accept_rate": 0.8
            }
            """.trimIndent()
        )
    }
}
