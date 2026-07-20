package io.dossier.app.domain.scanner

import io.dossier.app.domain.model.Finding

/**
 * Bounded-memory guard for long-running scans (ROADMAP M16).
 *
 * Scans accumulate findings, profile results, and face matches; without a cap a
 * deep-research run over many platforms could grow unbounded. [MemoryGuard]
 * enforces a hard ceiling on retained [Finding]s: once [MAX_FINDINGS] is reached,
 * further findings are dropped and [droppedCount] records how many were omitted
 * so the UI can surface an honest "N findings omitted (memory limit)" notice
 * rather than silently truncating. Pure and Android-free, so it is unit-testable.
 */
object MemoryGuard {
    const val MAX_FINDINGS = 500

    data class Result(
        val retained: List<Finding>,
        val droppedCount: Int
    )

    /**
     * Returns at most [MAX_FINDINGS] findings. If [findings] is larger, the first
     * [MAX_FINDINGS] are kept (highest-priority: Critical→Low already ordered by
     * callers) and the remainder are counted in [Result.droppedCount].
     */
    fun cap(findings: List<Finding>): Result {
        return if (findings.size <= MAX_FINDINGS) {
            Result(findings, 0)
        } else {
            Result(findings.take(MAX_FINDINGS), findings.size - MAX_FINDINGS)
        }
    }
}
