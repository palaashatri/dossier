package io.dossier.app.domain.evidence

import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.FindingType
import io.dossier.app.domain.model.RiskLevel
import kotlinx.serialization.Serializable

/**
 * Stable evidence state. This describes the observation itself, not whether an
 * inferred identity relationship is ultimately accepted.
 */
@Serializable
enum class EvidenceState {
    Observed,
    Verified,
    Probable,
    Candidate,
    Conflicting,
    Rejected,
    Unavailable
}

/** Source-quality class used by deterministic analysis and UI explanation. */
@Serializable
enum class EvidenceReliability {
    AuthoritativeApi,
    DirectPublicProfile,
    DirectPersonalWebsite,
    ArchiveSnapshot,
    SearchEngineCandidate,
    ThirdPartyAggregation,
    LocalDerived,
    UserSupplied,
    Unknown
}

/**
 * The universal evidence record used across Dossier.
 *
 * New provenance fields are optional/defaulted so existing scanners remain
 * source-compatible while migrations populate richer metadata incrementally.
 * Missing metadata is represented as missing; Dossier does not fabricate a
 * retrieval time, provider, parser version, or hash merely to fill a schema.
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
    val signals: List<String> = emptyList(),
    val providerId: String? = null,
    val retrievedAtEpochMillis: Long? = null,
    val observedAtEpochMillis: Long? = null,
    val state: EvidenceState = EvidenceState.Observed,
    val reliability: EvidenceReliability = EvidenceReliability.Unknown,
    val contentHashSha256: String? = null,
    val parserVersion: String? = null,
    val historical: Boolean = false
)

/** Product-contract name for the stable evidence representation. */
typealias EvidenceRecord = Evidence

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
 * A scanner's output: a batch of evidence plus relationships it can directly
 * assert from the same public observation.
 */
@Serializable
data class EvidenceCollection(
    val evidence: List<Evidence> = emptyList(),
    val relationships: List<EvidenceRelationship> = emptyList()
)

/**
 * Relationship asserted directly by a scanner before entity resolution
 * generalizes it. Evidence text is retained for backward compatibility; newer
 * producers should additionally keep the supporting Evidence IDs in their
 * higher-level relationship model as that migration lands.
 */
@Serializable
data class EvidenceRelationship(
    val fromValue: String,
    val toValue: String,
    val relation: String,
    val evidence: String? = null
)

/** Adapter: Evidence -> legacy Finding (lossless on shared legacy fields). */
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

/**
 * Adapter for legacy findings. Metadata that the legacy Finding contract cannot
 * prove remains explicitly Unknown/null rather than being invented.
 */
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
    signals = if (remediation.isBlank()) emptyList() else listOf(remediation),
    state = when {
        confidence >= 0.9f -> EvidenceState.Verified
        confidence >= 0.7f -> EvidenceState.Probable
        else -> EvidenceState.Candidate
    },
    reliability = when (type) {
        FindingType.PublicSearchEvidence,
        FindingType.PublicImageEvidence -> EvidenceReliability.SearchEngineCandidate
        FindingType.ImageConsistency -> EvidenceReliability.LocalDerived
        else -> EvidenceReliability.Unknown
    }
)
