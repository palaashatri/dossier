package io.dossier.app.domain.discovery

import java.time.Duration
import java.time.Instant
import java.util.Locale

/**
 * Aggregate provider outcomes used for maintenance diagnostics.
 *
 * This intentionally contains no queried handles, URLs, response bodies or case
 * data. A NotFound response is a valid provider response (the existence rule
 * worked), while soft errors, policy blocks and transport/parser failures are
 * reliability failures.
 */
data class ProviderHealthSample(
    val providerId: String,
    val attempts: Long,
    val successes: Long = 0,
    val notFound: Long = 0,
    val softNotFound: Long = 0,
    val timeouts: Long = 0,
    val rateLimited: Long = 0,
    val authenticationRequired: Long = 0,
    val unsupportedAutomation: Long = 0,
    val providerChanged: Long = 0,
    val parseFailures: Long = 0,
    val networkFailures: Long = 0,
    val latencyMs: Long? = null,
    val lastValidatedAt: Instant? = null
)

enum class ProviderHealthStatus {
    /** No recorded validation has completed for this provider. */
    Unvalidated,

    /** Recent validation has enough usable responses and few reliability failures. */
    Healthy,

    /** Validation exists but is too small, too old, or has a material failure rate. */
    Degraded,

    /** Recent validation found no usable responses or a predominantly failing provider. */
    Unavailable,

    /** A previously observed provider has not been validated within the freshness window. */
    Stale
}

data class ProviderHealthAssessment(
    val providerId: String,
    val status: ProviderHealthStatus,
    val attempts: Long,
    val usableResponses: Long,
    val usableResponseRate: Double,
    val failureRate: Double,
    val latencyMs: Long?,
    val lastValidatedAt: Instant?
)

/**
 * Deterministic, explicitly non-scientific health bucketing for maintenance
 * diagnostics. These thresholds must not be read as identity or evidence
 * confidence. They only decide whether a provider should be rechecked or
 * surfaced as unavailable to the operator.
 */
object ProviderHealthAssessmentRules {
    const val MIN_ATTEMPTS = 3L
    const val DEGRADED_FAILURE_RATE = 0.25
    const val UNAVAILABLE_FAILURE_RATE = 0.75
    val DEFAULT_STALE_AFTER: Duration = Duration.ofDays(30)

    fun assess(
        sample: ProviderHealthSample?,
        now: Instant = Instant.now(),
        staleAfter: Duration = DEFAULT_STALE_AFTER
    ): ProviderHealthAssessment {
        require(!staleAfter.isNegative) { "staleAfter must not be negative" }

        val providerId = sample?.providerId?.trim()?.lowercase(Locale.ROOT).orEmpty().ifBlank { "unknown" }
        val attempts = sample?.attempts?.coerceAtLeast(0L) ?: 0L
        val successes = sample?.successes?.coerceAtLeast(0L) ?: 0L
        val notFound = sample?.notFound?.coerceAtLeast(0L) ?: 0L
        val usableResponses = (successes + notFound).coerceAtMost(attempts)
        val failureCount = (attempts - usableResponses).coerceAtLeast(0L)
        val usableRate = if (attempts == 0L) 0.0 else usableResponses.toDouble() / attempts
        val failureRate = if (attempts == 0L) 0.0 else failureCount.toDouble() / attempts
        val lastValidatedAt = sample?.lastValidatedAt
        val stale = lastValidatedAt != null &&
            !Duration.between(lastValidatedAt, now).isNegative &&
            Duration.between(lastValidatedAt, now) > staleAfter

        val status = when {
            attempts == 0L || lastValidatedAt == null -> ProviderHealthStatus.Unvalidated
            stale -> ProviderHealthStatus.Stale
            attempts < MIN_ATTEMPTS -> ProviderHealthStatus.Degraded
            usableResponses == 0L || failureRate >= UNAVAILABLE_FAILURE_RATE -> ProviderHealthStatus.Unavailable
            failureRate >= DEGRADED_FAILURE_RATE -> ProviderHealthStatus.Degraded
            else -> ProviderHealthStatus.Healthy
        }

        return ProviderHealthAssessment(
            providerId = providerId,
            status = status,
            attempts = attempts,
            usableResponses = usableResponses,
            usableResponseRate = usableRate,
            failureRate = failureRate,
            latencyMs = sample?.latencyMs?.coerceAtLeast(0L),
            lastValidatedAt = lastValidatedAt
        )
    }

    /**
     * Assess every known provider, including providers with no history. Unknown
     * diagnostic records are deliberately ignored so a stale or orphaned
     * preference entry can never inflate catalog breadth or health coverage.
     */
    fun report(
        knownProviderIds: Collection<String>,
        samples: Collection<ProviderHealthSample>,
        now: Instant = Instant.now(),
        staleAfter: Duration = DEFAULT_STALE_AFTER
    ): ProviderHealthReport {
        val known = knownProviderIds
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter(String::isNotBlank)
            .distinct()
            .sorted()
        val byId = samples
            .associateBy { it.providerId.trim().lowercase(Locale.ROOT) }
        val assessments = known.map { id -> assess(byId[id], now, staleAfter) }
        return ProviderHealthReport(
            knownProviderCount = assessments.size,
            observedProviderCount = assessments.count { it.attempts > 0L },
            assessments = assessments
        )
    }
}

data class ProviderHealthReport(
    val knownProviderCount: Int,
    val observedProviderCount: Int,
    val assessments: List<ProviderHealthAssessment>
) {
    init {
        require(knownProviderCount >= 0)
        require(observedProviderCount in 0..knownProviderCount)
        require(assessments.size == knownProviderCount)
    }

    val coverageRate: Double
        get() = if (knownProviderCount == 0) 0.0 else observedProviderCount.toDouble() / knownProviderCount

    val healthyCount: Int
        get() = assessments.count { it.status == ProviderHealthStatus.Healthy }

    val degradedCount: Int
        get() = assessments.count { it.status == ProviderHealthStatus.Degraded }

    val unavailableCount: Int
        get() = assessments.count { it.status == ProviderHealthStatus.Unavailable }

    val staleCount: Int
        get() = assessments.count { it.status == ProviderHealthStatus.Stale }

    val unvalidatedCount: Int
        get() = assessments.count { it.status == ProviderHealthStatus.Unvalidated }
}
