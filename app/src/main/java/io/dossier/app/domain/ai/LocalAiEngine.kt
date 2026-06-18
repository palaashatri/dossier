package io.dossier.app.domain.ai

import android.net.Uri

data class LocalAiAnalysisResult(
    val extractedText: String?,
    val detectedLandmarks: List<String>,
    val containsFace: Boolean
)

interface LocalAiEngine {
    val name: String
    suspend fun analyzeImage(imageUri: Uri): LocalAiAnalysisResult?
    suspend fun isAvailable(): Boolean
}
