package io.dossier.app.data.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageclassifier.ImageClassifier
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import io.dossier.app.domain.ai.LocalAiAnalysisResult
import io.dossier.app.domain.ai.LocalAiEngine
import io.dossier.app.domain.ai.LocalAiModelDownloader
import io.dossier.app.domain.ai.LocalAiModelType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * MediaPipe Tasks engine for imported/bundled local vision models.
 *
 * HONESTY:
 * - Multimodal scene *descriptions* (free-form text about what is in the image)
 *   are handled by [AiCoreEngine] / Gemini Nano, not this path.
 * - This engine runs MediaPipe **ImageClassifier** first (scene-level labels),
 *   then falls back to **ObjectDetector** for object-category labels.
 * - It never fabricates OCR text or landmarks. OCR stays with ML Kit
 *   TextRecognizer. On any load/inference failure it returns null so callers
 *   degrade honestly.
 */
class MediaPipeEngine(private val context: Context) : LocalAiEngine {
    override val name: String = "MediaPipe Tasks (Local vision labels)"

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        resolveModelSource() != null
    }

    override suspend fun analyzeImage(imageUri: Uri): LocalAiAnalysisResult? = withContext(Dispatchers.IO) {
        val modelSource = resolveModelSource() ?: return@withContext null
        val bitmap = decodeBitmap(imageUri) ?: return@withContext null
        val mpImage = BitmapImageBuilder(bitmap).build()

        runClassifier(modelSource, mpImage)
            ?: runObjectDetector(modelSource, mpImage)
    }

    private fun resolveModelSource(): ModelSource? {
        if (LocalAiModelDownloader.isModelDownloaded(context, LocalAiModelType.PALIGEMMA)) {
            return runCatching {
                val file = LocalAiModelDownloader.getModelFile(context, LocalAiModelType.PALIGEMMA)
                FileInputStream(file).use { fis ->
                    val channel = fis.channel
                    val buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
                    ModelSource.Buffer(buffer)
                }
            }.getOrNull()
        }

        val assets = runCatching { context.assets.list("") ?: emptyArray() }.getOrDefault(emptyArray())
        val modelName = assets.firstOrNull { it.endsWith(".tflite") } ?: return null
        return ModelSource.AssetPath(modelName)
    }

    private fun decodeBitmap(imageUri: Uri): Bitmap? =
        context.contentResolver.openInputStream(imageUri)?.use { input ->
            BitmapFactory.decodeStream(input)
        }

    private fun runClassifier(
        modelSource: ModelSource,
        mpImage: com.google.mediapipe.framework.image.MPImage
    ): LocalAiAnalysisResult? =
        runCatching {
            val options = ImageClassifier.ImageClassifierOptions.builder()
                .setBaseOptions(modelSource.toBaseOptions())
                .setRunningMode(RunningMode.IMAGE)
                .setMaxResults(8)
                .setScoreThreshold(0.25f)
                .build()
            val classifier = ImageClassifier.createFromOptions(context, options)
            try {
                val result = classifier.classify(mpImage)
                val categories = result.classificationResult()
                    ?.classifications()
                    .orEmpty()
                    .flatMap { it.categories().orEmpty() }

                if (categories.isEmpty()) return@runCatching null

                var faceDetected = false
                val labels = mutableListOf<String>()
                categories.forEach { category ->
                    val name = category.categoryName().orEmpty().trim()
                    if (name.isEmpty()) return@forEach
                    val lower = name.lowercase()
                    if (lower == "person" || lower == "face" || lower.contains("portrait")) {
                        faceDetected = true
                    } else if (lower != "background") {
                        labels.add(name)
                    }
                }
                LocalAiAnalysisResult(
                    extractedText = null,
                    detectedLandmarks = labels.distinct(),
                    containsFace = faceDetected
                )
            } finally {
                classifier.close()
            }
        }.getOrNull()

    private fun runObjectDetector(
        modelSource: ModelSource,
        mpImage: com.google.mediapipe.framework.image.MPImage
    ): LocalAiAnalysisResult? =
        runCatching {
            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(modelSource.toBaseOptions())
                .setScoreThreshold(0.5f)
                .setRunningMode(RunningMode.IMAGE)
                .build()
            val detector = ObjectDetector.createFromOptions(context, options)
            try {
                val detectionResult = detector.detect(mpImage)
                var faceDetected = false
                val landmarks = mutableListOf<String>()
                detectionResult.detections().forEach { detection ->
                    detection.categories().forEach { category ->
                        val name = category.categoryName().lowercase()
                        if (name == "person" || name == "face") {
                            faceDetected = true
                        } else if (name != "background" && name.isNotBlank()) {
                            landmarks.add(name)
                        }
                    }
                }
                if (landmarks.isEmpty() && !faceDetected) return@runCatching null
                LocalAiAnalysisResult(
                    extractedText = null,
                    detectedLandmarks = landmarks.distinct(),
                    containsFace = faceDetected
                )
            } finally {
                detector.close()
            }
        }.getOrNull()

    private sealed class ModelSource {
        data class Buffer(val buffer: ByteBuffer) : ModelSource()
        data class AssetPath(val path: String) : ModelSource()

        fun toBaseOptions(): BaseOptions {
            val builder = BaseOptions.builder()
            when (this) {
                is Buffer -> builder.setModelAssetBuffer(buffer)
                is AssetPath -> builder.setModelAssetPath(path)
            }
            return builder.build()
        }
    }
}
