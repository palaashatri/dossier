package io.dossier.app.domain.case

import io.dossier.app.domain.ai.AiAnalysisSnapshot
import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.ExposureEngine
import io.dossier.app.domain.evidence.EvidenceIdPolicy
import io.dossier.app.domain.model.EntityGraph
import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.ProfileScanResult
import io.dossier.app.domain.model.RiskLevel
import io.dossier.app.domain.risk.RiskScorer
import java.util.Locale

/** Why a raw evidence row is shown only in the explicit corrections/audit view. */
enum class CorrectionAuditReason(val label: String) {
    IgnoredEvidence("Ignored by user"),
    RejectedEvidence("Rejected by user"),
    RejectedEntityEvidence("Supports an entity rejected by user")
}

/**
 * Corrected presentation view of a saved case.
 *
 * [DossierCase] remains the immutable, encrypted audit record. This projection
 * derives the fields that should be shown or scored after explicit user
 * corrections, while retaining the raw evidence and graph in [rawCase]. The
 * AI snapshot is the canonical correction engine, so saved-case UI and AI use
 * identical evidence/entity filtering and contradiction states.
 */
data class EffectiveCaseProjection(
    val rawCase: DossierCase,
    val evidence: List<Evidence>,
    val findings: List<Finding>,
    val profileResults: List<ProfileScanResult>,
    val entityGraph: EntityGraph,
    val riskLevel: RiskLevel,
    val exposure: ExposureEngine.ExposureResult?,
    val excludedEvidenceIds: List<String>,
    val confirmedEntityIds: List<String>,
    val rejectedEntityIds: List<String>,
    /** Raw IDs retained for the Corrections / Rejected timeline filter. */
    val rawAuditEvidenceById: Map<String, CorrectionAuditReason>
) {
    /**
     * Returns the fields intended for display/export while leaving raw
     * evidence, corrections, and the encrypted source case untouched.
     */
    fun presentationCase(): DossierCase = rawCase.copy(
        findings = findings,
        profileResults = profileResults,
        entityGraph = entityGraph,
        riskLevel = riskLevel,
        exposure = exposure
    )

    companion object {
        fun from(case: DossierCase): EffectiveCaseProjection {
            val snapshot = AiAnalysisSnapshot.fromCase(case)
            val rawAuditEvidenceById = correctionAudit(case, snapshot)
            val effectiveRisk = RiskScorer().score(snapshot.findings)
            val effectiveExposure = ExposureEngine().score(
                findings = snapshot.findings,
                breaches = snapshot.breachDigests
            )
            return EffectiveCaseProjection(
                rawCase = case,
                evidence = snapshot.evidence,
                findings = snapshot.findings,
                profileResults = snapshot.profileResults,
                entityGraph = snapshot.graph,
                riskLevel = effectiveRisk,
                exposure = effectiveExposure,
                excludedEvidenceIds = snapshot.excludedEvidenceIds,
                confirmedEntityIds = snapshot.confirmedEntityIds,
                rejectedEntityIds = snapshot.rejectedEntityIds,
                rawAuditEvidenceById = rawAuditEvidenceById
            )
        }

        /**
         * Returns correction provenance keyed by the immutable raw evidence ID.
         * Matching uses the same corrected AI snapshot as the presentation view,
         * so entity-level ThisIsNotMe corrections (including conservative
         * source/type matches) cannot be missed by the timeline.
         */
        fun correctionAudit(case: DossierCase): Map<String, CorrectionAuditReason> =
            correctionAudit(case, AiAnalysisSnapshot.fromCase(case))

        private fun correctionAudit(
            case: DossierCase,
            snapshot: AiAnalysisSnapshot
        ): Map<String, CorrectionAuditReason> {
            val orderedCorrections = case.userCorrections
                .sortedWith(compareBy<UserCorrection> { it.createdAtUtc }.thenBy { it.correctionId })
            return case.evidenceRecords.mapNotNull { raw ->
                val direct = orderedCorrections.lastOrNull { correction ->
                    correction.evidenceId?.let { sameEvidenceId(it, raw.id) } == true
                }
                val snapshotRejected = snapshot.evidence.any { effective ->
                    evidenceMatchKey(effective) == evidenceMatchKey(raw) &&
                        effective.state == io.dossier.app.domain.evidence.EvidenceState.Rejected
                }
                val reason = when {
                    direct?.decision == UserCorrectionDecision.IgnoreEvidence ->
                        CorrectionAuditReason.IgnoredEvidence
                    direct?.decision == UserCorrectionDecision.ThisIsNotMe ->
                        CorrectionAuditReason.RejectedEvidence
                    snapshotRejected -> CorrectionAuditReason.RejectedEntityEvidence
                    else -> null
                } ?: return@mapNotNull null
                raw.id to reason
            }.toMap()
        }

        private data class EvidenceMatchKey(
            val kind: EvidenceKind,
            val value: String,
            val sourceUrl: String
        )

        private fun evidenceMatchKey(evidence: Evidence): EvidenceMatchKey = EvidenceMatchKey(
            kind = evidence.kind,
            value = comparable(evidence.value),
            sourceUrl = comparable(evidence.sourceUrl.orEmpty())
        )

        private fun comparable(value: String): String = value
            .trim()
            .replace(Regex("\\s+"), " ")
            .lowercase(Locale.US)

        private fun sameEvidenceId(left: String, right: String): Boolean =
            left == right || EvidenceIdPolicy.migrate(left) == EvidenceIdPolicy.migrate(right)
    }
}
