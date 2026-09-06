package io.dossier.app.data.web

import java.net.URI
import java.util.Locale
import kotlin.math.roundToInt

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
            require(initialSeed.normalizedKind.isNotBlank()) {
                "Synthetic case initial seed must have a valid normalized kind"
            }
            require(initialSeed.normalizedValue.isNotBlank()) {
                "Synthetic case initial seed must have a valid normalized value"
            }
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
            require(
                expectedFacts.map(::factKey).toSet()
                    .intersect(knownNegatives.map(::factKey).toSet())
                    .isEmpty()
            ) {
                "Synthetic expected facts and known negatives must be disjoint"
            }
        }
    }

    enum class EventStatus {
        VERIFIED,
        /**
         * An exact value was observed in accessible content, but the content
         * does not establish that it belongs to the audited subject.
         *
         * Observations contribute to exposure recall only. They are not
         * identity proof and are never eligible for identity pivots.
         */
        OBSERVED,
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
        /** Cumulative pivots for this fact; duplicate observations are merged by maximum. */
        val usefulPivotCount: Int = 0,
        /** Optional producer marker for the attacker-supplied seed observation. */
        val isInitialSeed: Boolean = false
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
        /** Run-level failures may overlap failure-event requests; evaluation uses their maximum. */
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
        /** Fraction of explicit known-negative facts incorrectly verified; null when none supplied. */
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
        val totalProviderRequestCount: Int = 0,
        /** Number of unique explicitly labelled negative facts observed in any accepted state. */
        val observedKnownNegativeCount: Int = 0,
        /** Number of explicitly labelled negative facts in the case ground truth. */
        val knownNegativeCount: Int = 0,
        /** Strict count of events with candidate status. */
        val candidateCount: Int = 0,
        /** Strict count of unavailable source events. */
        val unavailableEventCount: Int = 0,
        /** Strict count of provider-failure events. */
        val providerFailureEventCount: Int = 0,
        /** Sum of useful pivots emitted by verified findings. */
        val usefulPivotCount: Int = 0,
        /** Requests represented by unavailable/failure events and run failures. */
        val failedRequestCount: Int = 0,
        /** Expected facts recovered from an exact, non-identity-attributed observation. */
        val observedExpectedCount: Int = 0,
        /** Expected facts recovered from an identity-verified observation. */
        val verifiedExpectedCount: Int = 0,
        /** Unique exact observations with [EventStatus.OBSERVED], excluding the initial seed. */
        val totalObservedFindingCount: Int = 0,
        /** Unique recovered findings with [EventStatus.VERIFIED] or [EventStatus.OBSERVED]. */
        val totalRecoveredFindingCount: Int = 0,
        /** Known-negative facts observed in any accepted state. */
        val observedKnownNegativeEventCount: Int = 0,
        /** Known-negative facts incorrectly accepted as identity-verified. */
        val verifiedKnownNegativeCount: Int = 0,
        /** Observed exact facts outside the listed ground truth. */
        val observedExtraCount: Int = 0
    ) {
        /**
         * Reconstruct counts for callers that persisted the pre-counter shape
         * of this data class. New evaluator output always supplies exact counts.
         */
        val effectiveFailedRequestCount: Int
            get() = failedRequestCount.takeIf { it > 0 } ?: estimatedCount(
                providerFailureRate,
                totalProviderRequestCount
            )

        val effectiveUsefulPivotCount: Int
            get() = usefulPivotCount.takeIf { it > 0 } ?: estimatedCount(
                usefulPivotsPerVerifiedFinding,
                totalVerifiedFindingCount
            )

        private fun estimatedCount(rate: Double, denominator: Int): Int =
            if (!rate.isFinite() || rate <= 0.0 || denominator <= 0) {
                0
            } else {
                (rate * denominator).roundToInt().coerceAtLeast(0)
            }

        /** Exposure recall includes both verified and exact observed facts. */
        val exposureRecall: Double get() = recall

        /** Alias for callers that distinguish identity findings explicitly. */
        val identityVerifiedFindingCount: Int get() = totalVerifiedFindingCount

        /** Alias for the number of exact observations retained separately from verification. */
        val observedFindingCount: Int get() = totalObservedFindingCount

        /** Alias for the number of expected facts recovered in either accepted state. */
        val recoveredExpectedCount: Int get() = truePositives
    }

    data class SyntheticAggregateMetrics(
        val totalCases: Int,
        val truePositives: Int,
        val falsePositives: Int,
        val falseNegatives: Int,
        val averagePrecision: Double,
        val averageRecall: Double,
        val averageF1: Double,
        /** Micro precision over label-aware exposure findings (TP / (TP + FP)). */
        val corpusPrecision: Double = 0.0,
        /** Recall@known-exposure, pooled across all expected facts. */
        val recallAtKnownExposure: Double = 0.0,
        /** Fraction of explicit known-negative facts incorrectly verified; null when none supplied. */
        val corpusFalsePositiveRate: Double? = null,
        val knownExposedFacts: Int = 0,
        val recoveredKnownExposedFacts: Int = 0,
        val knownNegativeFacts: Int = 0,
        val observedKnownNegativeFacts: Int = 0,
        /** Averages are over cases that reached the corresponding milestone. */
        val averageTimeToFirstUsefulResultMs: Double? = null,
        val averageTimeToFirstVerifiedIdentityAnchorMs: Double? = null,
        val averageTimeToFirstHighValueExactIdentifierMs: Double? = null,
        val averageTimeTo50PercentRecallMs: Double? = null,
        val averageTimeTo80PercentRecallMs: Double? = null,
        val averageTotalScanDurationMs: Double? = null,
        val timeToFirstUsefulResultCaseCount: Int = 0,
        val timeToFirstVerifiedIdentityAnchorCaseCount: Int = 0,
        val timeToFirstHighValueExactIdentifierCaseCount: Int = 0,
        val timeTo50PercentRecallCaseCount: Int = 0,
        val timeTo80PercentRecallCaseCount: Int = 0,
        /** Candidate, unavailable, provider-failure, and blank-value events. */
        val unresolvedCandidateCount: Int = 0,
        /** Strict unresolved candidate events (excluding unavailable/provider failures). */
        val candidateCount: Int = 0,
        val unavailableEventCount: Int = 0,
        val providerFailureEventCount: Int = 0,
        val unlabelledExtraCount: Int = 0,
        val totalVerifiedFindingCount: Int = 0,
        val totalProviderRequestCount: Int = 0,
        val totalFailedRequestCount: Int = 0,
        val totalUsefulPivotCount: Int = 0,
        val providerFailureRate: Double = 0.0,
        val usefulFindingsPerRequest: Double = 0.0,
        val usefulPivotsPerVerifiedFinding: Double = 0.0,
        /** Open-world verified findings not covered by the listed ground truth. */
        val unlabelledFindingRate: Double = 0.0,
        /** Expected facts recovered from exact observations rather than identity verification. */
        val observedKnownExposedFacts: Int = 0,
        /** Expected facts recovered with identity verification. */
        val verifiedKnownExposedFacts: Int = 0,
        val totalObservedFindingCount: Int = 0,
        val totalRecoveredFindingCount: Int = 0,
        val verifiedKnownNegativeFacts: Int = 0,
        val observedExtraCount: Int = 0
    ) {
        /** Alias names make the corpus-level contract explicit to report callers. */
        val precision: Double get() = corpusPrecision
        val recall: Double get() = recallAtKnownExposure
        val falsePositiveRate: Double? get() = corpusFalsePositiveRate
        val weightedPrecision: Double get() = corpusPrecision
        val weightedRecall: Double get() = recallAtKnownExposure
        val observedExpectedCount: Int get() = observedKnownExposedFacts
        val verifiedExpectedCount: Int get() = verifiedKnownExposedFacts
        val observedFindingCount: Int get() = totalObservedFindingCount
        val recoveredFindingCount: Int get() = totalRecoveredFindingCount
    }

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
        val initialSeedKey = factKey(case.initialSeed)
        // A benchmark manifest may include the seed in its expected list for
        // convenience, but it is not an exposure recovered by the scan. Keep
        // it out of the known-exposure denominator as well as event scoring.
        val expectedKeys = case.expectedFacts
            .filterNot { factKey(it) == initialSeedKey || isEquivalentInitialSeed(it, case.initialSeed) }
            .map(::factKey)
            .toSet()
        val negativeKeys = case.knownNegatives.map(::factKey).toSet()
        val recoveredFacts = mutableSetOf<String>()
        val verifiedFacts = mutableSetOf<String>()
        val observedFacts = mutableSetOf<String>()
        val recoveredExpected = mutableSetOf<String>()
        val verifiedExpected = mutableSetOf<String>()
        val observedExpected = mutableSetOf<String>()
        val observedKnownNegatives = mutableSetOf<String>()
        val verifiedKnownNegatives = mutableSetOf<String>()
        val factUsefulPivots = mutableMapOf<String, Int>()

        var falsePositives = 0
        var unlabelledExtra = 0
        var observedExtra = 0
        var unresolvedCandidateCount = 0
        var candidateCount = 0
        var unavailableEventCount = 0
        var providerFailureEventCount = 0
        var timeToFirstUsefulResultMs: Long? = null
        var timeToFirstVerifiedIdentityAnchorMs: Long? = null
        var timeToFirstHighValueExactIdentifierMs: Long? = null
        var timeTo50PercentRecallMs: Long? = null
        var timeTo80PercentRecallMs: Long? = null
        var usefulPivotsCount = 0

        for (event in events) {
            val key = factKey(event.fact)
            val accepted = event.status == EventStatus.VERIFIED || event.status == EventStatus.OBSERVED
            if (!accepted || event.fact.normalizedValue.isBlank()) {
                unresolvedCandidateCount++
                when (event.status) {
                    EventStatus.CANDIDATE -> candidateCount++
                    EventStatus.UNAVAILABLE -> unavailableEventCount++
                    EventStatus.PROVIDER_FAILURE -> providerFailureEventCount++
                    EventStatus.VERIFIED,
                    EventStatus.OBSERVED -> Unit
                }
                continue
            }

            // The initial seed is supplied by the attacker/user, so seeing it
            // again in fetched content is not a recovered exposure. Requests
            // and failure events are still accounted for above/below.
            if (event.isInitialSeed || key == initialSeedKey ||
                isEquivalentInitialSeed(event.fact, case.initialSeed)
            ) continue

            // Merge policy for duplicate events:
            // - Milestones retain the earliest observed time.
            // - Useful pivots are the maximum from verified events for a fact.
            // - Finding counts only consider each normalized fact once.
            if (timeToFirstUsefulResultMs == null) timeToFirstUsefulResultMs = event.elapsedTimeMs
            if (event.status == EventStatus.VERIFIED &&
                event.isIdentityAnchor &&
                timeToFirstVerifiedIdentityAnchorMs == null
            ) {
                timeToFirstVerifiedIdentityAnchorMs = event.elapsedTimeMs
            }
            if (event.fact.normalizedKind in HIGH_VALUE_KINDS &&
                timeToFirstHighValueExactIdentifierMs == null
            ) {
                timeToFirstHighValueExactIdentifierMs = event.elapsedTimeMs
            }

            if (event.status == EventStatus.VERIFIED) {
                val currentMaxPivots = factUsefulPivots.getOrDefault(key, 0)
                if (event.usefulPivotCount > currentMaxPivots) {
                    usefulPivotsCount += event.usefulPivotCount - currentMaxPivots
                    factUsefulPivots[key] = event.usefulPivotCount
                }
            }

            val isNewRecovered = recoveredFacts.add(key)
            val isNewVerified = event.status == EventStatus.VERIFIED && verifiedFacts.add(key)
            if (event.status == EventStatus.VERIFIED) {
                // [isNewVerified] records a state upgrade as well as a first
                // observation, so an observed value later verified still
                // receives truthful identity/false-positive accounting.
            } else {
                observedFacts.add(key)
            }

            if (key in expectedKeys) {
                recoveredExpected += key
                if (event.status == EventStatus.VERIFIED) {
                    verifiedExpected += key
                } else {
                    observedExpected += key
                }

                if (isNewRecovered) {
                    val recall = recoveredExpected.size.toDouble() / expectedKeys.size.coerceAtLeast(1)
                    if (recall >= 0.5 && timeTo50PercentRecallMs == null) {
                        timeTo50PercentRecallMs = event.elapsedTimeMs
                    }
                    if (recall >= 0.8 && timeTo80PercentRecallMs == null) {
                        timeTo80PercentRecallMs = event.elapsedTimeMs
                    }
                }
            } else if (key in negativeKeys) {
                if (event.status == EventStatus.OBSERVED) {
                    observedKnownNegatives.add(key)
                } else if (verifiedKnownNegatives.add(key)) {
                    // An observed contact is exposure evidence, not proof
                    // that the value belongs to the subject. Only a verified
                    // event can be a known-negative false positive.
                    falsePositives++
                }
            } else if (event.status == EventStatus.VERIFIED && isNewVerified) {
                if (case.isCompleteGroundTruth) {
                    falsePositives++
                } else {
                    unlabelledExtra++
                }
            } else if (event.status == EventStatus.OBSERVED && isNewRecovered) {
                // Keep open-world observations visible without promoting them
                // to verified identity findings or unlabelled verified extras.
                observedExtra++
            }
        }

        val truePositives = recoveredExpected.size
        val falseNegatives = expectedKeys.size - truePositives
        val observedNegativeCount = (observedKnownNegatives + verifiedKnownNegatives).size
        val falsePositiveRate = negativeKeys
            .takeIf { it.isNotEmpty() }
            ?.let { ratio(verifiedKnownNegatives.size, it.size) }
        val eventRequests = events.sumOf(DiscoveryEvent::requestCount)
        val eventFailedRequests = events
            .filter { it.status == EventStatus.UNAVAILABLE || it.status == EventStatus.PROVIDER_FAILURE }
            .sumOf(DiscoveryEvent::requestCount)
        val failedRequests = maxOf(eventFailedRequests, run.providerFailureCount)
        val totalRequests = maxOf(eventRequests, run.totalRequestCount ?: 0, failedRequests)
        val verifiedFindingCount = verifiedFacts.size
        val observedFindingCount = observedFacts.size
        val recoveredFindingCount = recoveredFacts.size
        val duration = run.totalScanDurationMs
            ?: (events.maxOfOrNull(DiscoveryEvent::elapsedTimeMs) ?: 0L)

        return SyntheticRunMetrics(
            truePositives = truePositives,
            falsePositives = falsePositives,
            falseNegatives = falseNegatives,
            precision = precisionRatio(truePositives, truePositives + falsePositives),
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
            totalProviderRequestCount = totalRequests,
            observedKnownNegativeCount = observedNegativeCount,
            knownNegativeCount = negativeKeys.size,
            candidateCount = candidateCount,
            unavailableEventCount = unavailableEventCount,
            providerFailureEventCount = providerFailureEventCount,
            usefulPivotCount = usefulPivotsCount,
            failedRequestCount = failedRequests,
            observedExpectedCount = observedExpected.size,
            verifiedExpectedCount = verifiedExpected.size,
            totalObservedFindingCount = observedFindingCount,
            totalRecoveredFindingCount = recoveredFindingCount,
            observedKnownNegativeEventCount = observedKnownNegatives.size,
            verifiedKnownNegativeCount = verifiedKnownNegatives.size,
            observedExtraCount = observedExtra
        )
    }

    fun aggregate(runs: List<SyntheticRunMetrics>): SyntheticAggregateMetrics {
        if (runs.isEmpty()) {
            return SyntheticAggregateMetrics(0, 0, 0, 0, 0.0, 0.0, 0.0)
        }
        val truePositives = runs.sumOf(SyntheticRunMetrics::truePositives)
        val falsePositives = runs.sumOf(SyntheticRunMetrics::falsePositives)
        val falseNegatives = runs.sumOf(SyntheticRunMetrics::falseNegatives)
        val observedKnownExposedFacts = runs.sumOf(SyntheticRunMetrics::observedExpectedCount)
        val verifiedKnownExposedFacts = runs.sumOf(SyntheticRunMetrics::verifiedExpectedCount)
        val knownExposedFacts = truePositives + falseNegatives
        val knownNegativeFacts = runs.sumOf(SyntheticRunMetrics::knownNegativeCount)
        val observedKnownNegativeFacts = runs.sumOf(SyntheticRunMetrics::observedKnownNegativeCount)
        val verifiedKnownNegativeFacts = runs.sumOf(SyntheticRunMetrics::verifiedKnownNegativeCount)
        val firstUseful = runs.mapNotNull(SyntheticRunMetrics::timeToFirstUsefulResultMs)
        val firstAnchor = runs.mapNotNull(SyntheticRunMetrics::timeToFirstVerifiedIdentityAnchorMs)
        val firstHighValue = runs.mapNotNull(SyntheticRunMetrics::timeToFirstHighValueExactIdentifierMs)
        val halfRecall = runs.mapNotNull(SyntheticRunMetrics::timeTo50PercentRecallMs)
        val eightyRecall = runs.mapNotNull(SyntheticRunMetrics::timeTo80PercentRecallMs)
        return SyntheticAggregateMetrics(
            totalCases = runs.size,
            truePositives = truePositives,
            falsePositives = falsePositives,
            falseNegatives = falseNegatives,
            averagePrecision = runs.map(SyntheticRunMetrics::precision).average(),
            averageRecall = runs.map(SyntheticRunMetrics::recall).average(),
            averageF1 = runs.map(SyntheticRunMetrics::f1).average(),
            // Unlabelled open-world findings are reported separately and are
            // intentionally not treated as false positives.
            corpusPrecision = zeroRatio(truePositives, truePositives + falsePositives),
            recallAtKnownExposure = ratio(truePositives, knownExposedFacts),
            corpusFalsePositiveRate = knownNegativeFacts
                .takeIf { it > 0 }
                ?.let { ratio(verifiedKnownNegativeFacts, it) },
            knownExposedFacts = knownExposedFacts,
            recoveredKnownExposedFacts = truePositives,
            knownNegativeFacts = knownNegativeFacts,
            observedKnownNegativeFacts = observedKnownNegativeFacts,
            averageTimeToFirstUsefulResultMs = firstUseful.averageOrNull(),
            averageTimeToFirstVerifiedIdentityAnchorMs = firstAnchor.averageOrNull(),
            averageTimeToFirstHighValueExactIdentifierMs = firstHighValue.averageOrNull(),
            averageTimeTo50PercentRecallMs = halfRecall.averageOrNull(),
            averageTimeTo80PercentRecallMs = eightyRecall.averageOrNull(),
            averageTotalScanDurationMs = runs.map { it.totalScanDurationMs.toDouble() }.average(),
            timeToFirstUsefulResultCaseCount = firstUseful.size,
            timeToFirstVerifiedIdentityAnchorCaseCount = firstAnchor.size,
            timeToFirstHighValueExactIdentifierCaseCount = firstHighValue.size,
            timeTo50PercentRecallCaseCount = halfRecall.size,
            timeTo80PercentRecallCaseCount = eightyRecall.size,
            unresolvedCandidateCount = runs.sumOf(SyntheticRunMetrics::unresolvedCandidateCount),
            candidateCount = runs.sumOf(SyntheticRunMetrics::candidateCount),
            unavailableEventCount = runs.sumOf(SyntheticRunMetrics::unavailableEventCount),
            providerFailureEventCount = runs.sumOf(SyntheticRunMetrics::providerFailureEventCount),
            unlabelledExtraCount = runs.sumOf(SyntheticRunMetrics::unlabelledExtraCount),
            totalVerifiedFindingCount = runs.sumOf(SyntheticRunMetrics::totalVerifiedFindingCount),
            totalProviderRequestCount = runs.sumOf(SyntheticRunMetrics::totalProviderRequestCount),
            totalFailedRequestCount = runs.sumOf(SyntheticRunMetrics::effectiveFailedRequestCount),
            totalUsefulPivotCount = runs.sumOf(SyntheticRunMetrics::effectiveUsefulPivotCount),
            providerFailureRate = zeroRatio(
                runs.sumOf(SyntheticRunMetrics::effectiveFailedRequestCount),
                runs.sumOf(SyntheticRunMetrics::totalProviderRequestCount)
            ),
            usefulFindingsPerRequest = zeroRatio(
                runs.sumOf(SyntheticRunMetrics::totalVerifiedFindingCount),
                runs.sumOf(SyntheticRunMetrics::totalProviderRequestCount)
            ),
            usefulPivotsPerVerifiedFinding = zeroRatio(
                runs.sumOf(SyntheticRunMetrics::effectiveUsefulPivotCount),
                runs.sumOf(SyntheticRunMetrics::totalVerifiedFindingCount)
            ),
            unlabelledFindingRate = zeroRatio(
                runs.sumOf(SyntheticRunMetrics::unlabelledExtraCount),
                runs.sumOf(SyntheticRunMetrics::totalVerifiedFindingCount)
            ),
            observedKnownExposedFacts = observedKnownExposedFacts,
            verifiedKnownExposedFacts = verifiedKnownExposedFacts,
            totalObservedFindingCount = runs.sumOf(SyntheticRunMetrics::totalObservedFindingCount),
            totalRecoveredFindingCount = runs.sumOf(SyntheticRunMetrics::totalRecoveredFindingCount),
            verifiedKnownNegativeFacts = verifiedKnownNegativeFacts,
            observedExtraCount = runs.sumOf(SyntheticRunMetrics::observedExtraCount)
        )
    }

    private fun List<Long>.averageOrNull(): Double? = takeIf { it.isNotEmpty() }?.average()

    private fun factKey(fact: Fact): String =
        "${fact.normalizedKind}:${fact.normalizedValue}"

    /**
     * Public-fetch adapters may represent a user URL seed as either `url` or
     * `profile`. Treat those URL-kind aliases as the same supplied seed while
     * leaving all other typed facts strict and independently matchable.
     */
    private fun isEquivalentInitialSeed(event: Fact, seed: Fact): Boolean {
        if (event.normalizedValue.isBlank() || event.normalizedValue != seed.normalizedValue) {
            return false
        }
        val urlKinds = setOf("url", "profile", "profile_url")
        return event.normalizedKind in urlKinds && seed.normalizedKind in urlKinds
    }

    private fun normalizeValue(kind: String, value: String?): String {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isBlank()) return ""
        return when (kind) {
            "email" -> trimmed.lowercase(Locale.ROOT)
            "phone" -> trimmed.filter(Char::isDigit)
            "username" -> trimmed.removePrefix("@").lowercase(Locale.ROOT)
            "profile", "profile_url", "website", "domain", "image", "photo", "document",
            "source_page", "reverse_image", "account" ->
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
        val precision = precisionRatio(tp, predicted)
        val recall = ratio(tp, expected)
        return if (precision + recall == 0.0) 0.0 else 2.0 * precision * recall / (precision + recall)
    }

    private fun precisionRatio(truePositives: Int, predictions: Int): Double =
        if (predictions == 0) 0.0 else truePositives.toDouble() / predictions.toDouble()

    private fun zeroRatio(numerator: Int, denominator: Int): Double =
        if (denominator == 0) 0.0 else numerator.toDouble() / denominator.toDouble()

    private val HIGH_VALUE_KINDS = setOf("email", "phone", "address", "postal_code")
}
