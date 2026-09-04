package io.dossier.app.data.web

import java.net.URI
import java.util.Locale

/**
 * Small, dependency-free benchmark evaluator used by regression fixtures and future
 * device/network corpora. Reliability claims must be derived from these metrics rather
 * than from the number of supported platform templates.
 */
object DiscoveryBenchmark {
    // --- Legacy observation-based metrics API ---
    enum class Expected { BELONGS, DOES_NOT_BELONG, UNVERIFIABLE }
    enum class Actual { VERIFIED, PLAUSIBLE, NOT_FOUND, UNVERIFIABLE }

    data class Observation(
        val id: String,
        val expected: Expected,
        val actual: Actual
    )

    data class Metrics(
        val truePositives: Int,
        val falsePositives: Int,
        val falseNegatives: Int,
        val trueNegatives: Int,
        val correctlyUnverifiable: Int,
        val total: Int
    ) {
        val precision: Double
            get() = ratio(truePositives, truePositives + falsePositives)
        val recall: Double
            get() = ratio(truePositives, truePositives + falseNegatives)
        val specificity: Double
            get() = ratio(trueNegatives, trueNegatives + falsePositives)
        val unverifiableAccuracy: Double
            get() = ratio(correctlyUnverifiable, total)
        val f1: Double
            get() = if (precision + recall == 0.0) 0.0 else 2.0 * precision * recall / (precision + recall)
    }

    private fun ratio(numerator: Int, denominator: Int): Double =
        if (denominator == 0) 1.0 else numerator.toDouble() / denominator.toDouble()

    fun evaluate(observations: List<Observation>): Metrics {
        var tp = 0
        var fp = 0
        var fn = 0
        var tn = 0
        var unverifiable = 0

        observations.forEach { observation ->
            when (observation.expected) {
                Expected.BELONGS -> when (observation.actual) {
                    Actual.VERIFIED -> tp++
                    Actual.PLAUSIBLE, Actual.NOT_FOUND, Actual.UNVERIFIABLE -> fn++
                }
                Expected.DOES_NOT_BELONG -> when (observation.actual) {
                    Actual.VERIFIED -> fp++
                    Actual.PLAUSIBLE, Actual.NOT_FOUND, Actual.UNVERIFIABLE -> tn++
                }
                Expected.UNVERIFIABLE -> {
                    if (observation.actual == Actual.UNVERIFIABLE) unverifiable++
                    if (observation.actual == Actual.VERIFIED) fp++
                }
            }
        }

        return Metrics(
            truePositives = tp,
            falsePositives = fp,
            falseNegatives = fn,
            trueNegatives = tn,
            correctlyUnverifiable = unverifiable,
            total = observations.size
        )
    }

    // --- Synthetic end-to-end benchmark API ---

    /**
     * A ground-truth or observed fact.  [exactValue] is nullable so a provider
     * can report that a class of information exists without returning the
     * historical value.  Matching uses [normalizedValue], while events retain
     * the exact source string for inspection.
     */
    data class Fact(val kind: String, val exactValue: String?) {
        val normalizedKind: String
            get() = kind.trim().lowercase(Locale.ROOT).replace('-', '_').replace(' ', '_')

        val normalizedValue: String
            get() = normalizeValue(normalizedKind, exactValue)

        fun matches(other: Fact): Boolean =
            normalizedValue.isNotBlank() &&
                normalizedKind == other.normalizedKind &&
                normalizedValue == other.normalizedValue
    }

    data class SyntheticCase(
        val name: String,
        val initialSeed: Fact,
        val expectedFacts: List<Fact>,
        val knownNegatives: List<Fact> = emptyList(),
        val isCompleteGroundTruth: Boolean = false
    ) {
        init {
            require(name.isNotBlank()) { "Synthetic case name is required" }
            require(initialSeed.exactValue?.isNotBlank() == true) {
                "Synthetic case initial seed must retain an exact value"
            }
            require(expectedFacts.isNotEmpty()) { "Synthetic cases require at least one expected fact" }
            require(expectedFacts.all { it.normalizedValue.isNotBlank() }) {
                "Synthetic expected facts must retain exact values"
            }
            require(knownNegatives.all { it.normalizedValue.isNotBlank() }) {
                "Synthetic known negatives must retain exact values"
            }
            require(expectedFacts.map(::factKey).distinct().size == expectedFacts.size) {
                "Synthetic expected facts must be unique after normalization"
            }
            require(knownNegatives.map(::factKey).distinct().size == knownNegatives.size) {
                "Synthetic known negatives must be unique after normalization"
            }
        }
    }

    enum class EventStatus {
        VERIFIED,
        CANDIDATE,
        UNAVAILABLE,
        PROVIDER_FAILURE
    }

    data class DiscoveryEvent(
        val elapsedTimeMs: Long,
        val fact: Fact,
        val status: EventStatus,
        val providerId: String = "",
        val requestCount: Int = 0,
        val isIdentityAnchor: Boolean = false,
        val usefulPivotCount: Int = 0
    ) {
        init {
            require(elapsedTimeMs >= 0L) { "Synthetic event time cannot be negative" }
            require(requestCount >= 0) { "Synthetic request count cannot be negative" }
            require(usefulPivotCount >= 0) { "Synthetic pivot count cannot be negative" }
        }
    }

    /** A deterministic, network-free result emitted by a benchmark discoverer. */
    data class SyntheticRun(
        val events: List<DiscoveryEvent> = emptyList(),
        val totalScanDurationMs: Long? = null,
        val totalRequestCount: Int? = null,
        val providerFailureCount: Int = 0
    ) {
        init {
            require(totalScanDurationMs == null || totalScanDurationMs >= 0L) {
                "Synthetic scan duration cannot be negative"
            }
            require(totalRequestCount == null || totalRequestCount >= 0) {
                "Synthetic request count cannot be negative"
            }
            require(providerFailureCount >= 0) {
                "Synthetic provider failure count cannot be negative"
            }
            totalRequestCount?.let { declared ->
                require(declared >= events.sumOf(DiscoveryEvent::requestCount)) {
                    "Declared request count cannot omit event requests"
                }
                require(declared >= providerFailureCount) {
                    "Declared request count cannot omit provider failures"
                }
            }
            totalScanDurationMs?.let { declared ->
                require(declared >= (events.maxOfOrNull { it.elapsedTimeMs } ?: 0L)) {
                    "Declared duration cannot precede the last event"
                }
            }
        }
    }

    data class SyntheticRunMetrics(
        val truePositives: Int,
        val falsePositives: Int,
        val falseNegatives: Int,
        val precision: Double,
        val recall: Double,
        val f1: Double,
        /** Null when no explicit negative facts were supplied. */
        val falsePositiveRate: Double?,
        val unresolvedCandidateCount: Int,
        val timeToFirstUsefulResultMs: Long?,
        val timeToFirstVerifiedIdentityAnchorMs: Long?,
        val timeToFirstHighValueExactIdentifierMs: Long?,
        val timeTo50PercentRecallMs: Long?,
        val timeTo80PercentRecallMs: Long?,
        val totalScanDurationMs: Long,
        val providerFailureRate: Double,
        val usefulFindingsPerRequest: Double,
        val usefulPivotsPerVerifiedFinding: Double,
        /** Verified facts outside incomplete ground truth are reported separately. */
        val unlabelledExtraCount: Int,
        val totalVerifiedFindingCount: Int = 0,
        val totalProviderRequestCount: Int = 0
    )

    data class SyntheticAggregateMetrics(
        val totalCases: Int,
        val truePositives: Int,
        val falsePositives: Int,
        val falseNegatives: Int,
        val averagePrecision: Double,
        val averageRecall: Double,
        val averageF1: Double
    )

    /** Execute a deterministic discoverer and evaluate its synthetic run. */
    suspend fun run(
        case: SyntheticCase,
        discoverer: suspend (SyntheticCase) -> SyntheticRun
    ): SyntheticRunMetrics = evaluate(case, discoverer(case))

    /** Backward-compatible list-based seam used by focused JVM fixtures. */
    suspend fun evaluateRun(
        case: SyntheticCase,
        discoverer: suspend (SyntheticCase) -> List<DiscoveryEvent>
    ): SyntheticRunMetrics = evaluate(case, SyntheticRun(events = discoverer(case)))

    fun evaluate(case: SyntheticCase, run: SyntheticRun): SyntheticRunMetrics {
        val events = run.events.sortedBy(DiscoveryEvent::elapsedTimeMs)
        val expectedKeys = case.expectedFacts.map(::factKey).toSet()
        val negativeKeys = case.knownNegatives.map(::factKey).toSet()
        val verifiedFacts = mutableSetOf<String>()
        val recoveredExpected = mutableSetOf<String>()

        var falsePositives = 0
        var unlabelledExtra = 0
        var unresolvedCandidateCount = 0
        var timeToFirstUsefulResultMs: Long? = null
        var timeToFirstVerifiedIdentityAnchorMs: Long? = null
        var timeToFirstHighValueExactIdentifierMs: Long? = null
        var timeTo50PercentRecallMs: Long? = null
        var timeTo80PercentRecallMs: Long? = null
        var usefulPivotsCount = 0

        for (event in events) {
            if (event.status != EventStatus.VERIFIED || event.fact.normalizedValue.isBlank()) {
                unresolvedCandidateCount++
                continue
            }
            if (!verifiedFacts.add(factKey(event.fact))) continue

            if (timeToFirstUsefulResultMs == null) timeToFirstUsefulResultMs = event.elapsedTimeMs
            if (event.isIdentityAnchor && timeToFirstVerifiedIdentityAnchorMs == null) {
                timeToFirstVerifiedIdentityAnchorMs = event.elapsedTimeMs
            }
            if (event.fact.normalizedKind in HIGH_VALUE_KINDS &&
                timeToFirstHighValueExactIdentifierMs == null
            ) {
                timeToFirstHighValueExactIdentifierMs = event.elapsedTimeMs
            }
            usefulPivotsCount += event.usefulPivotCount

            val key = factKey(event.fact)
            if (key in expectedKeys) {
                recoveredExpected += key
                val recall = recoveredExpected.size.toDouble() / expectedKeys.size.coerceAtLeast(1)
                if (recall >= 0.5 && timeTo50PercentRecallMs == null) {
                    timeTo50PercentRecallMs = event.elapsedTimeMs
                }
                if (recall >= 0.8 && timeTo80PercentRecallMs == null) {
                    timeTo80PercentRecallMs = event.elapsedTimeMs
                }
            } else if (key in negativeKeys || case.isCompleteGroundTruth) {
                falsePositives++
            } else {
                unlabelledExtra++
            }
        }

        val truePositives = recoveredExpected.size
        val falseNegatives = expectedKeys.size - truePositives
        val observedNegativeCount = verifiedFacts.count { it in negativeKeys }
        val falsePositiveRate = negativeKeys
            .takeIf { it.isNotEmpty() }
            ?.let { ratio(observedNegativeCount, it.size) }
        val eventRequests = events.sumOf(DiscoveryEvent::requestCount)
        val failedRequests = events
            .filter { it.status == EventStatus.UNAVAILABLE || it.status == EventStatus.PROVIDER_FAILURE }
            .sumOf(DiscoveryEvent::requestCount) + run.providerFailureCount
        val totalRequests = maxOf(eventRequests, run.totalRequestCount ?: 0, failedRequests)
        val verifiedFindingCount = verifiedFacts.size
        val duration = run.totalScanDurationMs
            ?: (events.maxOfOrNull(DiscoveryEvent::elapsedTimeMs) ?: 0L)

        return SyntheticRunMetrics(
            truePositives = truePositives,
            falsePositives = falsePositives,
            falseNegatives = falseNegatives,
            precision = ratio(truePositives, truePositives + falsePositives),
            recall = ratio(truePositives, truePositives + falseNegatives),
            f1 = f1(truePositives, truePositives + falsePositives, truePositives + falseNegatives),
            falsePositiveRate = falsePositiveRate,
            unresolvedCandidateCount = unresolvedCandidateCount,
            timeToFirstUsefulResultMs = timeToFirstUsefulResultMs,
            timeToFirstVerifiedIdentityAnchorMs = timeToFirstVerifiedIdentityAnchorMs,
            timeToFirstHighValueExactIdentifierMs = timeToFirstHighValueExactIdentifierMs,
            timeTo50PercentRecallMs = timeTo50PercentRecallMs,
            timeTo80PercentRecallMs = timeTo80PercentRecallMs,
            totalScanDurationMs = duration,
            providerFailureRate = zeroRatio(failedRequests, totalRequests),
            usefulFindingsPerRequest = zeroRatio(verifiedFindingCount, totalRequests),
            usefulPivotsPerVerifiedFinding = zeroRatio(usefulPivotsCount, verifiedFindingCount),
            unlabelledExtraCount = unlabelledExtra,
            totalVerifiedFindingCount = verifiedFindingCount,
            totalProviderRequestCount = totalRequests
        )
    }

    fun aggregate(runs: List<SyntheticRunMetrics>): SyntheticAggregateMetrics {
        if (runs.isEmpty()) {
            return SyntheticAggregateMetrics(0, 0, 0, 0, 0.0, 0.0, 0.0)
        }
        return SyntheticAggregateMetrics(
            totalCases = runs.size,
            truePositives = runs.sumOf(SyntheticRunMetrics::truePositives),
            falsePositives = runs.sumOf(SyntheticRunMetrics::falsePositives),
            falseNegatives = runs.sumOf(SyntheticRunMetrics::falseNegatives),
            averagePrecision = runs.map(SyntheticRunMetrics::precision).average(),
            averageRecall = runs.map(SyntheticRunMetrics::recall).average(),
            averageF1 = runs.map(SyntheticRunMetrics::f1).average()
        )
    }

    private fun factKey(fact: Fact): String =
        "${fact.normalizedKind}:${fact.normalizedValue}"

    private fun normalizeValue(kind: String, value: String?): String {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isBlank()) return ""
        return when (kind) {
            "email" -> trimmed.lowercase(Locale.ROOT)
            "phone" -> trimmed.filter(Char::isDigit)
            "username" -> trimmed.removePrefix("@").lowercase(Locale.ROOT)
            "profile", "profile_url", "website", "domain", "image", "photo", "document" ->
                normalizeUrl(trimmed)
            else -> trimmed.replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
        }
    }

    private fun normalizeUrl(value: String): String {
        val withoutFragment = value.substringBefore('#')
        return runCatching {
            val uri = URI(withoutFragment)
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
                ?: return@runCatching withoutFragment.lowercase(Locale.ROOT)
            val host = uri.host?.lowercase(Locale.ROOT)
                ?: return@runCatching withoutFragment.lowercase(Locale.ROOT)
            URI(scheme, uri.userInfo, host, uri.port, uri.path, uri.query, null).toString()
        }.getOrElse { withoutFragment.lowercase(Locale.ROOT) }
    }

    private fun f1(tp: Int, predicted: Int, expected: Int): Double {
        val precision = ratio(tp, predicted)
        val recall = ratio(tp, expected)
        return if (precision + recall == 0.0) 0.0 else 2.0 * precision * recall / (precision + recall)
    }

    private fun zeroRatio(numerator: Int, denominator: Int): Double =
        if (denominator == 0) 0.0 else numerator.toDouble() / denominator.toDouble()

    private val HIGH_VALUE_KINDS = setOf("email", "phone", "address", "postal_code")
}
