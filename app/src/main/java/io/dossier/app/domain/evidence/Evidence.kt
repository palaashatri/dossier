package io.dossier.app.domain.evidence

import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.FindingType
import io.dossier.app.domain.model.RiskLevel
import kotlinx.serialization.Serializable

/**
 * The universal language of Dossier (see ROADMAP: "Evidence is the universal
 * language"). A scanner never emits a Finding or a conclusion directly; it emits
 * [Evidence]. Findings, the entity graph, risk, and remediation are all derived
 * from Evidence downstream.
 *
 * This type is introduced in parallel with the legacy [Finding] type. The
 * [toFinding] / [Finding.toEvidence] adapters keep both representations
 * interchangeable so existing scanners and consumers keep working while new code
 * can target Evidence directly.
 *
 * @param id Stable identity for de-duplication and graph correlation.
 * @param kind What kind of observation this is (matches [FindingType]).
 * @param value The observed value (email, phone, username, URL, snippet...).
 * @param sourceUrl Where the evidence was observed, if any.
 * @param snippet Human-readable supporting text for explainability.
 * @param confidence 0..1 model/extraction confidence in the observation itself.
 * @param risk Inherent exposure risk of this evidence if it belongs to the subject.
 * @param signals Named reasons backing the observation (explainability per ROADMAP Principle 3).
 */
@Serializable
data class Evidence(
    val id: String,
    val kind: EvidenceKind,
    val value: String,
    val sourceUrl: String? = null,
    val snippet: String? = null,
    val confidence: Float = 0.5f,
    val risk: RiskLevel = RiskLevel.Low,
    val signals: List<String> = emptyList()
)

/**
 * Discriminates evidence the same way [FindingType] does, so the two can be
 * mapped losslessly. Kept as a separate enum to avoid coupling Evidence to the
 * UI-facing Finding contract.
 */
@Serializable
enum class EvidenceKind {
    Email,
    Phone,
    Address,
    Location,
    Username,
    Profile,
    Organization,
    UsernameReuse,
    PlausibleProfileMatch,
    PublicSearchEvidence,
    PublicImageEvidence,
    ImageConsistency,
    SensitiveSnippet
}

/**
 * A scanner's output: a batch of evidence plus optional raw relationships it was
 * able to assert directly (e.g. "this username appears on this profile").
 */
@Serializable
data class EvidenceCollection(
    val evidence: List<Evidence> = emptyList(),
    val relationships: List<EvidenceRelationship> = emptyList()
)

/**
 * A relationship asserted directly by a scanner between two observed values,
 * before the correlation engine generalizes it. Used to seed the identity graph.
 */
@Serializable
data class EvidenceRelationship(
    val fromValue: String,
    val toValue: String,
    val relation: String,
    val evidence: String? = null
)

/** Adapter: Evidence -> legacy Finding (lossless on the shared fields). */
fun Evidence.toFinding(): Finding = Finding(
    type = when (kind) {
        EvidenceKind.Email -> FindingType.Email
        EvidenceKind.Phone -> FindingType.Phone
        EvidenceKind.Address -> FindingType.Address
        EvidenceKind.Location -> FindingType.Location
        EvidenceKind.Username -> FindingType.Username
        EvidenceKind.Profile -> FindingType.Profile
        EvidenceKind.Organization -> FindingType.Organization
        EvidenceKind.UsernameReuse -> FindingType.UsernameReuse
        EvidenceKind.PlausibleProfileMatch -> FindingType.PlausibleProfileMatch
        EvidenceKind.PublicSearchEvidence -> FindingType.PublicSearchEvidence
        EvidenceKind.PublicImageEvidence -> FindingType.PublicImageEvidence
        EvidenceKind.ImageConsistency -> FindingType.ImageConsistency
        EvidenceKind.SensitiveSnippet -> FindingType.SensitiveSnippet
    },
    value = value,
    sourceUrl = sourceUrl,
    evidenceSnippet = snippet,
    confidence = confidence,
    risk = risk,
    remediation = signals.joinToString("; ")
)

/** Adapter: legacy Finding -> Evidence (lossless on the shared fields). */
fun Finding.toEvidence(): Evidence = Evidence(
    id = "ev:${type.name}:${value}:${sourceUrl ?: ""}",
    kind = when (type) {
        FindingType.Email -> EvidenceKind.Email
        FindingType.Phone -> EvidenceKind.Phone
        FindingType.Address -> EvidenceKind.Address
        FindingType.Location -> EvidenceKind.Location
        FindingType.Username -> EvidenceKind.Username
        FindingType.Profile -> EvidenceKind.Profile
        FindingType.Organization -> EvidenceKind.Organization
        FindingType.UsernameReuse -> EvidenceKind.UsernameReuse
        FindingType.PlausibleProfileMatch -> EvidenceKind.PlausibleProfileMatch
        FindingType.PublicSearchEvidence -> EvidenceKind.PublicSearchEvidence
        FindingType.PublicImageEvidence -> EvidenceKind.PublicImageEvidence
        FindingType.ImageConsistency -> EvidenceKind.ImageConsistency
        FindingType.SensitiveSnippet -> EvidenceKind.SensitiveSnippet
    },
    value = value,
    sourceUrl = sourceUrl,
    snippet = evidenceSnippet,
    confidence = confidence,
    risk = risk,
    signals = if (remediation.isBlank()) emptyList() else listOf(remediation)
)
