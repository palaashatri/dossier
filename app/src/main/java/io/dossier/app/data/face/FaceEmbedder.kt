package io.dossier.app.data.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.InputStream

/**
 * Honest face detection wrapper — detects whether a face is present in an image.
 *
 * HONESTY: the prior implementation fabricated 128-dim "embeddings" from face
 * bounding-box geometry + random Gaussian noise, which FaceEmbeddingService
 * then compared via cosine similarity to produce fake "visual similarity scores."
 * That is removed. This class now reports face presence only — no embedding
 * model exists in this build, so no similarity scoring is possible.
 *
 * A real FaceNet/ONNX model (the facenet.onnx AGENTS.md references) would be
 * needed for genuine embeddings; until then, face comparison is honestly
 * unavailable.
 */
class FaceEmbedder(private val context: Context) {

    private val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
        .build()

    private val detector = FaceDetection.getClient(options)

    /** Returns true if at least one face is detected in the image. */
    fun hasFace(imageUri: Uri): Boolean {
        val bitmap = loadBitmap(imageUri) ?: return false
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val task = detector.process(inputImage)
        val faces = try {
            Tasks.await(task)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
        return faces.isNotEmpty()
    }

    private fun loadBitmap(uri: Uri): Bitmap? {
        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
