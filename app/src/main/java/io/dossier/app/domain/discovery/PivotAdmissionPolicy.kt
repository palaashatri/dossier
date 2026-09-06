package io.dossier.app.domain.discovery

import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * Signal types that may enter the bounded recursive discovery path.
 * This policy is intentionally conservative because a false pivot can multiply
 * into many false profile candidates on the next hop.
 */
@Serializable
enum class PivotSignalType {
    ExplicitProfileLink,
    ExplicitPlatformMention,
    PersonalWebsiteCrossLink,
    SuppliedIdentifier,
    CommonUsername,
    NameOnly,
    LocationOnly,
    OccupationOnly,
    FaceSimilarityOnly
}

enum class PivotConfidenceBand {
    Strong,
    Medium,
    Weak
}

data class PivotAdmissionRequest(
    val signalType: PivotSignalType,
    val normalizedValue: String,
    val confidence: Float,
    val depth: Int,
    val corroboratingEvidenceCount: Int = 1,
    val alreadyVisited: Boolean = false,
    /** Caller-owned frontier bound; defaults preserve the conservative two-hop policy. */
    val maxDepth: Int = PivotAdmissionPolicy.DEFAULT_MAX_DEPTH
)

sealed interface PivotAdmissionDecision {
    data class Admit(
        val band: PivotConfidenceBand,
        val explanation: String
    ) : PivotAdmissionDecision

    data class Reject(val explanation: String) : PivotAdmissionDecision
}

/**
 * Deterministic admission gate used by public-profile pivot extraction.
 * Numerical cutoffs are conservative engineering defaults, not scientific
 * identity probabilities; entity-resolution calibration remains a later gate.
 */
object PivotAdmissionPolicy {
    const val DEFAULT_MAX_DEPTH = 2
    /** Hard upper bound; normal scans remain at [DEFAULT_MAX_DEPTH]. */
    const val MAX_ALLOWED_DEPTH = 4

    private val commonHandles = setOf(
        "admin", "administrator", "user", "username", "test", "demo",
        "support", "help", "contact", "info", "official", "team", "staff",
        "news", "media", "root", "guest", "unknown", "account", "profile",
        "undefined", "null", "none", "anonymous", "n/a", "na", "default"
    )

    /**
     * Normalizes a handle for common-value checks. The request-level policy
     * may admit a common handle with at least two corroborating evidence
     * records and confidence >= 0.75; [TypedSeedSafety] intentionally remains
     * stricter because [TypedSeed] carries no confidence field.
     */
    internal fun isCommonHandle(value: String): Boolean =
        value.trim().removePrefix("@").lowercase(Locale.ROOT) in commonHandles

    fun decide(request: PivotAdmissionRequest): PivotAdmissionDecision {
        if (request.alreadyVisited) {
            return PivotAdmissionDecision.Reject("Signal was already visited in this scan")
        }
        if (request.maxDepth !in 1..MAX_ALLOWED_DEPTH) {
            return PivotAdmissionDecision.Reject("Pivot depth bound is outside the allowed recursion limit")
        }
        if (request.depth !in 1..request.maxDepth) {
            return PivotAdmissionDecision.Reject(
                "Pivot depth exceeds the configured recursion limit (${request.maxDepth})"
            )
        }
        val value = request.normalizedValue.trim().lowercase(Locale.ROOT)
        if (value.length < 2) {
            return PivotAdmissionDecision.Reject("Signal is too short to be a useful identity pivot")
        }
        if (request.signalType == PivotSignalType.CommonUsername || isCommonHandle(value)) {
            return if (request.corroboratingEvidenceCount >= 2 && request.confidence >= 0.75f) {
                PivotAdmissionDecision.Admit(
                    PivotConfidenceBand.Medium,
                    "Common handle admitted only because independent evidence corroborates it"
                )
            } else {
                PivotAdmissionDecision.Reject("Common handle lacks independent corroboration")
            }
        }

        return when (request.signalType) {
            PivotSignalType.ExplicitProfileLink,
            PivotSignalType.SuppliedIdentifier -> if (request.confidence >= 0.65f) {
                PivotAdmissionDecision.Admit(
                    PivotConfidenceBand.Strong,
                    "Explicit public cross-link or supplied identifier"
                )
            } else {
                PivotAdmissionDecision.Reject("Explicit signal confidence is below the conservative admission floor")
            }

            PivotSignalType.PersonalWebsiteCrossLink -> if (
                request.confidence >= 0.65f || request.corroboratingEvidenceCount >= 2
            ) {
                PivotAdmissionDecision.Admit(
                    PivotConfidenceBand.Strong,
                    "Public personal-site cross-link"
                )
            } else {
                PivotAdmissionDecision.Reject("Personal-site pivot lacks sufficient support")
            }

            PivotSignalType.ExplicitPlatformMention -> if (
                request.confidence >= 0.60f && !isCommonHandle(value)
            ) {
                PivotAdmissionDecision.Admit(
                    PivotConfidenceBand.Medium,
                    "Explicit platform/handle mention on verified public evidence"
                )
            } else {
                PivotAdmissionDecision.Reject("Platform mention is too weak or too common")
            }

            PivotSignalType.NameOnly,
            PivotSignalType.LocationOnly,
            PivotSignalType.OccupationOnly,
            PivotSignalType.FaceSimilarityOnly -> PivotAdmissionDecision.Reject(
                "Weak identity signal cannot recursively expand without corroboration"
            )

            PivotSignalType.CommonUsername -> error("Handled above")
        }
    }
}
