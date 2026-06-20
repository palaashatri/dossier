package io.dossier.app.data.ai

import android.content.Context
import io.dossier.app.domain.ai.AiProviderConfig
import io.dossier.app.domain.ai.AiProviderType

class AiProviderConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("ai_provider_configs", Context.MODE_PRIVATE)

    fun get(provider: AiProviderType): AiProviderConfig {
        val defaults = AiProviderConfig.default(provider)
        return AiProviderConfig(
            provider = provider,
            enabled = prefs.getBoolean("${provider.name}.enabled", defaults.enabled),
            apiKey = prefs.getString("${provider.name}.apiKey", defaults.apiKey).orEmpty(),
            baseUrl = prefs.getString("${provider.name}.baseUrl", defaults.baseUrl).orEmpty().ifBlank { defaults.baseUrl },
            model = prefs.getString("${provider.name}.model", defaults.model).orEmpty().ifBlank { defaults.model }
        )
    }

    fun getAll(): List<AiProviderConfig> =
        AiProviderType.entries.map { get(it) }

    fun save(config: AiProviderConfig) {
        prefs.edit()
            .putBoolean("${config.provider.name}.enabled", config.enabled)
            .putString("${config.provider.name}.apiKey", config.apiKey)
            .putString("${config.provider.name}.baseUrl", config.baseUrl)
            .putString("${config.provider.name}.model", config.model)
            .apply()
    }

    fun firstUsableRemoteProvider(): AiProviderConfig? =
        getAll().firstOrNull { config ->
            config.enabled && (!config.provider.needsApiKey || config.apiKey.isNotBlank())
        }
}
