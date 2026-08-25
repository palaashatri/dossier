package io.dossier.app.domain.scanner

import io.dossier.app.domain.model.ProfileScanResult
import io.dossier.app.domain.model.UsernameCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Restores stable direct-profile results and executes only the remaining work.
 * The caller owns coordinator events, so cached hits never synthesize queue or
 * completion events and progress remains tied to actual scheduled operations.
 */
internal object ProfileInitialPassExecutor {
    /**
     * Evidence-free accounting for the direct-profile boundary.  A reused
     * result was loaded from the request/plan-bound checkpoint; a rerun result
     * was fetched during this pass.  The summary contains no candidate values
     * or provider payloads and is emitted only after the pass has completed.
     */
    data class RecoverySummary(
        val reusedCount: Int,
        val rerunCount: Int,
        val checkpointAvailable: Boolean
    )

    suspend fun execute(
        candidates: List<UsernameCandidate>,
        checkpoint: ProfileCheckpointAccess?,
        queueMiss: (UsernameCandidate) -> Unit,
        fetchMiss: suspend (UsernameCandidate) -> ProfileScanResult,
        onRecovery: (RecoverySummary) -> Unit = {}
    ): List<ProfileScanResult> = coroutineScope {
        val orderedResults = arrayOfNulls<ProfileScanResult>(candidates.size)
        val misses = mutableListOf<IndexedValue<UsernameCandidate>>()

        candidates.forEachIndexed { index, candidate ->
            val cached = runCatching { checkpoint?.load(candidate) }.getOrNull()
            if (cached != null && ProfileScanCheckpointStore.isReusable(cached)) {
                orderedResults[index] = cached
            } else {
                misses += IndexedValue(index, candidate)
            }
        }

        misses.forEach { queueMiss(it.value) }
        misses.map { indexedCandidate ->
            async(Dispatchers.IO) {
                val result = fetchMiss(indexedCandidate.value)
                if (checkpoint != null && ProfileScanCheckpointStore.isReusable(result)) {
                    runCatching { checkpoint.save(result) }
                }
                indexedCandidate.index to result
            }
        }.awaitAll().forEach { (index, result) ->
            orderedResults[index] = result
        }

        val completeResults = orderedResults.mapIndexed { index, result ->
            requireNotNull(result) { "Missing direct-profile result at index $index" }
        }
        // Diagnostics are deliberately best-effort and happen after all
        // direct-profile work succeeded. A reporting callback must never turn
        // an otherwise valid scan into a failure.
        runCatching {
            onRecovery(
                RecoverySummary(
                    reusedCount = candidates.size - misses.size,
                    rerunCount = misses.size,
                    checkpointAvailable = checkpoint != null
                )
            )
        }
        completeResults
    }
}
