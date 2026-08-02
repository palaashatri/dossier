package io.dossier.app.domain.face

import android.content.Context
import android.net.Uri
import io.dossier.app.data.face.FaceAppearanceDescriptor
import io.dossier.app.data.face.FaceEmbedder
import io.dossier.app.data.face.FaceEmbeddingCalibrationStore
import io.dossier.app.data.face.FaceEmbeddingModelRunner
import io.dossier.app.data.face.FaceEmbeddingModelStore
import io.dossier.app.data.face.FaceEmbeddingThresholds
import io.dossier.app.domain.model.FaceConsistencyMatch

/**
 * Local face-crop consistency service.
 *
 * A calibrated ONNX/TFLite embedding model is preferred when installed. When no
 * model is available, Dossier falls back to a built-in appearance descriptor so
 * the feature remains functional out of the box for reused or near-identical
 * profile photos. The fallback is explicitly not biometric identity recognition.
 */
class FaceEmbeddingService(context: Context) {
    private val faceEmbedder = FaceEmbedder(context)
    private val modelStore = FaceEmbeddingModelStore(context).also { it.ensureModelAvailable() }
    private val calibrationStore = FaceEmbeddingCalibrationStore(context)

    suspend fun extractAndCompare(
        selfieUri: Uri,
        profileUri: Uri
    ): FaceConsistencyMatch =
        compareFaces(
            selfieUri = selfieUri,
            profileUri = profileUri,
            profileUrl = profileUri.toString()
        )

    suspend fun compareFaces(
        selfieUri: Uri,
        profileUri: Uri,
        profileUrl: String
    ): FaceConsistencyMatch {
        val selfieFace = faceEmbedder.extractFaceBitmap(selfieUri)
        if (selfieFace == null) {
            return FaceConsistencyMatch(
                profileUrl = profileUrl,
                similarityScore = 0f,
                warning = "No face detected in selected selfie; visual consistency was not scored."
            )
        }

        val profileFace = faceEmbedder.extractFaceBitmap(profileUri)
        if (profileFace == null) {
            selfieFace.recycle()
            return FaceConsistencyMatch(
                profileUrl = profileUrl,
                similarityScore = 0f,
                warning = "No face detected in profile image; visual consistency was not scored."
            )
        }

        return try {
            if (modelStore.isModelImported()) {
                runModelComparison(selfieFace, profileFace, profileUrl)
            } else {
                runAppearanceFallback(selfieFace, profileFace, profileUrl)
            }
        } finally {
            selfieFace.recycle()
            profileFace.recycle()
        }
    }

    fun isCalibratedReviewScore(score: Float): Boolean =
        calibrationStore.getThresholds()?.isReviewScore(score) == true

    private fun runModelComparison(
        selfieFace: android.graphics.Bitmap,
        profileFace: android.graphics.Bitmap,
        profileUrl: String
    ): FaceConsistencyMatch = runCatching {
        val runner = FaceEmbeddingModelRunner(modelStore.getModelFile())
        val selfieEmbedding = runner.embed(selfieFace)
        val profileEmbedding = runner.embed(profileFace)
        val score = FaceEmbeddingModelRunner.cosineSimilarity(selfieEmbedding, profileEmbedding)
        val thresholds = calibrationStore.getThresholds()
        FaceConsistencyMatch(
            profileUrl = profileUrl,
            similarityScore = score,
            warning = warningForModelScore(score, thresholds)
        )
    }.getOrElse { error ->
        val fallback = runAppearanceFallback(selfieFace, profileFace, profileUrl)
        fallback.copy(
            warning = "${fallback.warning} The configured embedding model failed: " +
                (error.localizedMessage ?: error.javaClass.simpleName)
        )
    }

    private fun runAppearanceFallback(
        selfieFace: android.graphics.Bitmap,
        profileFace: android.graphics.Bitmap,
        profileUrl: String
    ): FaceConsistencyMatch {
        val selfieDescriptor = FaceAppearanceDescriptor.describe(selfieFace)
        val profileDescriptor = FaceAppearanceDescriptor.describe(profileFace)
        val raw = FaceAppearanceDescriptor.cosineSimilarity(selfieDescriptor, profileDescriptor)
        // Appearance descriptors tend to cluster high; remap the useful region
        // conservatively and keep thresholds strict to avoid identity claims.
        val score = ((raw - 0.55f) / 0.45f).coerceIn(0f, 1f)
        val warning = when {
            score >= 0.90f ->
                "Built-in local appearance descriptor reports high visual similarity for the face crops. " +
                    "This usually indicates the same or a near-duplicate photo; it is not biometric identity proof."
            score >= 0.74f ->
                "Built-in local appearance descriptor reports a review-range visual similarity. " +
                    "Treat this only as supporting evidence for possible photo reuse."
            else ->
                "Built-in local appearance descriptor reports low visual similarity. " +
                    "This fallback does not identify people across unrelated photographs."
        }
        return FaceConsistencyMatch(profileUrl, score, warning)
    }

    private fun warningForModelScore(score: Float, thresholds: FaceEmbeddingThresholds?): String =
        when {
            thresholds == null ->
                "Face embedding model produced a cosine score, but no matching calibration thresholds are available."
            thresholds.isSamePersonScore(score) ->
                "Calibrated face model reports a high visual similarity score. Confirm account ownership manually."
            thresholds.isReviewScore(score) ->
                "Calibrated face model reports a review-range similarity score. Treat as supporting evidence only."
            else ->
                "Calibrated face model reports a low similarity score."
        }
}
