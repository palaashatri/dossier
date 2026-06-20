package io.dossier.app.data.ai

import android.content.Context
import io.dossier.app.domain.ai.AiProviderConfig
import io.dossier.app.domain.ai.AiProviderType
import io.dossier.app.domain.ai.LocalAiModelType
import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.ProfileScanResult
import io.dossier.app.domain.scanner.ScanSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class AiInsightService(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun summarizeDossier(
        input: IdentityInput,
        profileResults: List<ProfileScanResult>,
        findings: List<Finding>
    ): String? {
        if (findings.isEmpty() && profileResults.none { it.exists }) return null

        val prompt = buildDossierSummaryPrompt(input, profileResults, findings)
        val selectedLocal = ScanSession.selectedModel.value
        if (selectedLocal == LocalAiModelType.GEMMA_E2B || selectedLocal == LocalAiModelType.GEMMA_E4B) {
            val local = MediaPipeLlmTextEngine(context)
            val localSummary = local.generate(prompt, selectedLocal)
            if (!localSummary.isNullOrBlank()) return localSummary.trim()
        }

        val config = AiProviderConfigStore(context).firstUsableRemoteProvider()
        if (config != null) {
            val remoteSummary = generateRemote(config, prompt)?.trim()?.takeIf { it.isNotBlank() }
            if (remoteSummary != null) return remoteSummary
        }

        return buildBaselineSummary(input, profileResults, findings)
    }

    suspend fun generateRemote(config: AiProviderConfig, prompt: String): String? =
        withContext(Dispatchers.IO) {
            try {
                when (config.provider) {
                    AiProviderType.OPENAI,
                    AiProviderType.OPENROUTER -> callOpenAiCompatible(config, prompt)
                    AiProviderType.ANTHROPIC -> callAnthropic(config, prompt)
                    AiProviderType.OLLAMA -> callOllama(config, prompt)
                    AiProviderType.HUGGINGFACE -> callHuggingFace(config, prompt)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

    private fun callOpenAiCompatible(config: AiProviderConfig, prompt: String): String? {
        val body = buildJsonObject {
            put("model", JsonPrimitive(config.model))
            put("temperature", JsonPrimitive(0.2))
            put("messages", buildJsonArray {
                add(chatMessage("system", SYSTEM_PROMPT))
                add(chatMessage("user", prompt))
            })
        }.toString()

        val requestBuilder = Request.Builder()
            .url("${config.baseUrl.trimEnd('/')}/chat/completions")
            .post(body.toRequestBody(mediaType))
            .header("Content-Type", "application/json")

        if (config.apiKey.isNotBlank()) requestBuilder.header("Authorization", "Bearer ${config.apiKey}")
        if (config.provider == AiProviderType.OPENROUTER) {
            requestBuilder.header("HTTP-Referer", "https://dossier.local")
            requestBuilder.header("X-Title", "Dossier")
        }

        return executeJson(requestBuilder.build())?.jsonObject
            ?.get("choices")?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject
            ?.get("content")?.jsonPrimitive?.contentOrNull
    }

    private fun callAnthropic(config: AiProviderConfig, prompt: String): String? {
        val body = buildJsonObject {
            put("model", JsonPrimitive(config.model))
            put("max_tokens", JsonPrimitive(700))
            put("temperature", JsonPrimitive(0.2))
            put("system", JsonPrimitive(SYSTEM_PROMPT))
            put("messages", buildJsonArray {
                add(chatMessage("user", prompt))
            })
        }.toString()

        val request = Request.Builder()
            .url("${config.baseUrl.trimEnd('/')}/messages")
            .post(body.toRequestBody(mediaType))
            .header("Content-Type", "application/json")
            .header("x-api-key", config.apiKey)
            .header("anthropic-version", "2023-06-01")
            .build()

        return executeJson(request)?.jsonObject
            ?.get("content")?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("text")?.jsonPrimitive?.contentOrNull
    }

    private fun callOllama(config: AiProviderConfig, prompt: String): String? {
        val body = buildJsonObject {
            put("model", JsonPrimitive(config.model))
            put("stream", JsonPrimitive(false))
            put("messages", buildJsonArray {
                add(chatMessage("system", SYSTEM_PROMPT))
                add(chatMessage("user", prompt))
            })
        }.toString()

        val requestBuilder = Request.Builder()
            .url("${config.baseUrl.trimEnd('/')}/api/chat")
            .post(body.toRequestBody(mediaType))
            .header("Content-Type", "application/json")
        if (config.apiKey.isNotBlank()) requestBuilder.header("Authorization", "Bearer ${config.apiKey}")

        return executeJson(requestBuilder.build())?.jsonObject
            ?.get("message")?.jsonObject
            ?.get("content")?.jsonPrimitive?.contentOrNull
    }

    private fun callHuggingFace(config: AiProviderConfig, prompt: String): String? {
        val body = buildJsonObject {
            put("inputs", JsonPrimitive("$SYSTEM_PROMPT\n\n$prompt"))
            put("parameters", buildJsonObject {
                put("max_new_tokens", JsonPrimitive(700))
                put("temperature", JsonPrimitive(0.2))
                put("return_full_text", JsonPrimitive(false))
            })
        }.toString()

        val request = Request.Builder()
            .url("${config.baseUrl.trimEnd('/')}/models/${config.model.trimStart('/')}")
            .post(body.toRequestBody(mediaType))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer ${config.apiKey}")
            .build()

        val response = executeJson(request) ?: return null
        return when (response) {
            is JsonArray -> response.firstOrNull()?.jsonObject
                ?.get("generated_text")?.jsonPrimitive?.contentOrNull
            is JsonObject -> response["generated_text"]?.jsonPrimitive?.contentOrNull
                ?: response["summary_text"]?.jsonPrimitive?.contentOrNull
            else -> null
        }
    }

    private fun executeJson(request: Request): kotlinx.serialization.json.JsonElement? {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return null
            return json.parseToJsonElement(body)
        }
    }

    companion object {
        private const val SYSTEM_PROMPT =
            "You summarize consented self-audit privacy findings. Be concise, cautious, and never claim identity proof from weak evidence."

        fun buildDossierSummaryPrompt(
            input: IdentityInput,
            profileResults: List<ProfileScanResult>,
            findings: List<Finding>
        ): String {
            val confirmed = profileResults.filter { it.exists && it.verified }
            val review = profileResults.filter { it.exists && !it.verified }
            val findingLines = findings.take(40).joinToString("\n") { finding ->
                "- ${finding.type}: ${finding.value} (${(finding.confidence * 100).toInt()}%, ${finding.risk}) source=${finding.sourceUrl ?: "local"}"
            }
            val profileLines = (confirmed.take(12) + review.take(12)).joinToString("\n") { result ->
                "- ${if (result.verified) "verified" else "review"} ${result.candidate.platform}: ${result.candidate.url} (${(result.candidate.confidence * 100).toInt()}%)"
            }
            return """
                Subject: ${input.fullName.ifBlank { "Unknown" }}
                Confirmed profiles: ${confirmed.size}
                Review candidates: ${review.size}
                Findings:
                $findingLines

                Profiles:
                $profileLines

                Produce:
                1. A three-sentence executive summary.
                2. The top five exposure risks.
                3. The top five remediation actions.
                4. Any evidence that should be manually verified.
            """.trimIndent()
        }

        private fun chatMessage(role: String, content: String): JsonObject =
            buildJsonObject {
                put("role", JsonPrimitive(role))
                put("content", JsonPrimitive(content))
            }

        fun buildBaselineSummary(
            input: IdentityInput,
            profileResults: List<ProfileScanResult>,
            findings: List<Finding>
        ): String {
            val confirmed = profileResults.filter { it.exists && it.verified }
            val review = profileResults.filter { it.exists && !it.verified }
            val topFindings = findings
                .sortedWith(compareByDescending<Finding> { riskWeight(it.risk) }.thenByDescending { it.confidence })
                .take(5)
            val remediation = topFindings
                .map { it.remediation }
                .distinct()
                .take(5)

            val subject = input.fullName.ifBlank { "This subject" }
            val riskLine = if (topFindings.isEmpty()) {
                "No high-confidence exposure findings were extracted, but review candidates should still be checked manually."
            } else {
                "Highest-priority findings include ${topFindings.joinToString(", ") { it.type.name }}."
            }

            return buildString {
                appendLine("Local baseline analysis")
                appendLine()
                appendLine("$subject has ${confirmed.size} directly verified profile(s) and ${review.size} public-search review candidate(s). $riskLine")
                appendLine()
                appendLine("Top exposure risks")
                if (topFindings.isEmpty()) {
                    appendLine("- No extracted findings exceeded the reporting threshold.")
                } else {
                    topFindings.forEach { finding ->
                        appendLine("- ${finding.type}: ${finding.value} (${(finding.confidence * 100).toInt()}%, ${finding.risk})")
                    }
                }
                appendLine()
                appendLine("Recommended actions")
                if (remediation.isEmpty()) {
                    appendLine("- Re-run with Deep Research and verify public-search candidates manually.")
                } else {
                    remediation.forEach { appendLine("- $it") }
                }
                if (review.isNotEmpty()) {
                    appendLine()
                    appendLine("Manual verification needed")
                    review.take(5).forEach { result ->
                        appendLine("- ${result.candidate.url} (${(result.candidate.confidence * 100).toInt()}%)")
                    }
                }
            }.trim()
        }

        private fun riskWeight(risk: io.dossier.app.domain.model.RiskLevel): Int = when (risk) {
            io.dossier.app.domain.model.RiskLevel.Critical -> 4
            io.dossier.app.domain.model.RiskLevel.High -> 3
            io.dossier.app.domain.model.RiskLevel.Medium -> 2
            io.dossier.app.domain.model.RiskLevel.Low -> 1
        }
    }
}
