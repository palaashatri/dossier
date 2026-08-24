package io.dossier.app.domain.identity

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * A resolver policy contains engineering thresholds, not probabilities.  A
 * policy only reaches production through [EntityResolutionCalibrationLoader]
 * and the consented-corpus gates below.
 */
@Serializable
data class EntityResolutionPolicy(
    val lowScore: Double = 0.20,
    val corroboratedMediumScore: Double = 0.35,
    val mediumScore: Double = 0.45,
    val highScore: Double = 0.72,
    val highMinimumNonUsernameSignals: Int = 2,
    val contradictionWeight: Double = 0.40,
    val featureWeights: Map<CorrelationFeature, Double> = emptyMap()
) {
    init {
        require(listOf(lowScore, corroboratedMediumScore, mediumScore, highScore).all { it.isFinite() }) {
            "Resolver thresholds must be finite."
        }
        require(lowScore in 0.0..1.0)
        require(corroboratedMediumScore in lowScore..1.0)
        require(mediumScore in corroboratedMediumScore..1.0)
        require(highScore in mediumScore..1.0)
        require(highMinimumNonUsernameSignals in 1..16)
        require(contradictionWeight in 0.0..1.0)
        require(featureWeights.keys.all { it != CorrelationFeature.UserSuppliedProfile }) {
            "User-supplied profile confirmation cannot be calibrated away."
        }
        require(featureWeights.values.all { it.isFinite() && it in -1.0..1.0 }) {
            "Resolver feature weights must be finite values between -1 and 1."
        }
        require(featureWeights
            .filterKeys { it != CorrelationFeature.ConflictingDisplayName && it != CorrelationFeature.ConflictingPersonalWebsite }
            .values.all { it >= 0.0 }) {
            "Supporting resolver feature weights cannot be negative."
        }
        require(featureWeights
            .filterKeys { it == CorrelationFeature.ConflictingDisplayName || it == CorrelationFeature.ConflictingPersonalWebsite }
            .values.all { it <= 0.0 }) {
            "Contradiction feature weights cannot be positive."
        }
    }

    fun weight(feature: CorrelationFeature, fallback: Double): Double =
        featureWeights[feature] ?: fallback

    companion object {
        val DEFAULT = EntityResolutionPolicy()
    }
}

/** Versioned result envelope produced by the offline resolver benchmark. */
@Serializable
data class EntityResolutionCalibrationArtifact(
    val schemaVersion: Int,
    val resolverVersion: String,
    val benchmarkVersion: String,
    val corpusId: String,
    val corpusVersion: String,
    val corpusKind: EntityResolutionCorpusKind,
    val corpusDigest: String,
    val deterministicSeed: Long,
    val sampleCount: Int,
    val positiveCaseCount: Int,
    val negativeCaseCount: Int,
    val unverifiableCaseCount: Int,
    val metrics: EntityResolutionBenchmarkMetrics,
    val policy: EntityResolutionPolicy,
    val source: String
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) { "Unsupported resolver calibration schema." }
        require(resolverVersion == EntityResolverV2.RESOLVER_VERSION) {
            "Calibration resolver version does not match the active resolver."
        }
        require(benchmarkVersion == EntityResolutionBenchmark.BENCHMARK_VERSION) {
            "Calibration benchmark version is unsupported."
        }
        require(corpusId.isNotBlank() && corpusVersion.isNotBlank())
        require(corpusDigest.matches(SHA256_PATTERN)) { "Calibration corpusDigest must be SHA-256." }
        require(sampleCount > 0)
        require(positiveCaseCount >= 0 && negativeCaseCount >= 0 && unverifiableCaseCount >= 0)
        require(positiveCaseCount + negativeCaseCount + unverifiableCaseCount == sampleCount) {
            "Calibration case counts must add up to sampleCount."
        }
        require(metrics.total == sampleCount) { "Calibration metrics total must equal sampleCount." }
        require(metrics.truePositives + metrics.falseNegatives == positiveCaseCount) {
            "Calibration positive metrics do not match positiveCaseCount."
        }
        require(metrics.trueNegatives + metrics.falsePositives == negativeCaseCount) {
            "Calibration negative metrics do not match negativeCaseCount."
        }
        require(metrics.correctlyUnverifiable + metrics.unsafeUnverifiablePositives == unverifiableCaseCount) {
            "Calibration unverifiable metrics do not match unverifiableCaseCount."
        }
        require(source.isNotBlank()) { "Calibration source is required." }
    }

    /**
     * Synthetic data is accepted for regression reporting, but only a sizeable
     * consented corpus may alter production thresholds.
     */
    fun productionPolicyOrNull(expectedCorpusDigest: String? = null): EntityResolutionPolicy? {
        if (corpusKind != EntityResolutionCorpusKind.CONSENTED) return null
        if (positiveCaseCount < MIN_CONSENTED_POSITIVE_CASES) return null
        if (negativeCaseCount < MIN_CONSENTED_NEGATIVE_CASES) return null
        if (expectedCorpusDigest == null ||
            !corpusDigest.equals(expectedCorpusDigest, ignoreCase = true)
        ) return null
        if (metrics.truePositives + metrics.falseNegatives != positiveCaseCount) return null
        if (metrics.trueNegatives + metrics.falsePositives != negativeCaseCount) return null
        if (metrics.correctlyUnverifiable + metrics.unsafeUnverifiablePositives != unverifiableCaseCount) return null
        return policy
    }

    fun toJson(): String = CALIBRATION_JSON.encodeToString(this)

    companion object {
        const val SCHEMA_VERSION = 1
        const val MIN_CONSENTED_POSITIVE_CASES = 100
        const val MIN_CONSENTED_NEGATIVE_CASES = 100
        private val SHA256_PATTERN = Regex("[a-fA-F0-9]{64}")

        fun fromEvaluation(
            evaluation: EntityResolutionBenchmarkEvaluation,
            policy: EntityResolutionPolicy,
            source: String
        ): EntityResolutionCalibrationArtifact {
            val metrics = evaluation.metrics
            val positive = metrics.truePositives + metrics.falseNegatives
            val negative = metrics.trueNegatives + metrics.falsePositives
            val unverifiable = metrics.correctlyUnverifiable + metrics.unsafeUnverifiablePositives
            return EntityResolutionCalibrationArtifact(
                schemaVersion = SCHEMA_VERSION,
                resolverVersion = EntityResolverV2.RESOLVER_VERSION,
                benchmarkVersion = EntityResolutionBenchmark.BENCHMARK_VERSION,
                corpusId = evaluation.corpus.corpusId,
                corpusVersion = evaluation.corpus.corpusVersion,
                corpusKind = evaluation.corpus.kind,
                corpusDigest = evaluation.corpusDigest,
                deterministicSeed = evaluation.corpus.deterministicSeed,
                sampleCount = metrics.total,
                positiveCaseCount = positive,
                negativeCaseCount = negative,
                unverifiableCaseCount = unverifiable,
                metrics = metrics,
                policy = policy,
                source = source
            )
        }
    }
}

sealed interface EntityResolutionCalibrationLoadResult {
    data class Accepted(val artifact: EntityResolutionCalibrationArtifact) : EntityResolutionCalibrationLoadResult
    data class Rejected(val reason: String) : EntityResolutionCalibrationLoadResult
}

/** Strict parser used by import/runtime code; malformed artifacts are never partially applied. */
object EntityResolutionCalibrationLoader {
    private val json = CALIBRATION_JSON

    fun load(
        serialized: String,
        expectedCorpusDigest: String? = null
    ): EntityResolutionCalibrationLoadResult {
        val artifact = runCatching { json.decodeFromString<EntityResolutionCalibrationArtifact>(serialized) }
            .getOrElse { return EntityResolutionCalibrationLoadResult.Rejected("Malformed calibration: ${it.message}") }
        if (expectedCorpusDigest != null && !artifact.corpusDigest.equals(expectedCorpusDigest, ignoreCase = true)) {
            return EntityResolutionCalibrationLoadResult.Rejected("Calibration corpus digest does not match the evaluated corpus.")
        }
        return EntityResolutionCalibrationLoadResult.Accepted(artifact)
    }

    fun loadOrNull(serialized: String, expectedCorpusDigest: String? = null): EntityResolutionCalibrationArtifact? =
        (load(serialized, expectedCorpusDigest) as? EntityResolutionCalibrationLoadResult.Accepted)?.artifact
}

private val CALIBRATION_JSON = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
}
