package io.dossier.app.domain.ai

import io.dossier.app.domain.evidence.Evidence
import kotlinx.serialization.Serializable

@Serializable
enum class AiClaimConfidence {
    HIGH,
    MEDIUM,
    LOW,
    UNRESOLVED,
    CONFLICTING
}

@Serializable
data class AiAnalysisClaim(
    val claim: String,
    val confidence: AiClaimConfidence,
    val supportingEvidence: List<String>,
    val contradictingEvidence: List<String> = emptyList(),
    val reasoningSummary: String,
    val recommendedAction: String? = null
)

@Serializable
data class AiAnalysisResult(
    val claims: List<AiAnalysisClaim>
)

data class ValidatedAiAnalysis(
    val acceptedClaims: List<AiAnalysisClaim>,
    val rejectedClaims: List<RejectedAiClaim>
)

data class RejectedAiClaim(
    val claim: AiAnalysisClaim,
    val reasons: List<String>
)

/**
 * Deterministic output gate between any language model and production UI.
 * Models may summarize evidence; they cannot create new evidence IDs or bypass
 * contradictory evidence already present in their own structured response.
 */
object EvidenceGroundedAiValidator {
    private const val MAX_CLAIM_CHARS = 600
    private const val MAX_REASONING_CHARS = 1_500
    private const val MAX_ACTION_CHARS = 800
    private const val MAX_CLAIMS = 20

    fun validate(result: AiAnalysisResult, evidence: List<Evidence>): ValidatedAiAnalysis {
        val byId = evidence.associateBy(Evidence::id)
        val accepted = mutableListOf<AiAnalysisClaim>()
        val rejected = mutableListOf<RejectedAiClaim>()

        result.claims.take(MAX_CLAIMS).forEach { original ->
            val reasons = mutableListOf<String>()
            val supportIds = original.supportingEvidence.distinct()
            val contradictionIds = original.contradictingEvidence.distinct()

            if (original.claim.isBlank() || original.claim.length > MAX_CLAIM_CHARS) {
                reasons += "Claim text is blank or exceeds the output bound"
            }
            if (original.reasoningSummary.isBlank() || original.reasoningSummary.length > MAX_REASONING_CHARS) {
                reasons += "Reasoning summary is blank or exceeds the output bound"
            }
            if (original.recommendedAction?.length ?: 0 > MAX_ACTION_CHARS) {
                reasons += "Recommended action exceeds the output bound"
            }
            if (supportIds.isEmpty()) {
                reasons += "Factual claim has no supporting evidence IDs"
            }
            val missingSupport = supportIds.filterNot(byId::containsKey)
            if (missingSupport.isNotEmpty()) {
                reasons += "Unknown supporting evidence IDs: ${missingSupport.joinToString()}"
            }
            val missingContradictions = contradictionIds.filterNot(byId::containsKey)
            if (missingContradictions.isNotEmpty()) {
                reasons += "Unknown contradicting evidence IDs: ${missingContradictions.joinToString()}"
            }

            if (reasons.isNotEmpty()) {
                rejected += RejectedAiClaim(original, reasons)
                return@forEach
            }

            // A model cannot call a relationship HIGH while simultaneously
            // citing contradictory evidence. Keep the claim but downgrade it so
            // uncertainty remains visible and inspectable.
            val confidence = if (
                contradictionIds.isNotEmpty() && original.confidence == AiClaimConfidence.HIGH
            ) {
                AiClaimConfidence.CONFLICTING
            } else {
                original.confidence
            }

            accepted += original.copy(
                confidence = confidence,
                supportingEvidence = supportIds,
                contradictingEvidence = contradictionIds,
                claim = original.claim.trim(),
                reasoningSummary = original.reasoningSummary.trim(),
                recommendedAction = original.recommendedAction?.trim()?.takeIf(String::isNotBlank)
            )
        }

        if (result.claims.size > MAX_CLAIMS) {
            result.claims.drop(MAX_CLAIMS).forEach { claim ->
                rejected += RejectedAiClaim(claim, listOf("Claim limit exceeded"))
            }
        }

        return ValidatedAiAnalysis(accepted, rejected)
    }
}
