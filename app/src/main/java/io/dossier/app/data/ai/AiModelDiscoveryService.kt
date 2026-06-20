package io.dossier.app.data.ai

import io.dossier.app.domain.ai.AiProviderConfig
import io.dossier.app.domain.ai.AiProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class RemoteAiModel(
    val id: String,
    val displayName: String = id
)

data class AiModelDiscoveryResult(
    val models: List<RemoteAiModel>,
    val live: Boolean,
    val message: String
)

class AiModelDiscoveryService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun discover(config: AiProviderConfig): AiModelDiscoveryResult = withContext(Dispatchers.IO) {
        try {
            val liveModels = when (config.provider) {
                AiProviderType.OPENAI,
                AiProviderType.OPENROUTER -> fetchOpenAiCompatibleModels(config)
                AiProviderType.ANTHROPIC -> fetchAnthropicModels(config)
                AiProviderType.OLLAMA -> fetchOllamaModels(config)
                AiProviderType.HUGGINGFACE -> emptyList()
            }

            if (liveModels.isNotEmpty()) {
                AiModelDiscoveryResult(
                    models = liveModels,
                    live = true,
                    message = "Loaded ${liveModels.size} live model IDs from ${config.provider.displayName}."
                )
            } else {
                presetResult(config.provider, "No live model list returned. Showing curated presets.")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            presetResult(
                config.provider,
                "Could not refresh models: ${e.localizedMessage ?: e.javaClass.simpleName}. Showing curated presets."
            )
        }
    }

    private fun fetchOpenAiCompatibleModels(config: AiProviderConfig): List<RemoteAiModel> {
        val requestBuilder = Request.Builder()
            .url("${config.baseUrl.trimEnd('/')}/models")
            .get()
        if (config.apiKey.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer ${config.apiKey}")
        }
        return execute(requestBuilder.build())?.let { parseOpenAiCompatibleModels(it) }.orEmpty()
    }

    private fun fetchAnthropicModels(config: AiProviderConfig): List<RemoteAiModel> {
        val request = Request.Builder()
            .url("${config.baseUrl.trimEnd('/')}/models")
            .get()
            .header("x-api-key", config.apiKey)
            .header("anthropic-version", "2023-06-01")
            .build()
        return execute(request)?.let { parseDataModels(it) }.orEmpty()
    }

    private fun fetchOllamaModels(config: AiProviderConfig): List<RemoteAiModel> {
        val rootBaseUrl = config.baseUrl.trimEnd('/').removeSuffix("/api")
        val requestBuilder = Request.Builder()
            .url("$rootBaseUrl/api/tags")
            .get()
        if (config.apiKey.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer ${config.apiKey}")
        }
        return execute(requestBuilder.build())?.let { parseOllamaModels(it) }.orEmpty()
    }

    private fun execute(request: Request): String? {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.string()?.takeIf { it.isNotBlank() }
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun presets(provider: AiProviderType): List<RemoteAiModel> = when (provider) {
            AiProviderType.OPENAI -> listOf(
                RemoteAiModel("gpt-5.5", "GPT-5.5"),
                RemoteAiModel("gpt-5.4-mini", "GPT-5.4 Mini"),
                RemoteAiModel("gpt-5.4-nano", "GPT-5.4 Nano"),
                RemoteAiModel("gpt-4o-mini", "GPT-4o Mini")
            )
            AiProviderType.ANTHROPIC -> listOf(
                RemoteAiModel("claude-sonnet-4-5", "Claude Sonnet 4.5"),
                RemoteAiModel("claude-haiku-4-5", "Claude Haiku 4.5"),
                RemoteAiModel("claude-3-5-sonnet-latest", "Claude 3.5 Sonnet Latest")
            )
            AiProviderType.OLLAMA -> listOf(
                RemoteAiModel("llama3.2", "llama3.2"),
                RemoteAiModel("gemma3", "gemma3"),
                RemoteAiModel("qwen2.5", "qwen2.5"),
                RemoteAiModel("mistral", "mistral")
            )
            AiProviderType.HUGGINGFACE -> listOf(
                RemoteAiModel("google/gemma-2-2b-it", "Gemma 2 2B Instruct"),
                RemoteAiModel("google/gemma-2-9b-it", "Gemma 2 9B Instruct"),
                RemoteAiModel("mistralai/Mistral-7B-Instruct-v0.3", "Mistral 7B Instruct")
            )
            AiProviderType.OPENROUTER -> listOf(
                RemoteAiModel("openai/gpt-5.5", "OpenAI GPT-5.5"),
                RemoteAiModel("openai/gpt-5.4-mini", "OpenAI GPT-5.4 Mini"),
                RemoteAiModel("anthropic/claude-sonnet-4.5", "Claude Sonnet 4.5"),
                RemoteAiModel("google/gemini-3-pro", "Gemini 3 Pro")
            )
        }

        fun presetResult(provider: AiProviderType, message: String): AiModelDiscoveryResult =
            AiModelDiscoveryResult(
                models = presets(provider),
                live = false,
                message = if (provider == AiProviderType.HUGGINGFACE) {
                    "Hugging Face does not expose a small key-scoped chat model list for this endpoint. Showing curated text-generation presets."
                } else {
                    message
                }
            )

        fun parseOpenAiCompatibleModels(responseBody: String): List<RemoteAiModel> {
            val models = parseDataModels(responseBody)
            return models
                .filterNot { isNonChatModel(it.id) }
                .distinctBy { it.id }
        }

        fun parseDataModels(responseBody: String): List<RemoteAiModel> {
            val root = json.parseToJsonElement(responseBody).jsonObject
            val data = root["data"]?.jsonArray ?: return emptyList()
            return data.mapNotNull { element ->
                val obj = element.jsonObject
                val id = obj.stringField("id") ?: return@mapNotNull null
                val name = obj.stringField("name")
                    ?: obj.stringField("display_name")
                    ?: id
                RemoteAiModel(id = id, displayName = name)
            }
        }

        fun parseOllamaModels(responseBody: String): List<RemoteAiModel> {
            val root = json.parseToJsonElement(responseBody).jsonObject
            val data = root["models"]?.jsonArray ?: return emptyList()
            return data.mapNotNull { element ->
                val obj = element.jsonObject
                val id = obj.stringField("model") ?: obj.stringField("name") ?: return@mapNotNull null
                val parameterSize = obj["details"]?.jsonObject?.stringField("parameter_size")
                RemoteAiModel(
                    id = id,
                    displayName = listOfNotNull(id, parameterSize?.let { "($it)" }).joinToString(" ")
                )
            }.distinctBy { it.id }
        }

        private fun isNonChatModel(id: String): Boolean {
            val normalized = id.lowercase()
            return listOf(
                "embedding", "embed", "whisper", "tts", "dall-e", "image",
                "moderation", "omni-moderation", "babbage", "davinci"
            ).any { normalized.contains(it) }
        }

        private fun JsonObject.stringField(name: String): String? =
            this[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }
}
