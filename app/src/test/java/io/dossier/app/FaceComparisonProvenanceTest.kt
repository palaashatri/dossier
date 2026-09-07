package io.dossier.app

import io.dossier.app.data.face.FaceCorrelationCalibrationStore
import io.dossier.app.data.face.FaceCorrelationModelPack
import io.dossier.app.data.face.FaceCorrelationThresholds
import io.dossier.app.data.face.toFaceComparisonProvenance
import io.dossier.app.domain.model.FaceComparisonBackend
import io.dossier.app.domain.model.FaceComparisonCalibrationState as ModelCalibrationState
import io.dossier.app.domain.model.FaceComparisonProvenance
import io.dossier.app.domain.model.FaceComparisonQuality
import io.dossier.app.domain.model.FaceConsistencyMatch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceComparisonProvenanceTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun structuredProvenanceAndQualityRoundTrip() {
        val quality = FaceComparisonQuality(
            accepted = true,
            reason = "accepted",
            detectorScore = 0.97f,
            faceWidth = 180f,
            faceHeight = 190f,
            eyeDistance = 62f,
            rollDegrees = 2.5f,
            brightness = 128f,
            sharpness = 44f
        )
        val match = FaceConsistencyMatch(
            profileUrl = "https://example.test/alice",
            similarityScore = 0.82f,
            warning = "Supporting evidence only",
            provenance = FaceComparisonProvenance(
                backend = FaceComparisonBackend.YuNetSFace,
                calibration = ModelCalibrationState.Measured,
                modelSource = "OpenCV Zoo YuNet 2023mar + SFace 2021dec",
                modelHashes = listOf(
                    FaceCorrelationModelPack.YUNET.sha256,
                    FaceCorrelationModelPack.SFACE.sha256
                ),
                pipelineVersion = FaceCorrelationModelPack.PIPELINE_VERSION,
                selfieQuality = quality,
                profileQuality = quality.copy(faceWidth = 176f)
            )
        )

        val encoded = json.encodeToString(match)
        val decoded = json.decodeFromString<FaceConsistencyMatch>(encoded)

        assertEquals(match, decoded)
        assertTrue(encoded.contains("YuNetSFace"))
        assertTrue(encoded.contains(FaceCorrelationModelPack.PIPELINE_VERSION))
    }

    @Test
    fun legacyFaceMatchPayloadDefaultsToUnknownProvenance() {
        val decoded = json.decodeFromString<FaceConsistencyMatch>(
            """
            {
              "profileUrl": "https://example.test/legacy",
              "similarityScore": 0.71,
              "warning": "Profile image appears visually similar"
            }
            """.trimIndent()
        )

        assertEquals(FaceComparisonBackend.Unknown, decoded.provenance.backend)
        assertEquals(ModelCalibrationState.Unknown, decoded.provenance.calibration)
        assertTrue(decoded.provenance.modelHashes.isEmpty())
    }

    @Test
    fun referenceAndMeasuredThresholdsExposeDistinctProvenanceStates() {
        val reference = FaceCorrelationCalibrationStore.REFERENCE_THRESHOLDS
            .toFaceComparisonProvenance()
        assertEquals(ModelCalibrationState.ReferencePolicy, reference.calibration)
        assertEquals(FaceComparisonBackend.YuNetSFace, reference.backend)

        val measured = FaceCorrelationThresholds(
            reviewThreshold = 0.40f,
            highSimilarityThreshold = 0.60f,
            source = "identity-disjoint test artifact",
            sfaceSha256 = FaceCorrelationModelPack.SFACE.sha256,
            yunetSha256 = FaceCorrelationModelPack.YUNET.sha256,
            pipelineVersion = FaceCorrelationModelPack.PIPELINE_VERSION,
            positivePairCount = 500,
            negativePairCount = 10_000,
            reviewFalseMatchRate = 0.01f,
            highFalseMatchRate = 0.001f,
            reviewTrueMatchRate = 0.90f,
            highTrueMatchRate = 0.70f,
            measured = true,
            identityDisjoint = true,
            consentConfirmed = true
        ).toFaceComparisonProvenance()

        assertEquals(ModelCalibrationState.Measured, measured.calibration)
        assertEquals(
            listOf(FaceCorrelationModelPack.YUNET.sha256, FaceCorrelationModelPack.SFACE.sha256),
            measured.modelHashes
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun provenanceRejectsUnboundedModelHashLists() {
        FaceComparisonProvenance(
            modelHashes = List(3) { FaceCorrelationModelPack.SFACE.sha256 }
        )
    }
}
