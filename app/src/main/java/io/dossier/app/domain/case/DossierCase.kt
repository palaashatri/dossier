package io.dossier.app.domain.case

import io.dossier.app.domain.evidence.AttackPathFinder.AttackPath
import io.dossier.app.domain.evidence.ExposureEngine.ExposureResult
import io.dossier.app.domain.evidence.RelationshipConfidence
import io.dossier.app.domain.model.*
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * A complete, persistable snapshot of one scan (ROADMAP M13/M14).
 *
 * Everything the report renders is captured here so two cases can be compared
 * (M14) and changes tracked over time (M13). Cases are saved only on explicit
 * user action — Dossier stays in-memory and temporary by default (Principle 4);
 * persisting is opt-in and local (no cloud).
 */
@Serializable
data class DossierCase(
    val caseId: String = UUID.randomUUID().toString(),
    val createdAt: String,                 // ISO-ish timestamp from the device
    val subjectName: String,
    val input: IdentityInput,
    val findings: List<Finding> = emptyList(),
    val profileResults: List<ProfileScanResult> = emptyList(),
    val faceMatches: List<FaceConsistencyMatch> = emptyList(),
    val entityGraph: EntityGraph = EntityGraph(),
    val breachDigests: List<BreachDigest> = emptyList(),
    val riskLevel: RiskLevel = RiskLevel.Low,
    val exposure: ExposureResult? = null,
    val attackPaths: List<AttackPath> = emptyList(),
    val relationshipConfidence: Map<String, RelationshipConfidence> = emptyMap(),
    val aiSummary: String? = null
) {

    /** Stable display label, e.g. "janedoe · 2026-07-20 14:03". */
    val label: String
        get() = buildString {
            append(subjectName.ifBlank { "UNKNOWN SUBJECT" })
            if (createdAt.isNotBlank()) append(" · $createdAt")
        }
}
