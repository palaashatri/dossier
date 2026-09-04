package io.dossier.app.domain.evidence

import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.FindingType
import io.dossier.app.domain.model.RiskLevel
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.util.Locale

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
    val historical: Boolean = false,
    val attributeKind: HistoricalAttributeKind? = null,
    val discoveryPath: List<String> = emptyList()
) {
    init {
        require(discoveryPath.size <= MAX_DISCOVERY_PATH_STEPS) {
            "Evidence may retain at most $MAX_DISCOVERY_PATH_STEPS discovery steps."
        }
    }

    companion object {
        const val MAX_DISCOVERY_PATH_STEPS = 64
    }
}

/**
 * Explicit semantic attribute kind for historical profile/snapshot metadata.
 * Kept optional on [Evidence] so non-attribute evidence is unaffected.
 */
@Serializable
enum class HistoricalAttributeKind {
    DisplayName,
    Bio,
    Username,
    AvatarUrl,
    ExternalLink,
    Organization,
    Location
}

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
    val evidence: String? = null,
    /**
     * Stable evidence IDs that independently support this relationship.
     *
     * Older producers only supplied the free-form [evidence] description, so
     * this remains optional for backwards compatibility. The graph builder
     * resolves exact endpoint/source matches as a safe migration aid.
     */
    val evidenceIds: List<String> = emptyList()
)

/**
 * Canonicalizes persisted/runtime relationship assertions without resolving
 * identity. Relationships with the same normalized endpoints and relation
 * are one assertion; their independent evidence IDs are unioned in input
 * order, bounded for safe persistence.
 */
object EvidenceRelationshipPolicy {
    const val MAX_RELATIONSHIPS = 10_000
    const val MAX_EVIDENCE_IDS_PER_RELATIONSHIP = 256

    fun normalize(relationships: List<EvidenceRelationship>): List<EvidenceRelationship> {
        if (relationships.isEmpty()) return emptyList()

        val merged = LinkedHashMap<String, EvidenceRelationship>()
        relationships.take(MAX_RELATIONSHIPS).forEach { relationship ->
            val key = listOf(
                relationship.fromValue.trim().lowercase(Locale.ROOT),
                relationship.toValue.trim().lowercase(Locale.ROOT),
                relationship.relation.trim().uppercase(Locale.ROOT)
            ).joinToString("\u001f")
            val evidenceIds = relationship.evidenceIds
                .map { it.trim() }
                .filter(String::isNotBlank)
                .map(EvidenceIdPolicy::migrate)
                .distinct()
                .take(MAX_EVIDENCE_IDS_PER_RELATIONSHIP)
            val previous = merged[key]
            if (previous == null) {
                merged[key] = relationship.copy(evidenceIds = evidenceIds)
            } else {
                val description = previous.evidence?.takeIf(String::isNotBlank)
                    ?: relationship.evidence?.takeIf(String::isNotBlank)
                merged[key] = previous.copy(
                    evidence = description,
                    evidenceIds = (previous.evidenceIds + evidenceIds)
                        .map { it.trim() }
                        .filter(String::isNotBlank)
                        .map(EvidenceIdPolicy::migrate)
                        .distinct()
                        .take(MAX_EVIDENCE_IDS_PER_RELATIONSHIP)
                )
            }
        }
        return merged.values.toList()
    }
}

/**
 * Resolves relationship provenance without inventing evidence.
 *
 * A relationship may arrive from a legacy producer with only endpoint values
 * and a human-readable description. When exactly one evidence record matches
 * an endpoint, the record's value/source URL, or an evidence description that
 * is itself an exact source/value, this attaches that existing ID. Ambiguous
 * exact matches remain unresolved. No fuzzy matching, provider inference, or
 * new evidence is created here.
 */
fun EvidenceCollection.withResolvedRelationshipEvidence(): EvidenceCollection {
    if (relationships.isEmpty() || evidence.isEmpty()) return this

    val resolvedRelationships = relationships.map { relationship ->
        val exactKeys = listOf(
            relationship.fromValue,
            relationship.toValue,
            relationship.evidence
        ).map(::provenanceKey).filter(String::isNotBlank).distinct()

        /*
         * Resolve each exact key independently. A URL/value can legitimately
         * occur on more than one evidence record (for example a profile record
         * and an extracted attribute sharing a source URL); attaching every
         * matching ID would silently turn an ambiguous legacy assertion into a
         * broad graph claim. Keep such a key unresolved and preserve only IDs
         * that the producer supplied explicitly. This is deliberately exact and
         * read-only: it never creates an Evidence record or guesses a provider.
         */
        val inferredIds = exactKeys.asSequence()
            .mapNotNull { exactKey ->
                val matchingIds = evidence.asSequence()
                    .filter { record ->
                        provenanceKey(record.id) == exactKey ||
                            provenanceKey(record.value) == exactKey ||
                            provenanceKey(record.sourceUrl) == exactKey
                    }
                    .map(Evidence::id)
                    .distinct()
                    .toList()
                matchingIds.singleOrNull()
            }
            .toList()

        relationship.copy(
            evidenceIds = (relationship.evidenceIds + inferredIds)
                .map(EvidenceIdPolicy::migrate)
                .distinct()
        )
    }
    return copy(relationships = resolvedRelationships)
}

private fun provenanceKey(value: String?): String = value
    ?.trim()
    ?.replace(Regex("\\s+"), " ")
    ?.lowercase(Locale.US)
    .orEmpty()

/**
 * Evidence-ID policy.
 *
 * Legacy `Finding.toEvidence()` IDs embedded raw finding values and source URLs.
 * That was useful while prototyping but is inappropriate for remote-AI citation,
 * logs, diagnostics, or other metadata surfaces. Current IDs hash the complete
 * legacy identifier so they remain deterministic while revealing no raw value.
 *
 * The transformation is deliberately defined from the old identifier string so
 * encrypted-case migrations can convert a persisted v3 correction/edge ID without
 * needing to reconstruct the original Finding object.
 */
object EvidenceIdPolicy {
    private const val CURRENT_PREFIX = "ev2:"
    private const val LEGACY_PREFIX = "ev:"

    fun legacyFindingId(finding: Finding): String =
        "$LEGACY_PREFIX${finding.type.name}:${finding.value}:${finding.sourceUrl ?: ""}"

    fun findingId(finding: Finding): String = fromLegacyId(legacyFindingId(finding))

    fun migrate(id: String): String = when {
        id.startsWith(CURRENT_PREFIX) -> id
        id.startsWith(LEGACY_PREFIX) -> fromLegacyId(id)
        else -> id
    }

    fun isCurrentFindingId(id: String): Boolean = id.startsWith(CURRENT_PREFIX)

    internal fun fromLegacyId(legacyId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(legacyId.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return "$CURRENT_PREFIX${digest.take(32)}"
    }
}

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
 * prove remains explicitly Unknown/null rather than being invented. In
 * particular, numeric confidence is never promoted into a verification state.
 */
fun Finding.toEvidence(): Evidence = Evidence(
    id = EvidenceIdPolicy.findingId(this),
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
    state = when (type) {
        FindingType.PlausibleProfileMatch,
        FindingType.PublicSearchEvidence,
        FindingType.PublicImageEvidence -> EvidenceState.Candidate
        else -> EvidenceState.Observed
    },
    reliability = when (type) {
        FindingType.PublicSearchEvidence,
        FindingType.PublicImageEvidence -> EvidenceReliability.SearchEngineCandidate
        FindingType.ImageConsistency -> EvidenceReliability.LocalDerived
        else -> EvidenceReliability.Unknown
    }
)
