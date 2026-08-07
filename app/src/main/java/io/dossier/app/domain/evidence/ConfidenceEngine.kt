package io.dossier.app.domain.evidence

import io.dossier.app.domain.model.EntityGraph
import io.dossier.app.domain.model.EntityType
import kotlinx.serialization.Serializable

/** Explainable score for one graph relationship. */
@Serializable
data class RelationshipConfidence(
    val score: Float,
    val reasons: List<String>
)

/**
 * Folds deterministic confidence contributors over existing graph relationships.
 * The engine never invents relationships; it only annotates evidence-backed
 * edges produced upstream.
 */
class ConfidenceEngine(
    private val contributors: List<ConfidenceContributor>
) {
    fun score(
        graph: EntityGraph,
        evidence: List<Evidence>
    ): Map<String, RelationshipConfidence> {
        val byValue = evidence.associateBy { it.value.lowercase() }
        val result = mutableMapOf<String, RelationshipConfidence>()

        for (edge in graph.edges) {
            val fromEntity = graph.entities.firstOrNull { it.id == edge.fromId } ?: continue
            val toEntity = graph.entities.firstOrNull { it.id == edge.toId } ?: continue

            val a = byValue[fromEntity.label.lowercase()]
                ?: Evidence(
                    id = fromEntity.id,
                    kind = fromEntity.type.toEvidenceKind(),
                    value = fromEntity.label,
                    confidence = fromEntity.confidence,
                    state = fromEntity.state.toEvidenceState(),
                    historical = fromEntity.historical
                )
            val b = byValue[toEntity.label.lowercase()]
                ?: Evidence(
                    id = toEntity.id,
                    kind = toEntity.type.toEvidenceKind(),
                    value = toEntity.label,
                    confidence = toEntity.confidence,
                    state = toEntity.state.toEvidenceState(),
                    historical = toEntity.historical
                )

            val (score, reasons) = contributors.fold(0f to emptyList<String>()) { acc, contributor ->
                val signals = contributor.contribute(a, b) ?: return@fold acc
                maxOf(acc.first, signals.score) to (acc.second + signals.reasons).distinct()
            }

            if (reasons.isNotEmpty()) {
                result[edgeKey(edge.fromId, edge.toId, edge.relation)] =
                    RelationshipConfidence(score = score, reasons = reasons)
            }
        }
        return result
    }

    companion object {
        fun edgeKey(fromId: String, toId: String, relation: String): String =
            "$fromId|$toId|$relation"
    }
}

private fun EntityType.toEvidenceKind(): EvidenceKind = when (this) {
    EntityType.Person,
    EntityType.Subject,
    EntityType.Account,
    EntityType.DisplayName -> EvidenceKind.Username

    EntityType.Username -> EvidenceKind.Username
    EntityType.Email -> EvidenceKind.Email
    EntityType.Phone -> EvidenceKind.Phone
    EntityType.Domain,
    EntityType.URL,
    EntityType.Website -> EvidenceKind.PublicSearchEvidence

    EntityType.Profile -> EvidenceKind.Profile
    EntityType.Organization -> EvidenceKind.Organization
    EntityType.Location -> EvidenceKind.Location
    EntityType.Occupation -> EvidenceKind.Organization
    EntityType.Image -> EvidenceKind.ImageConsistency
    EntityType.Document,
    EntityType.ArchiveSnapshot,
    EntityType.EvidenceArtifact -> EvidenceKind.SensitiveSnippet

    EntityType.Breach -> EvidenceKind.SensitiveSnippet
}

private fun io.dossier.app.domain.model.GraphNodeState.toEvidenceState(): EvidenceState = when (this) {
    io.dossier.app.domain.model.GraphNodeState.Confirmed -> EvidenceState.Verified
    io.dossier.app.domain.model.GraphNodeState.High -> EvidenceState.Probable
    io.dossier.app.domain.model.GraphNodeState.Medium,
    io.dossier.app.domain.model.GraphNodeState.Low,
    io.dossier.app.domain.model.GraphNodeState.Unresolved -> EvidenceState.Candidate
    io.dossier.app.domain.model.GraphNodeState.Conflicting -> EvidenceState.Conflicting
}
