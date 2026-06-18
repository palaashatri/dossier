package io.dossier.app.domain.face

import android.content.Context
import android.net.Uri
import io.dossier.app.data.face.FaceEmbedder
import io.dossier.app.domain.model.FaceConsistencyMatch

/**
 * Honest face-consistency service.
 *
 * HONESTY: the prior implementation computed cosine similarity over
 * FaceEmbedder's fabricated 128-dim vectors (box geometry + random noise) and
 * reported the resulting pseudo-score as a "visual similarity" measurement.
 * That was dishonest — no real embedding model exists in this build.
 *
 * This version reports face presence only. If a real FaceNet/ONNX model is
 * wired later, [extractAndCompare] can be restored to genuine embeddings.
 */
class FaceEmbeddingService(private val context: Context) {
    private val faceEmbedder = FaceEmbedder(context)

    /**
     * Reports whether a face is detected in each image. Does NOT compute a
     * similarity score — there is no embedding model. The returned match uses
     * a neutral warning so the UI can present this honestly.
     */
    suspend fun extractAndCompare(
        selfieUri: Uri,
        profileUri: Uri
    ): FaceConsistencyMatch {
        val selfieHasFace = faceEmbedder.hasFace(selfieUri)
        val profileHasFace = faceEmbedder.hasFace(profileUri)

        val warning = when {
            !selfieHasFace -> "No face detected in selfie — cannot compare."
            !profileHasFace -> "No face detected in profile image — cannot compare."
            else -> "Faces detected in both, but no embedding model available for similarity scoring."
        }

        return FaceConsistencyMatch(
            profileUrl = profileUri.toString(),
            // 0f signals "no score" honestly — the UI must not present this as a
            // real similarity. (ReportScreen shows the honest warning instead.)
            similarityScore = 0f,
            warning = warning
        )
    }
}
