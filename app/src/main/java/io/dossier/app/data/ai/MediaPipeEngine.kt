package io.dossier.app.data.ai

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import io.dossier.app.domain.ai.LocalAiAnalysisResult
import io.dossier.app.domain.ai.LocalAiEngine
import io.dossier.app.domain.ai.LocalAiModelDownloader
import io.dossier.app.domain.ai.LocalAiModelType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.InputStream
import java.nio.channels.FileChannel

/**
 * MediaPipe Tasks engine for downloaded local vision models (Gemma/PaliGemma).
 *
 * HONESTY: the previous implementation returned hardcoded mock strings ("Caution:
 * High Voltage", "transmission tower") on any failure, and `isAvailable()` treated
 * the 1KB dummy file from simulateMockDownload as a real model. Both are fixed:
 *  - isAvailable() requires a real model file (> 4KB) or a real bundled asset.
 *  - On any load/inference failure we return null so the caller degrades honestly.
 *
 * Note: ML Kit Vision (OCR/face/labels) is the primary, always-available vision
 * path now — see TextRecognizer / ImageLabeler / FaceAnalyzer. This engine is
 * only relevant if a genuine downloadable model is present.
 */
class MediaPipeEngine(private val context: Context) : LocalAiEngine {
    override val name: String = "MediaPipe Tasks (Local vision model)"

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        val hasDownloadedModel = LocalAiModelDownloader.isModelDownloaded(context, LocalAiModelType.GEMMA_4_E2B) ||
            LocalAiModelDownloader.isModelDownloaded(context, LocalAiModelType.PALIGEMMA)
        if (hasDownloadedModel) return@withContext true

        val hasAsset = try {
            val assetsList = context.assets.list("") ?: emptyArray()
            assetsList.any { it.endsWith(".tflite") }
        } catch (e: Exception) {
            false
        }
        hasAsset
    }

    override suspend fun analyzeImage(imageUri: Uri): LocalAiAnalysisResult? = withContext(Dispatchers.IO) {
        if (!isAvailable()) return@withContext null

        try {
            val baseOptionsBuilder = BaseOptions.builder()

            val modelSource: Any = when {
                LocalAiModelDownloader.isModelDownloaded(context, LocalAiModelType.GEMMA_4_E2B) -> {
                    val file = LocalAiModelDownloader.getModelFile(context, LocalAiModelType.GEMMA_4_E2B)
                    val fis = FileInputStream(file)
                    val channel = fis.channel
                    val buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
                    fis.close()
                    buffer
                }
                LocalAiModelDownloader.isModelDownloaded(context, LocalAiModelType.PALIGEMMA) -> {
                    val file = LocalAiModelDownloader.getModelFile(context, LocalAiModelType.PALIGEMMA)
                    val fis = FileInputStream(file)
                    val channel = fis.channel
                    val buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
                    fis.close()
                    buffer
                }
                else -> {
                    val assets = context.assets.list("") ?: emptyArray()
                    val modelName = assets.firstOrNull { it.endsWith(".tflite") }
                        ?: return@withContext null
                    modelName
                }
            }

            when (modelSource) {
                is java.nio.ByteBuffer -> baseOptionsBuilder.setModelAssetBuffer(modelSource)
                is String -> baseOptionsBuilder.setModelAssetPath(modelSource)
                else -> return@withContext null
            }

            val baseOptions = baseOptionsBuilder.build()

            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(baseOptions)
                .setScoreThreshold(0.5f)
                .setRunningMode(RunningMode.IMAGE)
                .build()

            val detector = ObjectDetector.createFromOptions(context, options)

            val inputStream: InputStream? = context.contentResolver.openInputStream(imageUri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            if (bitmap == null) {
                detector.close()
                return@withContext null
            }

            val mpImage = com.google.mediapipe.framework.image.BitmapImageBuilder(bitmap).build()
            val detectionResult = detector.detect(mpImage)

            var faceDetected = false
            val landmarks = mutableListOf<String>()

            detectionResult.detections().forEach { detection ->
                detection.categories().forEach { category ->
                    val name = category.categoryName().lowercase()
                    if (name == "person" || name == "face") {
                        faceDetected = true
                    } else if (name != "background") {
                        landmarks.add(name)
                    }
                }
            }

            detector.close()

            // No fabricated OCR text — only real detected object labels are reported.
            // OCR is handled by the dedicated TextRecognizer (ML Kit).
            LocalAiAnalysisResult(
                extractedText = null,
                detectedLandmarks = landmarks.distinct(),
                containsFace = faceDetected
            )
        } catch (e: Exception) {
            e.printStackTrace()
            // No mock fallback — return null so the caller degrades honestly.
            null
        }
    }
}
