package io.dossier.app.domain.case

import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.model.DossierEdge
import io.dossier.app.domain.model.DossierEntity
import io.dossier.app.domain.model.EntityGraph
import io.dossier.app.domain.model.GraphNodeState

/**
 * Deterministically applies explicit user corrections to the analysis view of a
 * saved case while preserving the underlying raw evidence in storage.
 */
object UserCorrectionEngine {
    data class EffectiveCaseView(
        val evidence: List<Evidence>,
        val graph: EntityGraph,
        val excludedEvidenceIds: Set<String>,
        val confirmedEntityIds: Set<String>,
        val rejectedEntityIds: Set<String>
    )

    fun apply(
        evidence: List<Evidence>,
        graph: EntityGraph,
        corrections: List<UserCorrection>
    ): EffectiveCaseView {
        val latestByEvidence = corrections
            .filter { it.evidenceId != null }
            .associateBy { it.evidenceId!! }
        val latestByEntity = corrections
            .filter { it.entityId != null }
            .associateBy { it.entityId!! }

        val excludedEvidence = latestByEvidence
            .filterValues { it.decision == UserCorrectionDecision.IgnoreEvidence || it.decision == UserCorrectionDecision.ThisIsNotMe }
            .keys
        val confirmedEntities = latestByEntity
            .filterValues { it.decision == UserCorrectionDecision.ThisIsMe }
            .keys
        val rejectedEntities = latestByEntity
            .filterValues { it.decision == UserCorrectionDecision.ThisIsNotMe }
            .keys

        val effectiveEvidence = evidence.mapNotNull { item ->
            val correction = latestByEvidence[item.id]
            when (correction?.decision) {
                UserCorrectionDecision.IgnoreEvidence -> null
                UserCorrectionDecision.ThisIsNotMe -> item.copy(state = EvidenceState.Rejected)
                UserCorrectionDecision.ThisIsMe -> item.copy(state = EvidenceState.Verified)
                UserCorrectionDecision.Unsure,
                null -> item
            }
        }

        val updatedEntities = graph.entities.map { entity ->
            val correction = latestByEntity[entity.id]
            when (correction?.decision) {
                UserCorrectionDecision.ThisIsMe -> entity.copy(state = GraphNodeState.Confirmed)
                UserCorrectionDecision.ThisIsNotMe -> entity.copy(state = GraphNodeState.Conflicting)
                UserCorrectionDecision.Unsure -> entity.copy(state = GraphNodeState.Unresolved)
                UserCorrectionDecision.IgnoreEvidence,
                null -> entity
            }
        }

        val updatedEdges = graph.edges.map { edge ->
            applyEdgeCorrections(edge, confirmedEntities, rejectedEntities, excludedEvidence)
        }

        return EffectiveCaseView(
            evidence = effectiveEvidence,
            graph = graph.copy(entities = updatedEntities, edges = updatedEdges),
            excludedEvidenceIds = excludedEvidence,
            confirmedEntityIds = confirmedEntities,
            rejectedEntityIds = rejectedEntities
        )
    }

    private fun applyEdgeCorrections(
        edge: DossierEdge,
        confirmedEntityIds: Set<String>,
        rejectedEntityIds: Set<String>,
        excludedEvidenceIds: Set<String>
    ): DossierEdge {
        val retainedEvidenceIds = edge.evidenceIds.filterNot(excludedEvidenceIds::contains)
        val explicitlyRejected = edge.fromId in rejectedEntityIds || edge.toId in rejectedEntityIds
        val explicitlyConfirmed = edge.fromId in confirmedEntityIds || edge.toId in confirmedEntityIds

        return when {
            explicitlyRejected -> edge.copy(
                confidence = 0f,
                evidenceIds = retainedEvidenceIds,
                contradictingEvidenceIds = (
                    edge.contradictingEvidenceIds + retainedEvidenceIds
                ).distinct()
            )
            explicitlyConfirmed -> edge.copy(
                confidence = maxOf(edge.confidence ?: 0f, 0.95f),
                evidenceIds = retainedEvidenceIds
            )
            else -> edge.copy(evidenceIds = retainedEvidenceIds)
        }
    }
}
