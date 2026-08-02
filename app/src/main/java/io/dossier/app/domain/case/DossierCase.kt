package io.dossier.app.domain.case

import io.dossier.app.domain.evidence.AttackPathFinder.AttackPath
import io.dossier.app.domain.evidence.ExposureEngine.ExposureResult
import io.dossier.app.domain.evidence.RelationshipConfidence
import io.dossier.app.domain.model.*
import kotlinx.serialization.Serializable
import java.util.UUID

/** Complete, persistable snapshot of one explicitly saved scan. */
@Serializable
data class DossierCase(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val caseId: String = UUID.randomUUID().toString(),
    val createdAt: String,
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
    val label: String
        get() = buildString {
            append(subjectName.ifBlank { "UNKNOWN SUBJECT" })
            if (createdAt.isNotBlank()) append(" · $createdAt")
        }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 2
    }
}
