package io.dossier.app.domain.face

import android.content.Context
import android.net.Uri
import io.dossier.app.data.face.FaceEmbedder
import io.dossier.app.data.face.FaceEmbeddingCalibrationStore
import io.dossier.app.data.face.FaceEmbeddingModelStore
import io.dossier.app.data.face.FaceEmbeddingModelRunner
import io.dossier.app.data.face.FaceEmbeddingThresholds
import io.dossier.app.domain.model.FaceConsistencyMatch

/**
 * Honest face consistency service.
 *
 * Earlier versions fabricated a 128-dimensional embedding from ML Kit face
 * bounding boxes. This version runs a user-imported ONNX/TFLite model when one
 * is available, otherwise it reports face presence without a fake score.
 */
class FaceEmbeddingService(context: Context) {
    private val faceEmbedder = FaceEmbedder(context)
    private val modelStore = FaceEmbeddingModelStore(context)
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
                warning = "No face detected in selected selfie; face consistency was not scored."
            )
        }

        val profileFace = faceEmbedder.extractFaceBitmap(profileUri)
        if (profileFace == null) {
            return FaceConsistencyMatch(
                profileUrl = profileUrl,
                similarityScore = 0f,
                warning = "No face detected in profile image; face consistency was not scored."
            )
        }

        if (!modelStore.isModelImported()) {
            return FaceConsistencyMatch(
                profileUrl = profileUrl,
                similarityScore = 0f,
                warning = "Face presence confirmed, but no face embedding model is imported."
            )
        }

        return runCatching {
            val runner = FaceEmbeddingModelRunner(modelStore.getModelFile())
            val selfieEmbedding = runner.embed(selfieFace)
            val profileEmbedding = runner.embed(profileFace)
            val score = FaceEmbeddingModelRunner.cosineSimilarity(selfieEmbedding, profileEmbedding)
            val thresholds = calibrationStore.getThresholds()
            FaceConsistencyMatch(
                profileUrl = profileUrl,
                similarityScore = score,
                warning = warningForScore(score, thresholds)
            )
        }.getOrElse { error ->
            FaceConsistencyMatch(
                profileUrl = profileUrl,
                similarityScore = 0f,
                warning = "Imported face model could not run: ${error.localizedMessage ?: error.javaClass.simpleName}"
            )
        }
    }

    fun isCalibratedReviewScore(score: Float): Boolean =
        calibrationStore.getThresholds()?.isReviewScore(score) == true

    private fun warningForScore(score: Float, thresholds: FaceEmbeddingThresholds?): String =
        when {
            thresholds == null ->
                "Imported face model produced a cosine score, but no calibrated threshold file is imported."
            thresholds.isSamePersonScore(score) ->
                "Calibrated face model reports a high visual similarity score. Confirm account ownership manually."
            thresholds.isReviewScore(score) ->
                "Calibrated face model reports a review-range similarity score. Treat as supporting evidence only."
            else ->
                "Calibrated face model reports a low similarity score."
        }
}
