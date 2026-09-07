package io.dossier.app.domain.discovery

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared bounded scheduler for public-provider work.
 *
 * It enforces both a global concurrency ceiling and a minimum interval for each
 * provider key. This avoids the old pattern where many coroutines all delayed at
 * once and then hit a provider simultaneously. No target/user data is retained.
 */
class ProviderRequestScheduler(
    maxGlobalConcurrency: Int = 8
) {
    private val global = Semaphore(maxGlobalConcurrency.coerceIn(1, 8))
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val lastStartedAt = ConcurrentHashMap<String, Long>()

    suspend fun <T> execute(
        providerKey: String,
        minimumIntervalMs: Long,
        block: suspend () -> T
    ): T {
        val key = providerKey.trim().lowercase().ifBlank { "unknown" }
        val mutex = locks.computeIfAbsent(key) { Mutex() }
        return mutex.withLock {
            val now = monotonicMillis()
            val previous = lastStartedAt[key]
            if (previous != null) {
                val remaining = minimumIntervalMs.coerceAtLeast(0L) - (now - previous)
                if (remaining > 0L) delay(remaining)
            }
            global.withPermit {
                lastStartedAt[key] = monotonicMillis()
                block()
            }
        }
    }

    fun clearIdleState() {
        // Scheduler instances are scan-local today. This method exists for longer-
        // lived owners without exposing provider timing state to persistence.
        locks.clear()
        lastStartedAt.clear()
    }

    private fun monotonicMillis(): Long = System.nanoTime() / 1_000_000L
}
