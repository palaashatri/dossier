package io.dossier.app.data.ai

import io.dossier.app.domain.ai.AiAnalysisSnapshot
import io.dossier.app.domain.ai.AiClaimConfidence
import io.dossier.app.domain.ai.EvidenceGroundedAiValidator
import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.model.IdentityInput

/** Evaluation corpus kind; synthetic fixtures are regression evidence, not scientific metrics. */
enum class AiEvaluationCorpusKind {
    SYNTHETIC,
    CONSENTED
}

data class AiEvaluationFixture(
    val id: String,
    val snapshot: AiAnalysisSnapshot,
    val rawModelOutput: String?,
    val expected: ExpectedOutcome
)

enum class ExpectedOutcome {
    ACCEPTED,
    REJECTED,
    FALLBACK
}

data class AiEvaluationCorpus(
    val corpusId: String,
    val version: String,
    val kind: AiEvaluationCorpusKind,
    val fixtures: List<AiEvaluationFixture>
) {
    init {
        require(corpusId.isNotBlank())
        require(version.isNotBlank())
        require(fixtures.isNotEmpty())
        require(fixtures.map(AiEvaluationFixture::id).distinct().size == fixtures.size) {
            "AI evaluation fixture IDs must be unique."
        }
    }
}

data class AiEvaluationCaseResult(
    val id: String,
    val expected: ExpectedOutcome,
    val actual: ExpectedOutcome,
    val acceptedClaimCount: Int,
    val rejectedReasonCodes: List<String>,
    val contradictionDowngradeCount: Int = 0
) {
    val passed: Boolean
        get() = expected == actual
}

data class AiEvaluationMetrics(
    val total: Int,
    val acceptedCases: Int,
    val rejectedCases: Int,
    val fallbackCases: Int,
    val unknownEvidenceRejections: Int,
    val unsupportedIdentifierRejections: Int,
    val contradictionDowngrades: Int,
    val fallbackOutputsProduced: Int,
    val passedCases: Int
) {
    val allCasesPassed: Boolean
        get() = total == passedCases
}

data class AiEvaluationReport(
    val corpus: AiEvaluationCorpus,
    val cases: List<AiEvaluationCaseResult>,
    val metrics: AiEvaluationMetrics
)

/**
 * Deterministic production-boundary harness. It evaluates parser/validator and
 * the same baseline fallback used by the service; it never creates evidence or
 * claims that these small synthetic fixtures calibrate a real model.
 */
object AiProductionEvaluation {
    const val CORPUS_VERSION = "ai-evidence-boundary-v1"

    fun evaluate(corpus: AiEvaluationCorpus): AiEvaluationReport {
        val results = corpus.fixtures.sortedBy(AiEvaluationFixture::id).map { fixture ->
            val parsed = fixture.rawModelOutput?.let(AiInsightService::parseStructuredResultForEvaluation)
            if (parsed == null) {
                val fallback = AiInsightService.buildBaselineSummary(fixture.snapshot)
                AiEvaluationCaseResult(
                    id = fixture.id,
                    expected = fixture.expected,
                    actual = if (fallback.isNotBlank()) ExpectedOutcome.FALLBACK else ExpectedOutcome.REJECTED,
                    acceptedClaimCount = 0,
                    rejectedReasonCodes = emptyList()
                )
            } else {
                val validation = EvidenceGroundedAiValidator.validate(
                    result = parsed,
                    evidence = fixture.snapshot.evidence,
                    remediationLinks = fixture.snapshot.remediationLinks
                )
                val actual = if (validation.acceptedClaims.isNotEmpty()) {
                    ExpectedOutcome.ACCEPTED
                } else {
                    ExpectedOutcome.REJECTED
                }
                AiEvaluationCaseResult(
                    id = fixture.id,
                    expected = fixture.expected,
                    actual = actual,
                    acceptedClaimCount = validation.acceptedClaims.size,
                    rejectedReasonCodes = validation.rejectedClaims.map { it.reason }.distinct(),
                    contradictionDowngradeCount = validation.acceptedClaims.count {
                        it.confidence == AiClaimConfidence.CONFLICTING && it.contradictingEvidence.isNotEmpty()
                    }
                )
            }
        }

        val metrics = AiEvaluationMetrics(
            total = results.size,
            acceptedCases = results.count { it.actual == ExpectedOutcome.ACCEPTED },
            rejectedCases = results.count { it.actual == ExpectedOutcome.REJECTED },
            fallbackCases = results.count { it.actual == ExpectedOutcome.FALLBACK },
            unknownEvidenceRejections = results.count { "unknown_evidence_id" in it.rejectedReasonCodes },
            unsupportedIdentifierRejections = results.count { "unsupported_identifier_in_claim" in it.rejectedReasonCodes },
            contradictionDowngrades = results.sumOf(AiEvaluationCaseResult::contradictionDowngradeCount),
            fallbackOutputsProduced = results.count { it.actual == ExpectedOutcome.FALLBACK },
            passedCases = results.count(AiEvaluationCaseResult::passed)
        )
        return AiEvaluationReport(corpus, results, metrics)
    }

    /** Small non-sensitive corpus for deterministic regression tests and CI. */
    fun syntheticCorpus(): AiEvaluationCorpus {
        val snapshot = AiAnalysisSnapshot.from(
            input = IdentityInput(fullName = "Synthetic Subject", primaryUsername = "synthetic_handle"),
            evidence = listOf(
                Evidence(
                    id = "synthetic-email",
                    kind = EvidenceKind.Email,
                    value = "synthetic@example.test",
                    sourceUrl = "https://example.test/contact",
                    state = EvidenceState.Observed
                ),
                Evidence(
                    id = "synthetic-profile",
                    kind = EvidenceKind.Profile,
                    value = "https://example.test/profile",
                    sourceUrl = "https://example.test/profile",
                    state = EvidenceState.Candidate
                )
            )
        )
        val emailId = snapshot.evidence.first { it.kind == EvidenceKind.Email }.id
        val profileId = snapshot.evidence.first { it.kind == EvidenceKind.Profile }.id
        return AiEvaluationCorpus(
            corpusId = "dossier-ai-synthetic-boundary",
            version = CORPUS_VERSION,
            kind = AiEvaluationCorpusKind.SYNTHETIC,
            fixtures = listOf(
                AiEvaluationFixture(
                    id = "accepted-grounded-claim",
                    snapshot = snapshot,
                    rawModelOutput = resultJson(
                        claim = "A public contact detail is present.",
                        supporting = listOf(emailId)
                    ),
                    expected = ExpectedOutcome.ACCEPTED
                ),
                AiEvaluationFixture(
                    id = "rejected-unknown-evidence-id",
                    snapshot = snapshot,
                    rawModelOutput = resultJson(
                        claim = "An unsupported profile was found.",
                        supporting = listOf("ev2:does-not-exist")
                    ),
                    expected = ExpectedOutcome.REJECTED
                ),
                AiEvaluationFixture(
                    id = "rejected-unsupported-identifier",
                    snapshot = snapshot,
                    rawModelOutput = resultJson(
                        claim = "The email other@example.test is exposed.",
                        supporting = listOf(emailId)
                    ),
                    expected = ExpectedOutcome.REJECTED
                ),
                AiEvaluationFixture(
                    id = "accepted-contradiction-downgrade",
                    snapshot = snapshot,
                    rawModelOutput = resultJson(
                        claim = "The profile remains a review candidate.",
                        supporting = listOf(profileId),
                        contradicting = listOf(emailId),
                        confidence = AiClaimConfidence.HIGH.name
                    ),
                    expected = ExpectedOutcome.ACCEPTED
                ),
                AiEvaluationFixture(
                    id = "fallback-malformed-output",
                    snapshot = snapshot,
                    rawModelOutput = "not-json",
                    expected = ExpectedOutcome.FALLBACK
                )
            )
        )
    }

    private fun resultJson(
        claim: String,
        supporting: List<String>,
        contradicting: List<String> = emptyList(),
        confidence: String = AiClaimConfidence.HIGH.name
    ): String {
        fun array(values: List<String>) = values.joinToString(",", "[", "]") { "\"$it\"" }
        return """{
          "claims": [{
            "claim": "$claim",
            "confidence": "$confidence",
            "supportingEvidence": ${array(supporting)},
            "contradictingEvidence": ${array(contradicting)},
            "reasoningSummary": "Supported only by the cited evidence.",
            "recommendedAction": null
          }]
        }""".trimIndent()
    }
}
