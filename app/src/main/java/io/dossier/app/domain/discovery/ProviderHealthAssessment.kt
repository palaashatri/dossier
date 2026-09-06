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
/**
 * Aggregate-only provider diagnostics. Every attempt must contribute to exactly
 * one outcome counter; malformed or partial samples are surfaced as invalid by
 * [ProviderHealthAssessmentRules] rather than being treated as healthy.
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

enum class ProviderHealthDataQuality {
    /** Aggregate counters form one complete, non-negative validation sample. */
    Valid,

    /** No sample exists for this catalog provider yet. */
    Missing,

    /** A persisted/received sample is internally inconsistent and is not trusted. */
    Invalid
}

data class ProviderHealthAssessment(
    val providerId: String,
    val status: ProviderHealthStatus,
    val attempts: Long,
    val usableResponses: Long,
    val usableResponseRate: Double,
    val failureRate: Double,
    val latencyMs: Long?,
    val lastValidatedAt: Instant?,
    val dataQuality: ProviderHealthDataQuality = ProviderHealthDataQuality.Valid,
    val dataQualityMessage: String? = null
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
        staleAfter: Duration = DEFAULT_STALE_AFTER,
        knownProviderId: String? = null
    ): ProviderHealthAssessment {
        require(!staleAfter.isNegative) { "staleAfter must not be negative" }

        val providerId = (sample?.providerId ?: knownProviderId)
            ?.trim()
            ?.lowercase(Locale.ROOT)
            .orEmpty()
            .ifBlank { "unknown" }
        val rawAttempts = sample?.attempts ?: 0L
        val attempts = rawAttempts.coerceAtLeast(0L)
        val successes = sample?.successes?.coerceAtLeast(0L) ?: 0L
        val notFound = sample?.notFound?.coerceAtLeast(0L) ?: 0L
        val softNotFound = sample?.softNotFound?.coerceAtLeast(0L) ?: 0L
        val timeouts = sample?.timeouts?.coerceAtLeast(0L) ?: 0L
        val rateLimited = sample?.rateLimited?.coerceAtLeast(0L) ?: 0L
        val authenticationRequired = sample?.authenticationRequired?.coerceAtLeast(0L) ?: 0L
        val unsupportedAutomation = sample?.unsupportedAutomation?.coerceAtLeast(0L) ?: 0L
        val providerChanged = sample?.providerChanged?.coerceAtLeast(0L) ?: 0L
        val parseFailures = sample?.parseFailures?.coerceAtLeast(0L) ?: 0L
        val networkFailures = sample?.networkFailures?.coerceAtLeast(0L) ?: 0L
        val usableResponses = (successes + notFound).coerceAtMost(attempts)
        val failureCount = (attempts - usableResponses).coerceAtLeast(0L)
        val usableRate = if (attempts == 0L) 0.0 else usableResponses.toDouble() / attempts
        val failureRate = if (attempts == 0L) 0.0 else failureCount.toDouble() / attempts
        val lastValidatedAt = sample?.lastValidatedAt
        val allCounters = listOf(
            rawAttempts,
            sample?.successes ?: 0L,
            sample?.notFound ?: 0L,
            sample?.softNotFound ?: 0L,
            sample?.timeouts ?: 0L,
            sample?.rateLimited ?: 0L,
            sample?.authenticationRequired ?: 0L,
            sample?.unsupportedAutomation ?: 0L,
            sample?.providerChanged ?: 0L,
            sample?.parseFailures ?: 0L,
            sample?.networkFailures ?: 0L
        )
        val hasNegativeCounter = allCounters.any { it < 0L }
        val outcomeTotal = listOf(
            successes,
            notFound,
            softNotFound,
            timeouts,
            rateLimited,
            authenticationRequired,
            unsupportedAutomation,
            providerChanged,
            parseFailures,
            networkFailures
        ).fold(0L) { total, count ->
            if (Long.MAX_VALUE - total < count) Long.MAX_VALUE else total + count
        }
        val futureTimestamp = lastValidatedAt?.isAfter(now) == true
        val dataQualityMessage = when {
            sample == null -> null
            sample.providerId.isBlank() -> "provider ID is blank"
            hasNegativeCounter -> "aggregate validation counters contain a negative value"
            outcomeTotal != rawAttempts -> "aggregate outcome counts do not equal attempts"
            sample.latencyMs != null && sample.latencyMs < 0L -> "latency is negative"
            futureTimestamp -> "validation timestamp is in the future"
            else -> null
        }
        val dataQuality = when {
            sample == null -> ProviderHealthDataQuality.Missing
            dataQualityMessage != null -> ProviderHealthDataQuality.Invalid
            else -> ProviderHealthDataQuality.Valid
        }
        val stale = lastValidatedAt != null &&
            !Duration.between(lastValidatedAt, now).isNegative &&
            Duration.between(lastValidatedAt, now) > staleAfter

        val status = when {
            dataQuality == ProviderHealthDataQuality.Invalid -> ProviderHealthStatus.Unavailable
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
            lastValidatedAt = lastValidatedAt,
            dataQuality = dataQuality,
            dataQualityMessage = dataQualityMessage
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
        val assessments = known.map { id ->
            assess(
                sample = byId[id],
                now = now,
                staleAfter = staleAfter,
                knownProviderId = id
            )
        }
        return ProviderHealthReport(
            knownProviderCount = assessments.size,
            observedProviderCount = assessments.count {
                it.attempts > 0L && it.dataQuality == ProviderHealthDataQuality.Valid
            },
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
