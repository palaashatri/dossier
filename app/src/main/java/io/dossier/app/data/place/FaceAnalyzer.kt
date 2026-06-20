package io.dossier.app.data.place

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.InputStream

data class FaceAnalysisResult(val faceDetected: Boolean)

class FaceAnalyzer(private val context: Context) {
    private val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
        .build()

    private val detector = FaceDetection.getClient(options)

    fun analyze(uri: Uri): FaceAnalysisResult {
        val bitmap = loadBitmap(uri) ?: return FaceAnalysisResult(false)
        return analyze(bitmap)
    }

    fun analyze(bitmap: Bitmap): FaceAnalysisResult {
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        val task = detector.process(inputImage)
        val faces = try {
            Tasks.await(task)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }

        return FaceAnalysisResult(faces.isNotEmpty())
    }

    private fun loadBitmap(uri: Uri): Bitmap? {
        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            inputStream.use {
                BitmapFactory.decodeStream(it)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
