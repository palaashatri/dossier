package io.dossier.app.domain.ai

enum class AiProviderType(
    val displayName: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val needsApiKey: Boolean
) {
    OPENAI(
        displayName = "OpenAI / OpenAI-compatible",
        defaultBaseUrl = "https://api.openai.com/v1",
        defaultModel = "gpt-4o-mini",
        needsApiKey = true
    ),
    ANTHROPIC(
        displayName = "Anthropic",
        defaultBaseUrl = "https://api.anthropic.com/v1",
        defaultModel = "claude-3-5-sonnet-latest",
        needsApiKey = true
    ),
    OLLAMA(
        displayName = "Ollama",
        defaultBaseUrl = "http://10.0.2.2:11434",
        defaultModel = "llama3.2",
        needsApiKey = false
    ),
    HUGGINGFACE(
        displayName = "Hugging Face",
        defaultBaseUrl = "https://api-inference.huggingface.co",
        defaultModel = "google/gemma-2-2b-it",
        needsApiKey = true
    ),
    OPENROUTER(
        displayName = "OpenRouter",
        defaultBaseUrl = "https://openrouter.ai/api/v1",
        defaultModel = "openai/gpt-4o-mini",
        needsApiKey = true
    )
}

data class AiProviderConfig(
    val provider: AiProviderType,
    val enabled: Boolean,
    val apiKey: String,
    val baseUrl: String,
    val model: String,
    val priority: Int
) {
    companion object {
        fun default(provider: AiProviderType): AiProviderConfig =
            AiProviderConfig(
                provider = provider,
                enabled = false,
                apiKey = "",
                baseUrl = provider.defaultBaseUrl,
                model = provider.defaultModel,
                priority = provider.ordinal
            )
    }
}
