package io.dossier.app.data.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

/**
 * ML Kit face detector utility.
 *
 * This class intentionally does not produce embeddings. It only detects faces
 * and extracts a crop that a real ONNX/TFLite face embedding backend can use.
 */
class FaceEmbedder(private val context: Context) {
    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .build()
        )
    }

    fun hasFace(imageUri: Uri): Boolean =
        detectLargestFace(loadBitmap(imageUri)) != null

    fun extractFaceBitmap(imageUri: Uri): Bitmap? {
        val bitmap = loadBitmap(imageUri) ?: return null
        val face = detectLargestFace(bitmap) ?: return null
        val cropRect = expandedCropRect(face.boundingBox, bitmap.width, bitmap.height)
        return Bitmap.createBitmap(bitmap, cropRect.left, cropRect.top, cropRect.width(), cropRect.height())
    }

    private fun detectLargestFace(bitmap: Bitmap?): Face? {
        if (bitmap == null) return null
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val task = detector.process(inputImage)
        val faces = Tasks.await(task)
        return faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
    }

    private fun expandedCropRect(box: Rect, imageWidth: Int, imageHeight: Int): Rect {
        val expandX = (box.width() * FACE_CROP_MARGIN).toInt()
        val expandY = (box.height() * FACE_CROP_MARGIN).toInt()
        return Rect(
            max(0, box.left - expandX),
            max(0, box.top - expandY),
            min(imageWidth, box.right + expandX),
            min(imageHeight, box.bottom + expandY)
        )
    }

    private fun loadBitmap(uri: Uri): Bitmap? {
        val inputStream: InputStream = context.contentResolver.openInputStream(uri) ?: return null
        return inputStream.use { BitmapFactory.decodeStream(it) }
    }

    private companion object {
        const val FACE_CROP_MARGIN = 0.20f
    }
}
