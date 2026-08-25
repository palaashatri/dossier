package io.dossier.app.domain.case

import io.dossier.app.domain.discovery.ScanMode
import io.dossier.app.domain.evidence.AttackPathFinder.AttackPath
import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceRelationship
import io.dossier.app.domain.evidence.ExposureEngine.ExposureResult
import io.dossier.app.domain.evidence.RelationshipConfidence
import io.dossier.app.domain.model.*
import io.dossier.app.domain.place.MediaIntelligenceSnapshot
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class AuthorizedScope {
    SelfAudit,
    ExplicitConsent,
    AuthorizedResearch,
    AuthorizedAssessment
}

@Serializable
enum class UserCorrectionDecision {
    ThisIsMe,
    ThisIsNotMe,
    Unsure,
    IgnoreEvidence
}

@Serializable
data class UserCorrection(
    val correctionId: String = UUID.randomUUID().toString(),
    val evidenceId: String? = null,
    val entityId: String? = null,
    val decision: UserCorrectionDecision,
    val note: String? = null,
    val createdAtUtc: String
)

@Serializable
enum class RemediationStatus {
    NotStarted,
    InProgress,
    Submitted,
    AwaitingResponse,
    Completed,
    Rejected,
    NeedsManualAction
}

@Serializable
data class RemediationRecord(
    val remediationId: String = UUID.randomUUID().toString(),
    val findingKey: String,
    val providerId: String? = null,
    val sourceUrl: String? = null,
    val action: String,
    val status: RemediationStatus = RemediationStatus.NotStarted,
    val createdAtUtc: String,
    val updatedAtUtc: String,
    val verificationNote: String? = null,
    val verifiedByScanId: String? = null,
    val evidenceId: String? = null
)

@Serializable
data class CaseScanHistoryEntry(
    val scanId: String,
    val startedAtUtc: String,
    val completedAtUtc: String? = null,
    val mode: ScanMode = ScanMode.Standard,
    val directProfileProviderCount: Int = 0,
    val profileResultCount: Int = 0,
    val findingCount: Int = 0,
    val breachRecordCount: Int = 0,
    val graphEntityCount: Int = 0,
    val graphRelationshipCount: Int = 0,
    val cancelled: Boolean = false,
    val failed: Boolean = false,
    val failureCode: String? = null
)
@Serializable
data class CaseExportRecord(
    val exportId: String = UUID.randomUUID().toString(),
    val createdAtUtc: String,
    val format: String,
    val redacted: Boolean,
    val manifestSha256: String? = null
)

/** Complete, persistable snapshot of an explicitly saved assessment. */
@Serializable
data class DossierCase(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val caseId: String = UUID.randomUUID().toString(),
    val createdAt: String,
    val subjectName: String,
    val input: IdentityInput,
    // Keep SelfAudit as the decoding default for legacy cases. New investigations
    // may explicitly use AuthorizedAssessment without rewriting historical meaning.
    val authorizedScope: AuthorizedScope = AuthorizedScope.SelfAudit,
    val findings: List<Finding> = emptyList(),
    val evidenceRecords: List<Evidence> = emptyList(),
    /** Canonical scanner/plugin assertions retained independently of graph edges. */
    val evidenceRelationships: List<EvidenceRelationship> = emptyList(),
    val profileResults: List<ProfileScanResult> = emptyList(),
    val faceMatches: List<FaceConsistencyMatch> = emptyList(),
    val entityGraph: EntityGraph = EntityGraph(),
    val breachDigests: List<BreachDigest> = emptyList(),
    val riskLevel: RiskLevel = RiskLevel.Low,
    val exposure: ExposureResult? = null,
    val attackPaths: List<AttackPath> = emptyList(),
    val relationshipConfidence: Map<String, RelationshipConfidence> = emptyMap(),
    val aiSummary: String? = null,
    /** Explicit state prevents a prior AI result being mistaken for a corrected view. */
    val aiSummaryNeedsRefresh: Boolean = false,
    val mediaIntelligence: MediaIntelligenceSnapshot = MediaIntelligenceSnapshot(),
    val scanHistory: List<CaseScanHistoryEntry> = emptyList(),
    val userCorrections: List<UserCorrection> = emptyList(),
    val remediationRecords: List<RemediationRecord> = emptyList(),
    val exports: List<CaseExportRecord> = emptyList()
) {
    val label: String
        get() = buildString {
            append(subjectName.ifBlank { "UNKNOWN SUBJECT" })
            if (createdAt.isNotBlank()) append(" · $createdAt")
        }

    fun findingKey(finding: Finding): String =
        "${finding.type.name}|${finding.value}|${finding.sourceUrl.orEmpty()}"

    /**
     * Returns the persisted scanner/plugin relationship assertions used as the
     * canonical read source for graph diagnostics.
     *
     * This is intentionally a read-only view: it does not fall back to graph
     * edges, normalize or merge assertions, or mutate the case. Persistence and
     * runtime producers apply the relationship policy before saving/building a
     * case, while consumers enforce their own diagnostic bounds. Legacy cases
     * with no persisted assertions therefore remain empty and unresolved.
     */
    fun canonicalEvidenceRelationships(): List<EvidenceRelationship> = evidenceRelationships

    companion object {
        /** v8 persists canonical evidence relationships alongside evidence records. */
        const val CURRENT_SCHEMA_VERSION = 8
    }
}
