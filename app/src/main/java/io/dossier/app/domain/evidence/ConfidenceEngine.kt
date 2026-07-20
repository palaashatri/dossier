package io.dossier.app.domain.evidence

import io.dossier.app.domain.model.EntityGraph
import kotlinx.serialization.Serializable

/**
 * Result of scoring a single relationship (edge) between two evidence values.
 *
 * @param score 0..1 confidence the two endpoints refer to the same subject.
 * @param reasons Named signals that justify the score (ROADMAP Principle 3:
 *   every confidence must explain itself).
 */
@Serializable
data class RelationshipConfidence(
    val score: Float,
    val reasons: List<String>
)

/**
 * Folds every registered [ConfidenceContributor] over the relationships in an
 * [EntityGraph] to produce an explainable confidence per edge (ROADMAP M7).
 *
 * The engine does not invent relationships — it only scores edges the
 * correlation step already produced. Each contributor may return a partial
 * score; the engine takes the maximum (most confident) and concatenates the
 * reasons so the conclusion stays attributable.
 *
 * This is introduced alongside the legacy graph builder and does not change how
 * the graph is built; it only annotates edges.
 */
class ConfidenceEngine(
    private val contributors: List<ConfidenceContributor>
) {
    /**
     * @param graph the fused entity graph (edges are the relationships to score)
     * @param evidence all observed evidence, indexed by value for fast lookup
     * @return map keyed by "fromId|toId|relation" → scored confidence
     */
    fun score(
        graph: EntityGraph,
        evidence: List<Evidence>
    ): Map<String, RelationshipConfidence> {
        val byValue = evidence.associateBy { it.value.lowercase() }
        val result = mutableMapOf<String, RelationshipConfidence>()

        for (edge in graph.edges) {
            val fromEntity = graph.entities.firstOrNull { it.id == edge.fromId } ?: continue
            val toEntity = graph.entities.firstOrNull { it.id == edge.toId } ?: continue

            // Best-effort: match each endpoint's label to an evidence value so
            // contributors that key off observed values can fire. Falls back to a
            // synthetic evidence derived from the entity when no match exists.
            val a = byValue[fromEntity.label.lowercase()]
                ?: Evidence(id = fromEntity.id, kind = fromEntity.type.toEvidenceKind(), value = fromEntity.label, confidence = fromEntity.confidence)
            val b = byValue[toEntity.label.lowercase()]
                ?: Evidence(id = toEntity.id, kind = toEntity.type.toEvidenceKind(), value = toEntity.label, confidence = toEntity.confidence)

            val (score, reasons) = contributors.fold(0f to emptyList<String>()) { acc: Pair<Float, List<String>>, contributor ->
                val signals = contributor.contribute(a, b) ?: return@fold acc
                val mergedScore = maxOf(acc.first, signals.score)
                val mergedReasons = (acc.second + signals.reasons).distinct()
                mergedScore to mergedReasons
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

private fun io.dossier.app.domain.model.EntityType.toEvidenceKind(): EvidenceKind =
    when (this) {
        io.dossier.app.domain.model.EntityType.Person -> EvidenceKind.Username
        io.dossier.app.domain.model.EntityType.Username -> EvidenceKind.Username
        io.dossier.app.domain.model.EntityType.Email -> EvidenceKind.Email
        io.dossier.app.domain.model.EntityType.Phone -> EvidenceKind.Phone
        io.dossier.app.domain.model.EntityType.Profile -> EvidenceKind.Profile
        io.dossier.app.domain.model.EntityType.Organization -> EvidenceKind.Organization
        io.dossier.app.domain.model.EntityType.Location -> EvidenceKind.Location
        io.dossier.app.domain.model.EntityType.Image -> EvidenceKind.ImageConsistency
        io.dossier.app.domain.model.EntityType.Breach -> EvidenceKind.SensitiveSnippet
        io.dossier.app.domain.model.EntityType.Website -> EvidenceKind.PublicSearchEvidence
    }
