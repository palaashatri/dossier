package io.dossier.app.domain.discovery

import kotlinx.serialization.Serializable

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
    val alreadyVisited: Boolean = false
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

    private val commonHandles = setOf(
        "admin", "administrator", "user", "username", "test", "demo",
        "support", "help", "contact", "info", "official", "team", "staff",
        "news", "media", "root", "guest", "unknown", "account", "profile"
    )

    fun decide(request: PivotAdmissionRequest): PivotAdmissionDecision {
        if (request.alreadyVisited) {
            return PivotAdmissionDecision.Reject("Signal was already visited in this scan")
        }
        if (request.depth !in 1..DEFAULT_MAX_DEPTH) {
            return PivotAdmissionDecision.Reject("Pivot depth exceeds the bounded recursion limit")
        }
        val value = request.normalizedValue.trim().lowercase()
        if (value.length < 2) {
            return PivotAdmissionDecision.Reject("Signal is too short to be a useful identity pivot")
        }
        if (request.signalType == PivotSignalType.CommonUsername || value in commonHandles) {
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
                request.confidence >= 0.60f && value !in commonHandles
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
