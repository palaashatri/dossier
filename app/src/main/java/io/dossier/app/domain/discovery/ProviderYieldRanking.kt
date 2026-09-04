package io.dossier.app.domain.discovery

/**
 * Deterministic pure ranking helper that prioritizes discovery exploration and yield.
 * Prioritizes unvalidated/new providers for exploration, favors high usable-response rates,
 * and penalizes high failure rates and latencies.
 */
object ProviderYieldRanking {

    private data class Scored<T>(
        val item: T,
        val assessment: ProviderHealthAssessment?
    )

    /**
     * Ranks items based on their associated [ProviderHealthAssessment].
     * Uses stable sorting to preserve the original catalog order on ties.
     */
    fun <T> rank(
        items: List<T>,
        assessmentProvider: (T) -> ProviderHealthAssessment?
    ): List<T> {
        return items
            .map { item -> Scored(item, assessmentProvider(item)) }
            .sortedWith(
                compareBy<Scored<T>> { scored -> explorationBucket(scored.assessment) }
                    .thenByDescending { scored -> scored.assessment?.usableResponseRate ?: 0.0 }
                    .thenBy { scored -> scored.assessment?.failureRate ?: 1.0 }
                    .thenBy { scored -> scored.assessment?.latencyMs ?: Long.MAX_VALUE }
            )
            .map(Scored<T>::item)
    }

    private fun explorationBucket(assessment: ProviderHealthAssessment?): Int = when {
        assessment == null ||
            assessment.attempts <= 0L ||
            assessment.status == ProviderHealthStatus.Unvalidated ||
            assessment.dataQuality != ProviderHealthDataQuality.Valid -> 0
        assessment.status == ProviderHealthStatus.Stale -> 1
        else -> 2
    }
}
