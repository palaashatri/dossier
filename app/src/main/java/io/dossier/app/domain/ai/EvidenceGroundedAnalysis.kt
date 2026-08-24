package io.dossier.app.domain.ai

import io.dossier.app.domain.evidence.Evidence
import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
enum class AiClaimConfidence {
    HIGH,
    MEDIUM,
    LOW,
    UNRESOLVED,
    CONFLICTING
}

/** Backward-compatible name retained for evaluation fixtures and saved integrations. */
typealias AiConfidence = AiClaimConfidence

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
) {
        /** Stable machine-readable compatibility code for deterministic evaluation/UI. */
        val reason: String
        get() = when {
            reasons.any { it.startsWith("Unknown supporting evidence IDs", ignoreCase = true) } ||
                reasons.any { it.startsWith("Unknown contradicting evidence IDs", ignoreCase = true) } ->
                "unknown_evidence_id"
            reasons.any { it.contains("no supporting evidence", ignoreCase = true) } ->
                "claim_has_no_supporting_evidence"
            reasons.any { it.startsWith("Identity attribution", ignoreCase = true) } ->
                "identity_attribution_requires_confirmation"
            reasons.any { it.startsWith("Unsupported identifier", ignoreCase = true) } ->
                "unsupported_identifier_in_claim"
            reasons.any { it.startsWith("Remediation outcome", ignoreCase = true) } ->
                "remediation_outcome_requires_verification"
            reasons.any { it.contains("Claim limit exceeded", ignoreCase = true) } ->
                "claim_budget_exceeded"
            else -> "validation_failed"
        }
}

/**
 * Deterministic output gate between any language model and production UI.
 * Models may summarize evidence; they cannot create new evidence IDs, introduce
 * uncited identifiers, or bypass contradictory evidence already present in their
 * own structured response.
 */
object EvidenceGroundedAiValidator {
    private const val MAX_CLAIM_CHARS = 600
    private const val MAX_REASONING_CHARS = 1_500
    private const val MAX_ACTION_CHARS = 800
    private const val MAX_CLAIMS = 20

    // Terminal punctuation such as the full stop ending a sentence is intentionally
    // allowed after the address; it is not part of the email identifier itself.
    private val EMAIL = Regex("(?i)(?<![A-Z0-9._%+-])[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}(?![A-Z0-9_%+-])")
    private val URL = Regex("(?i)https?://[^\\s<>\\[\\]{}\\\"']+")
    private val PHONE = Regex("(?<!\\d)(?:\\+?\\d[\\d .()\\-]{5,}\\d)(?!\\d)")
    private val HANDLE = Regex("(?<![A-Za-z0-9_])@[A-Za-z0-9][A-Za-z0-9._-]{2,64}(?![A-Za-z0-9_])")
    private val USERNAME_CUE = Regex(
        "(?i)\\b(?:username|handle)\\s*(?:is|:|=)\\s*@?([A-Za-z0-9][A-Za-z0-9._-]{2,64})\\b"
    )
    private val NAME_CUE = Regex(
        "\\b(?i:display name|full name|name)\\s*(?:(?i:is)|:|=)\\s*((?-i:[A-Z][a-z]{2,}(?:\\s+[A-Z][a-z]{2,})*))"
    )
    private val PROPER_NAME = Regex("\\b[A-Z][a-z]{2,}(?:\\s+[A-Z][a-z]{2,})*\\b")
    private val NAME_STOP_WORDS = setOf(
        "A", "An", "The", "This", "That", "These", "Those", "It", "They", "We", "I", "No", "Public",
        "Profile", "Account", "Candidate", "Verified", "Observed", "Possible", "Likely", "Unknown",
        "Shared", "Page", "Evidence", "Summary", "Reasoning", "Request", "Provider", "Data"
    ).map { it.lowercase(Locale.US) }.toSet()
    private val IDENTITY_ATTRIBUTION = Regex(
        "(?i)(?:" +
            "\\b(?:owns?|owned by|belongs?[- ]to|account[- ]of|profile[- ]of|" +
            "account holder|account owner|profile owner|identity of|" +
            "same[- ]person|same[- ]account|same[- ]individual|one person|single person)\\b" +
            "|\\b(?:uses?|operates?|controls?|runs?|manages?|administers?|maintains?)\\s+" +
            "(?:this|that|the)?\\s*(?:account|profile)\\b" +
            "|\\b(?:this|that|the)?\\s*(?:account|profile)\\s+" +
            "(?:is|was)?\\s*(?:used|operated|controlled|run|managed|administered|maintained)\\s+by\\b" +
            ")"
    )
    private val ATTRIBUTION_QUALIFIER = Regex(
        "(?i)\\b(?:may|might|could|possible|possibly|candidate|appears?|seems?|likely|uncertain|unresolved|unknown|unverified|not known|not (?:proof|confirmed)|cannot confirm)\\b"
    )
    private val REMEDIATION_OUTCOME = Regex(
        "(?i)(?:" +
            "\\b(?:provider|site|service|platform|company|they|it|this data|the data|your data)\\b" +
            "[^\\n.!?]{0,80}\\b(?:removed|deleted|erased|purged|completed|submitted|verified|confirmed)\\b" +
            "|\\b(?:removal|deletion|request|submission)\\b\\s+" +
            "(?:is|was|has been|have been)\\s+" +
            "(?:complete|completed|submitted|verified|confirmed|successful)\\b" +
            "|\\b(?:verified|confirmed)\\s+(?:removal|deletion)\\b" +
            ")"
    )

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
            val attributionCorpus = "${original.claim}\n${original.reasoningSummary}"
            if (hasUnqualifiedIdentityAttribution(attributionCorpus)) {
                reasons += "Identity attribution requires explicit confirmed evidence"
            }
            val remediationCorpus = buildList {
                add(original.claim)
                add(original.reasoningSummary)
                original.recommendedAction?.let(::add)
            }.joinToString("\n")
            if (REMEDIATION_OUTCOME.containsMatchIn(remediationCorpus)) {
                reasons += "Remediation outcome requires a matching verified remediation record"
            }
            if (supportIds.isEmpty()) {
                reasons += "Factual claim has no supporting evidence IDs"
            }
            val missingSupport = supportIds.filterNot(byId::containsKey)
            if (missingSupport.isNotEmpty()) {
                reasons += "Unknown supporting evidence IDs: ${missingSupport.joinToString()}"
            }
            val unusableSupport = supportIds.mapNotNull { id ->
                byId[id]?.takeIf { it.state == io.dossier.app.domain.evidence.EvidenceState.Rejected || it.state == io.dossier.app.domain.evidence.EvidenceState.Unavailable }
                    ?.let { id }
            }
            if (unusableSupport.isNotEmpty()) {
                reasons += "Supporting evidence IDs are rejected or unavailable: ${unusableSupport.joinToString()}"
            }
            val missingContradictions = contradictionIds.filterNot(byId::containsKey)
            if (missingContradictions.isNotEmpty()) {
                reasons += "Unknown contradicting evidence IDs: ${missingContradictions.joinToString()}"
            }
            val unusableContradictions = contradictionIds.mapNotNull { id ->
                byId[id]?.takeIf {
                    it.state == io.dossier.app.domain.evidence.EvidenceState.Rejected ||
                        it.state == io.dossier.app.domain.evidence.EvidenceState.Unavailable
                }?.let { id }
            }
            if (unusableContradictions.isNotEmpty()) {
                reasons += "Contradicting evidence IDs are rejected or unavailable: ${unusableContradictions.joinToString()}"
            }

            // A cited evidence ID cannot be used as cover for a fabricated email/URL.
            // Every explicit identifier emitted by the model must occur in at least one
            // of the evidence records the claim actually cites.
            if (missingSupport.isEmpty() && missingContradictions.isEmpty() && supportIds.isNotEmpty()) {
                val cited = (supportIds + contradictionIds).mapNotNull(byId::get)
                val citedCorpus = cited.joinToString("\n") { record ->
                    buildList {
                        add(record.value)
                        record.sourceUrl?.let(::add)
                        record.snippet?.let(::add)
                        addAll(record.signals)
                    }.joinToString("\n")
                }
                val citedIdentifierKeys = extractIdentifiers(citedCorpus)
                    .mapTo(mutableSetOf()) { identifier -> identifierKey(identifier) }

                val outputCorpus = buildList {
                    add(original.claim)
                    add(original.reasoningSummary)
                    original.recommendedAction?.let(::add)
                }.joinToString("\n")

                val identifiers = extractIdentifiers(outputCorpus)
                val unsupported = identifiers.filterNot { identifier ->
                    when (identifier.kind) {
                        IdentifierKind.Email,
                        IdentifierKind.Url,
                        IdentifierKind.Phone,
                        IdentifierKind.Username -> citedIdentifierKeys.contains(identifierKey(identifier))
                        IdentifierKind.Name -> containsToken(citedCorpus, identifier.normalized)
                    }
                }
                if (unsupported.isNotEmpty()) {
                    reasons += "Unsupported identifier in claim: " +
                        unsupported.take(3).joinToString { it.raw }
                }
            }

            if (reasons.isNotEmpty()) {
                rejected += RejectedAiClaim(original, reasons)
                return@forEach
            }

            // A model cannot call a relationship HIGH while simultaneously
            // citing contradictory evidence. Keep the claim but downgrade it so
            // uncertainty remains visible and inspectable.
            val supportStates = supportIds.mapNotNull { byId[it]?.state }
            val confidence = when {
                contradictionIds.isNotEmpty() -> AiClaimConfidence.CONFLICTING
                supportStates.any { it == io.dossier.app.domain.evidence.EvidenceState.Conflicting } -> AiClaimConfidence.CONFLICTING
                supportStates.any {
                    it == io.dossier.app.domain.evidence.EvidenceState.Observed ||
                        it == io.dossier.app.domain.evidence.EvidenceState.Candidate ||
                        it == io.dossier.app.domain.evidence.EvidenceState.Probable
                } -> AiClaimConfidence.UNRESOLVED
                else -> original.confidence
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

    private enum class IdentifierKind {
        Email,
        Url,
        Phone,
        Username,
        Name
    }

    private data class MentionedIdentifier(
        val raw: String,
        val normalized: String,
        val kind: IdentifierKind
    )

    private fun extractIdentifiers(text: String): Set<MentionedIdentifier> {
        val emailMentions = EMAIL.findAll(text).map { match ->
            MentionedIdentifier(match.value, match.value.lowercase(), IdentifierKind.Email)
        }
        val urlMentions = URL.findAll(text).map { match ->
            val value = match.value.trimEnd('.', ',', ';', ':', '!', '?', ')')
            MentionedIdentifier(value, normalizeUrl(value), IdentifierKind.Url)
        }
        val phoneMentions = PHONE.findAll(text).map { match ->
            MentionedIdentifier(match.value, normalizePhone(match.value), IdentifierKind.Phone)
        }
        val handleMentions = HANDLE.findAll(text).map { match ->
            val value = match.value.removePrefix("@")
            MentionedIdentifier(match.value, value.lowercase(), IdentifierKind.Username)
        }
        val usernameMentions = USERNAME_CUE.findAll(text).map { match ->
            val value = match.groupValues[1].removePrefix("@")
            MentionedIdentifier(match.groupValues[1], value.lowercase(), IdentifierKind.Username)
        }
        val namedValues = buildList {
            addAll(NAME_CUE.findAll(text).map { it.groupValues[1] })
            if (IDENTITY_ATTRIBUTION.containsMatchIn(text)) {
                addAll(PROPER_NAME.findAll(text).map { it.value })
            }
        }
        val namedMentions = namedValues.asSequence()
            .filterNot { it.trim().lowercase(Locale.US) in NAME_STOP_WORDS }
            .map { value -> MentionedIdentifier(value, value.lowercase(), IdentifierKind.Name) }

        return (emailMentions + urlMentions + phoneMentions + handleMentions + usernameMentions + namedMentions)
            .filter { it.normalized.isNotBlank() }
            .toSet()
    }

    /**
     * Qualifiers must modify the same attribution clause. A qualifier in a
     * later sentence or semicolon-separated clause cannot launder an earlier
     * absolute ownership/identity assertion into an unresolved hypothesis.
     * The conservative rule also requires the qualifier to precede the
     * attribution phrase in its clause (for example, "may belong to").
     */
    private fun hasUnqualifiedIdentityAttribution(text: String): Boolean =
        text.split(Regex("[.!?;\\n]+"))
            .any { clause ->
                IDENTITY_ATTRIBUTION.findAll(clause).any { match ->
                    val prefix = clause.substring(0, match.range.first)
                    !ATTRIBUTION_QUALIFIER.containsMatchIn(prefix)
                }
            }

    private fun identifierKey(identifier: MentionedIdentifier): String =
        identifier.kind.name + ":" + identifier.normalized

    private fun containsToken(text: String, token: String): Boolean {
        if (token.isBlank()) return false
        return Regex(
            "(?i)(?<![A-Za-z0-9])" + Regex.escape(token) + "(?![A-Za-z0-9])"
        ).containsMatchIn(text)
    }

    private fun normalizePhone(value: String): String = value.filter(Char::isDigit)

    private fun normalizeUrl(value: String): String {
        val lower = value.trim().lowercase(Locale.US)
        return lower
            .replace(Regex("^https://([^/?#:]+):443(?=[/?#]|$)"), "https://$1")
            .replace(Regex("^http://([^/?#:]+):80(?=[/?#]|$)"), "http://$1")
    }
}
