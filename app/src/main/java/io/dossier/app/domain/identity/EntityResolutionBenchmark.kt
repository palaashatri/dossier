package io.dossier.app.domain.identity

import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.ProfileScanResult
import java.security.MessageDigest
import kotlinx.serialization.Serializable

/**
 * The label attached to a benchmark case.  UNVERIFIABLE cases are kept out of
 * the labelled-positive/labelled-negative error rates, but their unsafe
 * positive rate is reported separately.
 */
@Serializable
enum class EntityResolutionExpected {
    BELONGS,
    DOES_NOT_BELONG,
    UNVERIFIABLE
}

/** A deterministic, non-sensitive resolver benchmark fixture. */
@Serializable
data class EntityResolutionBenchmarkCase(
    val id: String,
    val input: IdentityInput,
    val profile: ProfileScanResult,
    val expected: EntityResolutionExpected
)

/**
 * Metadata travels with results and calibration artifacts so a score cannot be
 * mistaken for a result from a different corpus or resolver implementation.
 */
@Serializable
data class EntityResolutionBenchmarkCorpus(
    val corpusId: String,
    val corpusVersion: String,
    val kind: EntityResolutionCorpusKind,
    val generatorVersion: String,
    val deterministicSeed: Long,
    val cases: List<EntityResolutionBenchmarkCase>
) {
    init {
        require(corpusId.isNotBlank()) { "Benchmark corpusId is required." }
        require(corpusVersion.isNotBlank()) { "Benchmark corpusVersion is required." }
        require(generatorVersion.isNotBlank()) { "Benchmark generatorVersion is required." }
        require(cases.isNotEmpty()) { "Benchmark corpus must contain at least one case." }
        require(cases.map { it.id }.distinct().size == cases.size) {
            "Benchmark case IDs must be unique."
        }
    }

    val digest: String
        get() = EntityResolutionBenchmark.digest(this)
}

@Serializable
enum class EntityResolutionCorpusKind {
    /** Synthetic fixtures exercise logic but cannot establish production calibration. */
    SYNTHETIC,

    /** Consented/appropriately licensed fixtures may be eligible after minimum-size checks. */
    CONSENTED
}

@Serializable
data class EntityResolutionBenchmarkMetrics(
    val truePositives: Int,
    val falsePositives: Int,
    val falseNegatives: Int,
    val trueNegatives: Int,
    val correctlyUnverifiable: Int,
    val unsafeUnverifiablePositives: Int,
    val total: Int
) {
    init {
        require(listOf(
            truePositives,
            falsePositives,
            falseNegatives,
            trueNegatives,
            correctlyUnverifiable,
            unsafeUnverifiablePositives,
            total
        ).all { it >= 0 }) { "Benchmark metrics cannot contain negative counts." }
        require(truePositives + falseNegatives + trueNegatives + falsePositives <= total) {
            "Labelled metrics cannot exceed the corpus size."
        }
        require(correctlyUnverifiable + unsafeUnverifiablePositives <= total) {
            "Unverifiable metrics cannot exceed the corpus size."
        }
        require(
            truePositives + falsePositives + falseNegatives + trueNegatives +
                correctlyUnverifiable + unsafeUnverifiablePositives == total
        ) {
            "Benchmark outcome counts must add up to the corpus size."
        }
    }

    val precision: Double
        get() = ratio(truePositives, truePositives + falsePositives)

    val recall: Double
        get() = ratio(truePositives, truePositives + falseNegatives)

    val f1: Double
        get() = if (precision + recall == 0.0) {
            0.0
        } else {
            2.0 * precision * recall / (precision + recall)
        }

    /** False-positive rate over explicitly labelled non-match cases only. */
    val falsePositiveRate: Double
        get() = ratio(falsePositives, falsePositives + trueNegatives)

    /** False-negative rate over explicitly labelled match cases only. */
    val falseNegativeRate: Double
        get() = ratio(falseNegatives, truePositives + falseNegatives)

    val unverifiableAccuracy: Double
        get() = ratio(correctlyUnverifiable, correctlyUnverifiable + unsafeUnverifiablePositives)

    private fun ratio(numerator: Int, denominator: Int): Double =
        if (denominator == 0) 1.0 else numerator.toDouble() / denominator.toDouble()
}

data class EntityResolutionBenchmarkEvaluation(
    val corpus: EntityResolutionBenchmarkCorpus,
    val metrics: EntityResolutionBenchmarkMetrics
) {
    val corpusDigest: String
        get() = corpus.digest
}

/**
 * Dependency-free benchmark runner for the production resolver.  A benchmark
 * is an evaluation harness, not an identity oracle: synthetic metrics are
 * useful regression evidence and are never presented as scientific estimates.
 */
object EntityResolutionBenchmark {
    const val BENCHMARK_VERSION = "entity-resolution-benchmark-v1"

    fun evaluate(
        corpus: EntityResolutionBenchmarkCorpus,
        calibration: EntityResolutionCalibrationArtifact? = null
    ): EntityResolutionBenchmarkEvaluation {
        require(corpus.generatorVersion == BENCHMARK_VERSION) {
            "Unsupported benchmark generator ${corpus.generatorVersion}."
        }
        var tp = 0
        var fp = 0
        var fn = 0
        var tn = 0
        var correctlyUnverifiable = 0
        var unsafeUnverifiable = 0
        val expectedCorpusDigest = calibration?.let { corpus.digest }

        corpus.cases.forEach { testCase ->
            val result = EntityResolverV2.resolve(
                testCase.input,
                testCase.profile,
                calibration,
                expectedCorpusDigest
            )
            val positive = result.band.isAtLeastMedium()
            when (testCase.expected) {
                EntityResolutionExpected.BELONGS -> if (positive) tp++ else fn++
                EntityResolutionExpected.DOES_NOT_BELONG -> if (positive) fp++ else tn++
                EntityResolutionExpected.UNVERIFIABLE -> if (positive) unsafeUnverifiable++ else correctlyUnverifiable++
            }
        }

        return EntityResolutionBenchmarkEvaluation(
            corpus = corpus,
            metrics = EntityResolutionBenchmarkMetrics(
                truePositives = tp,
                falsePositives = fp,
                falseNegatives = fn,
                trueNegatives = tn,
                correctlyUnverifiable = correctlyUnverifiable,
                unsafeUnverifiablePositives = unsafeUnverifiable,
                total = corpus.cases.size
            )
        )
    }

    /** Stable digest of ordered case content, independent of JVM hash ordering. */
    fun digest(corpus: EntityResolutionBenchmarkCorpus): String {
        val canonical = buildString {
            appendField(corpus.corpusId)
            appendField(corpus.corpusVersion)
            appendField(corpus.kind.name)
            appendField(corpus.generatorVersion)
            appendField(corpus.deterministicSeed)
            append('\n')
            corpus.cases.sortedBy { it.id }.forEach { testCase ->
                appendField(testCase.id)
                appendField(testCase.expected.name)
                appendField(canonicalInput(testCase.input))
                appendField(canonicalProfile(testCase.profile))
                append('\n')
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun canonicalInput(input: IdentityInput): String = listOf(
        input.fullName,
        input.aliases,
        input.emails,
        input.phones,
        input.locations,
        input.organizations,
        input.usernames,
        input.primaryUsername,
        input.profileUrls
    ).let(::canonicalValues)

    private fun canonicalProfile(profile: ProfileScanResult): String = listOf(
        profile.candidate.username,
        profile.candidate.platform.name,
        profile.candidate.url,
        profile.exists,
        profile.httpStatus,
        profile.displayName,
        profile.bio,
        profile.links,
        profile.extractedText,
        profile.findings.map(::canonicalFinding).sorted(),
        profile.verified,
        profile.verificationStatus,
        profile.providerId
    ).let(::canonicalValues)

    private fun canonicalFinding(finding: io.dossier.app.domain.model.Finding): String = buildString {
        appendField(finding.type.name)
        appendField(finding.value)
        appendField(finding.sourceUrl)
        appendField(finding.evidenceSnippet)
        appendField(finding.confidence)
        appendField(finding.risk.name)
        appendField(finding.remediation)
    }

    private fun canonicalValues(values: List<Any?>): String = buildString {
        values.forEach { value ->
            when (value) {
                is List<*> -> {
                    appendField(value.size)
                    value.forEach { appendField(it) }
                }
                else -> appendField(value)
            }
        }
    }

    private fun StringBuilder.appendField(value: Any?) {
        val text = value?.toString().orEmpty()
        append(text.length).append(':').append(text)
    }

    private fun ResolutionBand.isAtLeastMedium(): Boolean = when (this) {
        ResolutionBand.Confirmed,
        ResolutionBand.High,
        ResolutionBand.Medium -> true
        ResolutionBand.Low,
        ResolutionBand.Unresolved,
        ResolutionBand.Conflicting -> false
    }
}

/** Synthetic fixtures are intentionally small and cover positive/negative signals and contradictions. */
object EntityResolutionBenchmarkFixtures {
    const val CORPUS_ID = "dossier-entity-resolution-synthetic"
    const val CORPUS_VERSION = "1"
    const val DETERMINISTIC_SEED = 20260824L

    fun syntheticCorpus(): EntityResolutionBenchmarkCorpus = EntityResolutionBenchmarkCorpus(
        corpusId = CORPUS_ID,
        corpusVersion = CORPUS_VERSION,
        kind = EntityResolutionCorpusKind.SYNTHETIC,
        generatorVersion = EntityResolutionBenchmark.BENCHMARK_VERSION,
        deterministicSeed = DETERMINISTIC_SEED,
        cases = listOf(
            case(
                id = "belongs-explicit-profile",
                input = IdentityInput(
                    fullName = "Sample User",
                    primaryUsername = "sample_user",
                    profileUrls = listOf("https://github.com/sample_user")
                ),
                username = "sample_user",
                url = "https://github.com/sample_user",
                verified = true,
                displayName = "Sample User",
                expected = EntityResolutionExpected.BELONGS
            ),
            case(
                id = "belongs-independent-signals",
                input = IdentityInput(
                    fullName = "Jane Example",
                    primaryUsername = "jane_example",
                    organizations = listOf("Example Labs"),
                    profileUrls = listOf("https://jane.example.test/about")
                ),
                username = "jane_example",
                url = "https://github.com/jane_example",
                verified = true,
                displayName = "Jane Example",
                bio = "Engineer at Example Labs",
                links = listOf("https://jane.example.test/about"),
                expected = EntityResolutionExpected.BELONGS
            ),
            case(
                id = "does-not-belong-common-handle",
                input = IdentityInput(fullName = "", primaryUsername = "common_handle"),
                username = "common_handle",
                url = "https://github.com/common_handle",
                verified = false,
                expected = EntityResolutionExpected.DOES_NOT_BELONG
            ),
            case(
                id = "does-not-belong-name-contradiction",
                input = IdentityInput(fullName = "Alice Example", primaryUsername = "shared_handle"),
                username = "shared_handle",
                url = "https://github.com/shared_handle",
                verified = false,
                displayName = "Robert Different",
                expected = EntityResolutionExpected.DOES_NOT_BELONG
            ),
            case(
                id = "does-not-belong-verified-common-handle",
                input = IdentityInput(fullName = "", primaryUsername = "verified_common"),
                username = "verified_common",
                url = "https://github.com/verified_common",
                verified = true,
                expected = EntityResolutionExpected.DOES_NOT_BELONG
            ),
            case(
                id = "belongs-temporarily-not-found",
                input = IdentityInput(fullName = "Morgan Example", primaryUsername = "morgan"),
                username = "morgan",
                url = "https://example.test/morgan",
                verified = false,
                exists = false,
                verificationStatus = "not_found",
                expected = EntityResolutionExpected.BELONGS
            ),
            case(
                id = "unverifiable-login-wall",
                input = IdentityInput(fullName = "Taylor Example", primaryUsername = "taylor"),
                username = "taylor",
                url = "https://example.test/taylor",
                verified = false,
                exists = true,
                verificationStatus = "authentication_required",
                expected = EntityResolutionExpected.UNVERIFIABLE
            )
        )
    )

    private fun case(
        id: String,
        input: IdentityInput,
        username: String,
        url: String,
        verified: Boolean,
        expected: EntityResolutionExpected,
        displayName: String? = null,
        bio: String? = null,
        links: List<String> = emptyList(),
        exists: Boolean = true,
        verificationStatus: String? = if (verified) "verified" else "review"
    ): EntityResolutionBenchmarkCase {
        val profile = ProfileScanResult(
            candidate = io.dossier.app.domain.model.UsernameCandidate(
                username = username,
                platform = io.dossier.app.domain.model.Platform.GitHub,
                url = url,
                matchType = io.dossier.app.domain.model.UsernameMatchType.Exact,
                confidence = if (verified) 0.9f else 0.5f
            ),
            exists = exists,
            httpStatus = if (exists) 200 else 404,
            displayName = displayName,
            bio = bio,
            links = links,
            extractedText = listOfNotNull(displayName, bio).joinToString(" "),
            findings = emptyList(),
            confidenceSignals = emptyList(),
            verified = verified,
            verificationStatus = verificationStatus
        )
        return EntityResolutionBenchmarkCase(id, input, profile, expected)
    }
}
