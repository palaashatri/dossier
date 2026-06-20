package io.dossier.app.data.ai

import android.content.Context
import android.net.Uri
import io.dossier.app.domain.ai.LocalAiAnalysisResult
import io.dossier.app.domain.ai.LocalAiModelType
import io.dossier.app.domain.scanner.ScanSession

class HybridAiClient(private val context: Context) {
    private val aiCoreEngine = AiCoreEngine(context)
    private val mediaPipeEngine = MediaPipeEngine(context)

    suspend fun analyzeImage(imageUri: Uri): LocalAiAnalysisResult {
        val selected = ScanSession.selectedModel.value

        try {
            // AICore / MediaPipe are best-effort downloadable/LLM engines.
            // MLKIT_VISION is handled directly by the dedicated TextRecognizer /
            // ImageLabeler / FaceAnalyzer classes (not via this client).
            if (selected == LocalAiModelType.AICORE && aiCoreEngine.isAvailable()) {
                val result = aiCoreEngine.analyzeImage(imageUri)
                if (result != null) return result
            } else if (selected == LocalAiModelType.PALIGEMMA && mediaPipeEngine.isAvailable()) {
                val result = mediaPipeEngine.analyzeImage(imageUri)
                if (result != null) return result
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Dynamic fallback: try whatever optional engine is available.
        try {
            if (aiCoreEngine.isAvailable()) {
                val result = aiCoreEngine.analyzeImage(imageUri)
                if (result != null) return result
            }
            if (mediaPipeEngine.isAvailable()) {
                val result = mediaPipeEngine.analyzeImage(imageUri)
                if (result != null) return result
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Final fallback: no optional AI engine ran. Return an honest empty
        // result (no fabricated text/landmarks) — callers that need real vision
        // should use ML Kit Vision (TextRecognizer / ImageLabeler) directly.
        return LocalAiAnalysisResult(
            extractedText = null,
            detectedLandmarks = emptyList(),
            containsFace = false
        )
    }
}
