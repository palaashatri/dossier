package io.dossier.app.domain.graph

import io.dossier.app.domain.case.DossierCase
import io.dossier.app.domain.evidence.EvidenceIdPolicy
import io.dossier.app.domain.evidence.EvidenceRelationship
import io.dossier.app.domain.model.DossierEdge
import io.dossier.app.domain.model.EntityGraph
import java.util.Locale

/**
 * The result of comparing canonical scanner/plugin assertions with persisted
 * graph edges. This is diagnostic data only: it never creates an edge, merges
 * an endpoint, or rewrites either input collection. Extra graph edges can be
 * legitimate derived graph material that has no canonical scanner assertion;
 * the diagnostic does not imply that such an edge is corrupt.
 */
data class GraphEvidenceReconciliationReport(
    val matchedRelationships: Int,
    val missingGraphEdges: Int,
    val extraGraphEdges: Int,
    val conflictingEvidence: Int,
    val ambiguousRelationships: Int,
    val diagnostics: List<GraphEvidenceReconciliationDiagnostic>,
    val truncatedCanonicalRelationships: Int = 0,
    val truncatedGraphEdges: Int = 0
) {
    val isConsistent: Boolean
        get() = missingGraphEdges == 0 &&
            extraGraphEdges == 0 &&
            conflictingEvidence == 0 &&
            ambiguousRelationships == 0 &&
            truncatedCanonicalRelationships == 0 &&
            truncatedGraphEdges == 0
}

enum class GraphEvidenceReconciliationKind {
    MissingGraphEdge,
    ExtraGraphEdge,
    ConflictingEvidence,
    Ambiguous
}

data class GraphEvidenceReconciliationDiagnostic(
    val kind: GraphEvidenceReconciliationKind,
    val fromValue: String,
    val toValue: String,
    val relation: String,
    val canonicalEvidenceIds: List<String> = emptyList(),
    val graphEvidenceIds: List<String> = emptyList(),
    val graphEdgeReferences: List<String> = emptyList(),
    val reason: String
)

/**
 * Exact, bounded comparison of canonical relationship assertions and graph
 * edges. Endpoint labels and relation names are normalized only for lookup;
 * no fuzzy matching or identity inference is performed.
 */
object GraphEvidenceReconciliation {
    const val MAX_RELATIONSHIPS = 10_000
    const val MAX_DIAGNOSTICS = 512
    const val MAX_EDGE_REFERENCES_PER_DIAGNOSTIC = 16

    fun validate(
        canonicalRelationships: List<EvidenceRelationship>,
        graph: EntityGraph
    ): GraphEvidenceReconciliationReport {
        val boundedCanonical = canonicalRelationships.take(MAX_RELATIONSHIPS)
        val boundedEdges = graph.edges.take(MAX_RELATIONSHIPS)
        val canonicalByKey = linkedMapOf<RelationshipKey, MutableList<EvidenceRelationship>>()
        boundedCanonical.forEach { relationship ->
            canonicalByKey.getOrPut(relationship.key()) { mutableListOf() } += relationship
        }

        val graphByKey = linkedMapOf<RelationshipKey, MutableList<GraphEdgeProjection>>()
        val unresolvedEdges = mutableListOf<GraphEdgeProjection>()
        boundedEdges.forEach { edge ->
            val projection = project(edge, graph)
            val key = projection.key
            if (key == null) {
                unresolvedEdges += projection
            } else {
                graphByKey.getOrPut(key) { mutableListOf() } += projection
            }
        }

        val diagnostics = mutableListOf<GraphEvidenceReconciliationDiagnostic>()
        var matched = 0
        var missing = 0
        var extra = 0
        var conflicting = 0
        var ambiguous = 0

        fun addDiagnostic(diagnostic: GraphEvidenceReconciliationDiagnostic) {
            if (diagnostics.size < MAX_DIAGNOSTICS) diagnostics += diagnostic
        }

        val allKeys = (canonicalByKey.keys + graphByKey.keys).sortedWith(RelationshipKey.ORDER)
        allKeys.forEach { key ->
            val canonical = canonicalByKey[key].orEmpty()
            val graphEdges = graphByKey[key].orEmpty()
            when {
                canonical.size > 1 || graphEdges.size > 1 -> {
                    ambiguous++
                    addDiagnostic(
                        diagnostic(
                            kind = GraphEvidenceReconciliationKind.Ambiguous,
                            key = key,
                            canonicalEvidenceIds = canonical.flatMap { it.normalizedEvidenceIds() }
                                .distinct()
                                .sorted(),
                            graphEvidenceIds = graphEdges.flatMap { it.normalizedEvidenceIds }
                                .distinct()
                                .sorted(),
                            graphEdgeReferences = graphEdges.map { it.reference }
                                .distinct()
                                .sorted()
                                .take(MAX_EDGE_REFERENCES_PER_DIAGNOSTIC),
                            reason = when {
                                canonical.size > 1 && graphEdges.size > 1 ->
                                    "multiple canonical assertions and graph edges share the same exact key"
                                canonical.size > 1 ->
                                    "multiple canonical assertions share the same exact key"
                                else ->
                                    "multiple graph edges share the same exact key"
                            }
                        )
                    )
                }

                canonical.isEmpty() -> {
                    val graphEdge = graphEdges.single()
                    if (graphEdge.normalizedEvidenceIds.isEmpty()) {
                        ambiguous++
                        addDiagnostic(
                            diagnostic(
                                kind = GraphEvidenceReconciliationKind.Ambiguous,
                                key = key,
                                graphEdgeReferences = listOf(graphEdge.reference),
                                reason = "graph edge has no canonical assertion or evidence IDs"
                            )
                        )
                    } else {
                        extra++
                        addDiagnostic(
                            diagnostic(
                                kind = GraphEvidenceReconciliationKind.ExtraGraphEdge,
                                key = key,
                                graphEvidenceIds = graphEdge.normalizedEvidenceIds,
                                graphEdgeReferences = listOf(graphEdge.reference),
                                reason = "graph edge has no canonical evidence relationship assertion; it may be derived graph material"
                            )
                        )
                    }
                }

                graphEdges.isEmpty() -> {
                    missing++
                    val relationship = canonical.single()
                    addDiagnostic(
                        diagnostic(
                            kind = GraphEvidenceReconciliationKind.MissingGraphEdge,
                            key = key,
                            canonicalEvidenceIds = relationship.normalizedEvidenceIds(),
                            reason = "canonical evidence relationship has no exact graph edge"
                        )
                    )
                }

                else -> {
                    val relationship = canonical.single()
                    val graphEdge = graphEdges.single()
                    val canonicalIds = relationship.normalizedEvidenceIds()
                    if (canonicalIds.isEmpty() && graphEdge.normalizedEvidenceIds.isEmpty()) {
                        ambiguous++
                        addDiagnostic(
                            diagnostic(
                                kind = GraphEvidenceReconciliationKind.Ambiguous,
                                key = key,
                                reason = "exact endpoint/relation match has no evidence IDs on either side"
                            )
                        )
                    } else if (canonicalIds == graphEdge.normalizedEvidenceIds) {
                        matched++
                    } else {
                        conflicting++
                        addDiagnostic(
                            diagnostic(
                                kind = GraphEvidenceReconciliationKind.ConflictingEvidence,
                                key = key,
                                canonicalEvidenceIds = canonicalIds,
                                graphEvidenceIds = graphEdge.normalizedEvidenceIds,
                                graphEdgeReferences = listOf(graphEdge.reference),
                                reason = "exact endpoint/relation match has different evidence ID sets"
                            )
                        )
                    }
                }
            }
        }

        unresolvedEdges
            .sortedBy(GraphEdgeProjection::reference)
            .forEach { edge ->
                ambiguous++
                addDiagnostic(
                    GraphEvidenceReconciliationDiagnostic(
                        kind = GraphEvidenceReconciliationKind.Ambiguous,
                        fromValue = edge.fromDisplay,
                        toValue = edge.toDisplay,
                        relation = edge.normalizedRelation,
                        graphEvidenceIds = edge.normalizedEvidenceIds,
                        graphEdgeReferences = listOf(edge.reference),
                        reason = "graph edge endpoint entity is missing; exact endpoint comparison is unavailable"
                    )
                )
            }

        return GraphEvidenceReconciliationReport(
            matchedRelationships = matched,
            missingGraphEdges = missing,
            extraGraphEdges = extra,
            conflictingEvidence = conflicting,
            ambiguousRelationships = ambiguous,
            diagnostics = diagnostics,
            truncatedCanonicalRelationships = (canonicalRelationships.size - boundedCanonical.size).coerceAtLeast(0),
            truncatedGraphEdges = (graph.edges.size - boundedEdges.size).coerceAtLeast(0)
        )
    }

    fun validate(case: DossierCase): GraphEvidenceReconciliationReport =
        validate(case.evidenceRelationships, case.entityGraph)

    private fun diagnostic(
        kind: GraphEvidenceReconciliationKind,
        key: RelationshipKey,
        canonicalEvidenceIds: List<String> = emptyList(),
        graphEvidenceIds: List<String> = emptyList(),
        graphEdgeReferences: List<String> = emptyList(),
        reason: String
    ): GraphEvidenceReconciliationDiagnostic = GraphEvidenceReconciliationDiagnostic(
        kind = kind,
        fromValue = key.fromValue,
        toValue = key.toValue,
        relation = key.relation,
        canonicalEvidenceIds = canonicalEvidenceIds,
        graphEvidenceIds = graphEvidenceIds,
        graphEdgeReferences = graphEdgeReferences,
        reason = reason
    )

    private data class RelationshipKey(
        val fromValue: String,
        val toValue: String,
        val relation: String
    ) {
        companion object {
            val ORDER = compareBy<RelationshipKey>(
                RelationshipKey::fromValue,
                RelationshipKey::toValue,
                RelationshipKey::relation
            )
        }
    }

    private data class GraphEdgeProjection(
        val key: RelationshipKey?,
        val fromDisplay: String,
        val toDisplay: String,
        val normalizedRelation: String,
        val normalizedEvidenceIds: List<String>,
        val reference: String
    ) {
    }

    private fun project(edge: DossierEdge, graph: EntityGraph): GraphEdgeProjection {
        val from = graph.entity(edge.fromId)
        val to = graph.entity(edge.toId)
        val normalizedRelation = edge.relation.trim().uppercase(Locale.ROOT)
        val fromDisplay = from?.label?.trim().orEmpty().ifBlank { "id:${edge.fromId}" }
        val toDisplay = to?.label?.trim().orEmpty().ifBlank { "id:${edge.toId}" }
        val key = if (from == null || to == null) {
            null
        } else {
            RelationshipKey(
                fromValue = normalizeEndpoint(from.label),
                toValue = normalizeEndpoint(to.label),
                relation = normalizedRelation
            )
        }
        return GraphEdgeProjection(
            key = key,
            fromDisplay = fromDisplay,
            toDisplay = toDisplay,
            normalizedRelation = normalizedRelation,
            normalizedEvidenceIds = normalizeEvidenceIds(
                edge.evidenceIds + edge.contradictingEvidenceIds
            ),
            reference = listOf(edge.fromId, edge.toId, normalizedRelation)
                .joinToString("|")
        )
    }

    private fun EvidenceRelationship.key(): RelationshipKey = RelationshipKey(
        fromValue = normalizeEndpoint(fromValue),
        toValue = normalizeEndpoint(toValue),
        relation = relation.trim().uppercase(Locale.ROOT)
    )

    private fun EvidenceRelationship.normalizedEvidenceIds(): List<String> =
        normalizeEvidenceIds(evidenceIds)

    private fun normalizeEndpoint(value: String): String = value.trim().lowercase(Locale.ROOT)

    private fun normalizeEvidenceIds(ids: List<String>): List<String> = ids
        .map(String::trim)
        .filter(String::isNotBlank)
        .map(EvidenceIdPolicy::migrate)
        .distinct()
        .sorted()
}

/** Read-only case diagnostic entry point used by callers that have a case snapshot. */
fun DossierCase.graphEvidenceReconciliation(): GraphEvidenceReconciliationReport =
    GraphEvidenceReconciliation.validate(this)
