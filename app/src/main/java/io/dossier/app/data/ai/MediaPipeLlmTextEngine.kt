package io.dossier.app.data.ai

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import io.dossier.app.domain.ai.LocalAiModelDownloader
import io.dossier.app.domain.ai.LocalAiModelType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaPipeLlmTextEngine(private val context: Context) {

    suspend fun isAvailable(modelType: LocalAiModelType): Boolean = withContext(Dispatchers.IO) {
        modelType == LocalAiModelType.GEMMA_E2B || modelType == LocalAiModelType.GEMMA_E4B
    } && LocalAiModelDownloader.isModelDownloaded(context, modelType)

    suspend fun generate(
        prompt: String,
        modelType: LocalAiModelType,
        maxTokens: Int = 512
    ): String? = withContext(Dispatchers.IO) {
        if (!isAvailable(modelType)) return@withContext null

        val modelFile = LocalAiModelDownloader.getModelFile(context, modelType)
        try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(maxTokens)
                .setTopK(40)
                .setTemperature(0.3f)
                .setRandomSeed(42)
                .build()
            val llm = LlmInference.createFromOptions(context, options)
            try {
                llm.generateResponse(prompt)
            } finally {
                llm.close()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
