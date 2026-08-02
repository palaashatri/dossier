package io.dossier.app.data.web

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/** Shared retry, block-page detection, and circuit-breaker policy for public discovery. */
internal object DiscoveryHttpPolicy {
    private const val DEFAULT_BASE_DELAY_MS = 600L
    private const val MAX_DELAY_MS = 6_000L

    fun isTransientHttpStatus(code: Int): Boolean =
        code == 408 || code == 425 || code == 429 || code in 500..599

    fun retryDelayMillis(
        attempt: Int,
        retryAfterHeader: String?,
        baseDelayMs: Long = DEFAULT_BASE_DELAY_MS
    ): Long {
        val retryAfterMs = retryAfterHeader
            ?.trim()
            ?.toLongOrNull()
            ?.coerceIn(0L, 60L)
            ?.times(1_000L)
        if (retryAfterMs != null) return retryAfterMs

        val exponent = attempt.coerceIn(0, 4)
        val exponential = baseDelayMs * (1L shl exponent)
        // Deterministic jitter keeps tests stable while preventing synchronized retries.
        val jitter = ((attempt + 1) * 137L) % 311L
        return min(MAX_DELAY_MS, exponential + jitter)
    }

    fun looksBlocked(html: String): Boolean {
        if (html.isBlank()) return false
        val lower = html.lowercase()
        val markers = listOf(
            "verify you are human",
            "unusual traffic",
            "automated queries",
            "checking your browser",
            "just a moment",
            "cf-challenge",
            "captcha",
            "access denied",
            "enable javascript and cookies",
            "our systems have detected unusual traffic"
        )
        return markers.any(lower::contains)
    }
}

/**
 * Small in-memory circuit breaker. One broken provider cannot consume every query budget.
 * State is process-local by design; a fresh app process gets a clean retry opportunity.
 */
internal class ProviderCircuitBreaker(
    private val failureThreshold: Int = 3,
    private val cooldownMillis: Long = 120_000L,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    private data class State(var failures: Int = 0, var openUntil: Long = 0L)
    private val states = ConcurrentHashMap<String, State>()

    fun canAttempt(provider: String): Boolean {
        val state = states[provider] ?: return true
        val now = nowMillis()
        if (state.openUntil == 0L || now >= state.openUntil) {
            if (state.openUntil != 0L) states.remove(provider, state)
            return true
        }
        return false
    }

    fun recordSuccess(provider: String) {
        states.remove(provider)
    }

    fun recordFailure(provider: String) {
        states.compute(provider) { _, old ->
            val state = old ?: State()
            state.failures += 1
            if (state.failures >= failureThreshold) {
                state.openUntil = nowMillis() + cooldownMillis
            }
            state
        }
    }
}
