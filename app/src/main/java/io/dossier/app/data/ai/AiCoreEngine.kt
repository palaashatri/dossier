package io.dossier.app.data.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import io.dossier.app.domain.ai.LocalAiAnalysisResult
import io.dossier.app.domain.ai.LocalAiEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

/**
 * AICore (Gemini Nano via Android System Intelligence) engine.
 *
 * Uses ML Kit's GenAI Prompt API, which talks to Gemini Nano through the Android
 * AICore system service on supported devices. Availability is based on the
 * official feature status API, not package-presence heuristics.
 */
class AiCoreEngine(context: Context) : LocalAiEngine {
    private val appContext = context.applicationContext

    override val name: String = "Google AICore (Gemini Nano)"

    suspend fun status(): AiCoreStatus =
        runCatching {
            withModel { model ->
                when (model.checkStatus()) {
                    FeatureStatus.AVAILABLE -> AiCoreStatus.Available
                    FeatureStatus.DOWNLOADABLE -> AiCoreStatus.Downloadable
                    FeatureStatus.DOWNLOADING -> AiCoreStatus.Downloading
                    FeatureStatus.UNAVAILABLE -> AiCoreStatus.Unavailable
                    else -> AiCoreStatus.Unavailable
                }
            }
        }.getOrElse { error ->
            AiCoreStatus.Error(error.localizedMessage ?: error.javaClass.simpleName)
        }

    suspend fun prepare(): AiCoreStatus =
        runCatching {
            withModel { model ->
                when (model.checkStatus()) {
                    FeatureStatus.AVAILABLE -> AiCoreStatus.Available
                    FeatureStatus.DOWNLOADABLE -> {
                        if (download(model)) AiCoreStatus.Available else AiCoreStatus.Downloading
                    }
                    FeatureStatus.DOWNLOADING -> AiCoreStatus.Downloading
                    FeatureStatus.UNAVAILABLE -> AiCoreStatus.Unavailable
                    else -> AiCoreStatus.Unavailable
                }
            }
        }.getOrElse { error ->
            AiCoreStatus.Error(error.localizedMessage ?: error.javaClass.simpleName)
        }

    override suspend fun isAvailable(): Boolean =
        status() == AiCoreStatus.Available

    override suspend fun analyzeImage(imageUri: Uri): LocalAiAnalysisResult? {
        val bitmap = decodeBitmap(imageUri) ?: return null
        return runCatching {
            withModel { model ->
                if (!ensureReady(model, allowDownload = true)) {
                    null
                } else {
                    val response = model.generateContent(
                        generateContentRequest(ImagePart(bitmap), TextPart(IMAGE_SCAN_PROMPT)) {
                            temperature = 0f
                            candidateCount = 1
                            maxOutputTokens = 200
                        }
                    )
                    parseAnalysisResponse(response.candidates.firstOrNull()?.text.orEmpty())
                }
            }
        }.getOrNull()
    }

    private suspend fun decodeBitmap(imageUri: Uri): Bitmap? =
        withContext(Dispatchers.IO) {
            appContext.contentResolver.openInputStream(imageUri)?.use { input ->
                BitmapFactory.decodeStream(input)
            }
        }

    private suspend fun <T> withModel(block: suspend (GenerativeModel) -> T): T {
        val model = Generation.getClient()
        return try {
            block(model)
        } finally {
            model.close()
        }
    }

    private suspend fun ensureReady(model: GenerativeModel, allowDownload: Boolean): Boolean =
        when (model.checkStatus()) {
            FeatureStatus.AVAILABLE -> true
            FeatureStatus.DOWNLOADABLE -> allowDownload && download(model)
            else -> false
        }

    private suspend fun download(model: GenerativeModel): Boolean {
        var completed = false
        model.download().collect { status ->
            when (status) {
                DownloadStatus.DownloadCompleted -> completed = true
                is DownloadStatus.DownloadFailed -> throw status.e
                else -> Unit
            }
        }
        return completed || model.checkStatus() == FeatureStatus.AVAILABLE
    }

    companion object {
        private const val IMAGE_SCAN_PROMPT =
            "Analyze this image for a safety/location scan. Return exactly:\n" +
                "TEXT: visible text or none\n" +
                "LANDMARKS: visible landmarks or places, comma-separated, or none\n" +
                "FACE: yes or no\n" +
                "Use only visible evidence."

        internal fun parseAnalysisResponse(raw: String): LocalAiAnalysisResult {
            val textField = field(raw, "TEXT")
            val extractedText = when {
                textField == null -> raw.trim().takeIf { it.isNotEmpty() }
                textField.isNone() -> null
                else -> textField
            }
            val detectedLandmarks = field(raw, "LANDMARKS")
                ?.takeUnless { it.isNone() }
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
            val containsFace = field(raw, "FACE")
                ?.lowercase()
                ?.let { it.startsWith("yes") || it.startsWith("true") }
                ?: false
            return LocalAiAnalysisResult(
                extractedText = extractedText,
                detectedLandmarks = detectedLandmarks,
                containsFace = containsFace
            )
        }

        private fun field(raw: String, label: String): String? {
            val pattern = Regex(
                "\\b${Regex.escape(label)}\\s*:\\s*(.*?)(?=\\s+\\b(?:TEXT|LANDMARKS|FACE)\\s*:|$)",
                RegexOption.IGNORE_CASE
            )
            return pattern.find(raw)
                ?.groupValues
                ?.get(1)
                ?.trim()
        }

        private fun String.isNone(): Boolean =
            equals("none", ignoreCase = true) || equals("n/a", ignoreCase = true) || equals("unknown", ignoreCase = true)
    }
}

sealed class AiCoreStatus {
    data object Available : AiCoreStatus()
    data object Downloadable : AiCoreStatus()
    data object Downloading : AiCoreStatus()
    data object Unavailable : AiCoreStatus()
    data class Error(val message: String) : AiCoreStatus()

    val canRun: Boolean
        get() = this == Available

    val label: String
        get() = when (this) {
            Available -> "Available"
            Downloadable -> "Downloadable"
            Downloading -> "Downloading"
            Unavailable -> "Unavailable"
            is Error -> "Error"
        }

    val detail: String
        get() = when (this) {
            Available -> "Gemini Nano is downloaded and available through AICore."
            Downloadable -> "Gemini Nano can be downloaded on this supported device."
            Downloading -> "Gemini Nano is currently downloading through AICore."
            Unavailable -> "Gemini Nano is not supported, not configured yet, or blocked on this device."
            is Error -> message
        }
}
