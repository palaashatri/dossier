package io.dossier.app.domain.discovery

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * Longitudinal provider diagnostics containing provider IDs and aggregate transport
 * outcomes only. No queried handles, URLs, identities, snippets, or case data are
 * written here.
 */
class PersistentProviderHealthStore(context: Context) {
    @Serializable
    data class Record(
        val providerId: String,
        val attempts: Long = 0,
        val successes: Long = 0,
        val notFound: Long = 0,
        val softNotFound: Long = 0,
        val timeouts: Long = 0,
        val rateLimited: Long = 0,
        val authenticationRequired: Long = 0,
        val unsupportedAutomation: Long = 0,
        val parseFailures: Long = 0,
        val networkFailures: Long = 0,
        val latencyEwmaMs: Double? = null,
        val lastValidatedAtUtc: String? = null
    ) {
        val successRate: Double
            get() = if (attempts <= 0L) 0.0 else successes.toDouble() / attempts.toDouble()
    }

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    @Synchronized
    fun record(providerId: String, outcome: ProviderOutcome, latencyMs: Long) {
        val id = normalize(providerId)
        val previous = load(id) ?: Record(providerId = id)
        val nextLatency = previous.latencyEwmaMs?.let { old ->
            old * (1.0 - EWMA_ALPHA) + latencyMs.coerceAtLeast(0L) * EWMA_ALPHA
        } ?: latencyMs.coerceAtLeast(0L).toDouble()
        val next = previous.copy(
            attempts = previous.attempts + 1,
            successes = previous.successes + if (outcome == ProviderOutcome.Success) 1 else 0,
            notFound = previous.notFound + if (outcome == ProviderOutcome.NotFound) 1 else 0,
            softNotFound = previous.softNotFound + if (outcome == ProviderOutcome.SoftNotFound) 1 else 0,
            timeouts = previous.timeouts + if (outcome == ProviderOutcome.Timeout) 1 else 0,
            rateLimited = previous.rateLimited + if (outcome == ProviderOutcome.RateLimited) 1 else 0,
            authenticationRequired = previous.authenticationRequired + if (outcome == ProviderOutcome.AuthenticationRequired) 1 else 0,
            unsupportedAutomation = previous.unsupportedAutomation + if (outcome == ProviderOutcome.UnsupportedAutomation) 1 else 0,
            parseFailures = previous.parseFailures + if (outcome == ProviderOutcome.ParseFailure) 1 else 0,
            networkFailures = previous.networkFailures + if (outcome == ProviderOutcome.NetworkFailure) 1 else 0,
            latencyEwmaMs = nextLatency,
            lastValidatedAtUtc = Instant.now().toString()
        )
        prefs.edit().putString(key(id), json.encodeToString(next)).apply()
    }

    @Synchronized
    fun load(providerId: String): Record? {
        val id = normalize(providerId)
        val raw = prefs.getString(key(id), null) ?: return null
        return runCatching { json.decodeFromString<Record>(raw) }.getOrNull()
    }

    @Synchronized
    fun snapshot(): List<Record> = prefs.all
        .asSequence()
        .filter { (key, value) -> key.startsWith(KEY_PREFIX) && value is String }
        .mapNotNull { (_, value) ->
            runCatching { json.decodeFromString<Record>(value as String) }.getOrNull()
        }
        .sortedWith(compareByDescending<Record> { it.attempts }.thenBy { it.providerId })
        .toList()

    @Synchronized
    fun clear() {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(KEY_PREFIX) }.forEach(editor::remove)
        editor.apply()
    }

    private fun normalize(value: String): String = value.trim().lowercase().take(160).ifBlank { "unknown" }
    private fun key(providerId: String): String = "$KEY_PREFIX$providerId"

    private companion object {
        const val PREFS = "dossier-provider-health-v1"
        const val KEY_PREFIX = "provider:"
        const val EWMA_ALPHA = 0.20
    }
}
