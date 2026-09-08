package io.dossier.app.domain.scanner

import io.dossier.app.domain.model.ProfileScanResult
import io.dossier.app.domain.model.UsernameCandidate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
        onRecovery: (RecoverySummary) -> Unit = {},
        onProgress: suspend (List<ProfileScanResult>) -> Unit = {}
    ): List<ProfileScanResult> = coroutineScope {
        val orderedResults = arrayOfNulls<ProfileScanResult>(candidates.size)
        val misses = mutableListOf<IndexedValue<UsernameCandidate>>()
        val progressLock = Mutex()

        suspend fun publishProgress() {
            progressLock.withLock {
                val snapshot = orderedResults.filterNotNull()
                if (snapshot.isEmpty()) return@withLock
                try {
                    onProgress(snapshot)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A UI/progress observer must never turn a valid profile
                    // result into a failed scan. The scanner still commits the
                    // result and returns it in deterministic candidate order.
                }
            }
        }

        suspend fun publishCompleted(index: Int, result: ProfileScanResult) {
            progressLock.withLock {
                orderedResults[index] = result
                try {
                    onProgress(orderedResults.filterNotNull())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // See publishProgress: observers are best effort, while
                    // profile persistence/return semantics remain authoritative.
                }
            }
        }

        candidates.forEachIndexed { index, candidate ->
            val cached = runCatching { checkpoint?.load(candidate) }.getOrNull()
            if (cached != null && ProfileScanCheckpointStore.isReusable(cached)) {
                orderedResults[index] = cached
            } else {
                misses += IndexedValue(index, candidate)
            }
        }

        misses.forEach { queueMiss(it.value) }
        publishProgress()
        misses.map { indexedCandidate ->
            async(Dispatchers.IO) {
                val result = fetchMiss(indexedCandidate.value)
                if (checkpoint != null && ProfileScanCheckpointStore.isReusable(result)) {
                    runCatching { checkpoint.save(result) }
                }
                publishCompleted(indexedCandidate.index, result)
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
