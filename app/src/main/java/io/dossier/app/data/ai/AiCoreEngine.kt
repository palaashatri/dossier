package io.dossier.app.data.ai

import android.content.Context
import android.net.Uri
import io.dossier.app.domain.ai.LocalAiAnalysisResult
import io.dossier.app.domain.ai.LocalAiEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AICore (Gemini Nano via Android System Intelligence) engine.
 *
 * HONESTY: the previous implementation fabricated deterministic results from
 * `bytes.size % 2/3` ("Gare du Nord", "Server Rack", etc.) regardless of input.
 * That is removed. AICore's public Generative AI SDK requires device support +
 * a usable model; when we cannot genuinely run it we return null so the caller
 * falls through honestly instead of showing fake data.
 *
 * (A full AICore integration would use `com.google.ai.client.generativeai` with
 *  an on-device model handle. That's device- and build-dependent; until a real
 *  handle is wired, this engine truthfully reports "not available".)
 */
class AiCoreEngine(private val context: Context) : LocalAiEngine {
    override val name: String = "Google AICore (Gemini Nano)"

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        // Android System Intelligence package presence is a *necessary* but not
        // sufficient condition for AICore. We don't have a real on-device model
        // handle wired, so report unavailable rather than fabricating results.
        try {
            val pm = context.packageManager
            pm.getPackageInfo("com.google.android.as", 0)
            // Package present, but we cannot genuinely drive AICore yet.
            false
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun analyzeImage(imageUri: Uri): LocalAiAnalysisResult? {
        // Not genuinely wired — return null so the caller degrades honestly.
        return null
    }
}
