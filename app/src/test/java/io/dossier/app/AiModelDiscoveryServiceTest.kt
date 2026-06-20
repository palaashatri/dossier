package io.dossier.app

import io.dossier.app.data.ai.AiModelDiscoveryService
import io.dossier.app.domain.ai.AiProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiModelDiscoveryServiceTest {

    @Test
    fun parseOpenAiCompatibleModels_keepsChatModelsAndFiltersNonChatModels() {
        val models = AiModelDiscoveryService.parseOpenAiCompatibleModels(
            """
            {
              "object": "list",
              "data": [
                {"id": "gpt-5.4-mini", "object": "model"},
                {"id": "text-embedding-3-large", "object": "model"},
                {"id": "omni-moderation-latest", "object": "model"}
              ]
            }
            """.trimIndent()
        )

        assertEquals(listOf("gpt-5.4-mini"), models.map { it.id })
    }

    @Test
    fun parseOpenRouterStyleModels_usesFriendlyNameWhenAvailable() {
        val models = AiModelDiscoveryService.parseOpenAiCompatibleModels(
            """
            {
              "data": [
                {"id": "anthropic/claude-sonnet-4.5", "name": "Claude Sonnet 4.5"}
              ]
            }
            """.trimIndent()
        )

        assertEquals("anthropic/claude-sonnet-4.5", models.single().id)
        assertEquals("Claude Sonnet 4.5", models.single().displayName)
    }

    @Test
    fun parseOllamaModels_readsModelNameAndParameterSize() {
        val models = AiModelDiscoveryService.parseOllamaModels(
            """
            {
              "models": [
                {
                  "name": "gemma3:latest",
                  "model": "gemma3:latest",
                  "details": {"parameter_size": "4.0B"}
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals("gemma3:latest", models.single().id)
        assertEquals("gemma3:latest (4.0B)", models.single().displayName)
    }

    @Test
    fun presets_existForEveryProvider() {
        AiProviderType.entries.forEach { provider ->
            assertFalse("Missing preset for $provider", AiModelDiscoveryService.presets(provider).isEmpty())
        }
        assertTrue(AiModelDiscoveryService.presets(AiProviderType.OPENROUTER).any { it.id.contains("/") })
    }
}
