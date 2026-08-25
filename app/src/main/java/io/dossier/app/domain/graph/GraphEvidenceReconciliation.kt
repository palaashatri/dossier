package io.dossier.app.domain.graph

import io.dossier.app.domain.case.DossierCase
import io.dossier.app.domain.evidence.Evidence
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
    val truncatedGraphEdges: Int = 0,
    val truncatedCanonicalEvidenceIds: Int = 0,
    val truncatedGraphEvidenceIds: Int = 0,
    /** Distinct relationship IDs that do not resolve to a persisted evidence record. */
    val danglingCanonicalEvidenceIds: Int = 0,
    /** Distinct graph-edge IDs that do not resolve to a persisted evidence record. */
    val danglingGraphEvidenceIds: Int = 0,
    /** Distinct graph-entity IDs that do not resolve to a persisted evidence record. */
    val danglingGraphEntityEvidenceIds: Int = 0,
    /** Graph-entity provenance IDs omitted from the bounded comparison. */
    val truncatedGraphEntityEvidenceIds: Int = 0
) {
    val isConsistent: Boolean
        get() = missingGraphEdges == 0 &&
            extraGraphEdges == 0 &&
            conflictingEvidence == 0 &&
            ambiguousRelationships == 0 &&
            truncatedCanonicalRelationships == 0 &&
            truncatedGraphEdges == 0 &&
            truncatedCanonicalEvidenceIds == 0 &&
            truncatedGraphEvidenceIds == 0 &&
            danglingCanonicalEvidenceIds == 0 &&
            danglingGraphEvidenceIds == 0 &&
            danglingGraphEntityEvidenceIds == 0 &&
            truncatedGraphEntityEvidenceIds == 0
}

enum class GraphEvidenceReconciliationKind {
    MissingGraphEdge,
    ExtraGraphEdge,
    ConflictingEvidence,
    Ambiguous,
    /** A provenance ID is present but absent from the case evidence ledger. */
    DanglingEvidenceReference,
    /** A bounded provenance projection omitted one or more IDs. */
    TruncatedEvidenceReference
}

data class GraphEvidenceReconciliationDiagnostic(
    val kind: GraphEvidenceReconciliationKind,
    val fromValue: String,
    val toValue: String,
    val relation: String,
    val canonicalEvidenceIds: List<String> = emptyList(),
    val graphEvidenceIds: List<String> = emptyList(),
    val graphEdgeReferences: List<String> = emptyList(),
    val graphEntityReferences: List<String> = emptyList(),
    val truncatedCanonicalEvidenceIds: Int = 0,
    val truncatedGraphEvidenceIds: Int = 0,
    val truncatedGraphEntityEvidenceIds: Int = 0,
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
    const val MAX_EVIDENCE_IDS_PER_DIAGNOSTIC = 256

    fun validate(
        canonicalRelationships: List<EvidenceRelationship>,
        graph: EntityGraph,
        /**
         * Optional evidence ledger. A null value preserves the legacy
         * two-argument diagnostic semantics when callers only have a graph
         * projection. An explicitly supplied ledger enables fail-closed
         * dangling-reference checks without inventing evidence.
         */
        evidenceRecords: List<Evidence>? = null
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

        val truncatedCanonicalEvidenceIds = boundedCanonical.sumOf {
            it.normalizedEvidenceIds().truncated
        }
        val allGraphProjections = graphByKey.values.asSequence().flatten() + unresolvedEdges.asSequence()
        val truncatedGraphEvidenceIds = allGraphProjections.sumOf { it.truncatedEvidenceIds }
        val graphEntityProjections = graph.entities.map { entity ->
            val evidenceIds = normalizeEvidenceIds(entity.evidenceIds.asSequence())
            GraphEntityProjection(
                reference = entity.id,
                normalizedEvidenceIds = evidenceIds.ids,
                truncatedEvidenceIds = evidenceIds.truncated
            )
        }
        val truncatedGraphEntityEvidenceIds = graphEntityProjections.sumOf {
            it.truncatedEvidenceIds
        }

        fun addDiagnostic(diagnostic: GraphEvidenceReconciliationDiagnostic) {
            if (diagnostics.size < MAX_DIAGNOSTICS) diagnostics += diagnostic
        }

        val allKeys = (canonicalByKey.keys + graphByKey.keys).sortedWith(RelationshipKey.ORDER)
        allKeys.forEach { key ->
            val canonical = canonicalByKey[key].orEmpty()
            val graphEdges = graphByKey[key].orEmpty()
            when {
                canonical.size > 1 || graphEdges.size > 1 -> {
                    val canonicalEvidence = canonicalEvidenceIds(canonical)
                    val graphEvidence = graphEvidenceIds(graphEdges)
                    ambiguous++
                    addDiagnostic(
                        diagnostic(
                            kind = GraphEvidenceReconciliationKind.Ambiguous,
                            key = key,
                            canonicalEvidenceIds = canonicalEvidence.ids,
                            graphEvidenceIds = graphEvidence.ids,
                            graphEdgeReferences = graphEdges.map { it.reference }
                                .distinct()
                                .sorted()
                                .take(MAX_EDGE_REFERENCES_PER_DIAGNOSTIC),
                            truncatedCanonicalEvidenceIds = canonicalEvidence.truncated,
                            truncatedGraphEvidenceIds = graphEvidence.truncated,
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
                                truncatedGraphEvidenceIds = graphEdge.truncatedEvidenceIds,
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
                                truncatedGraphEvidenceIds = graphEdge.truncatedEvidenceIds,
                                reason = "graph edge has no canonical evidence relationship assertion; it may be derived graph material"
                            )
                        )
                    }
                }

                graphEdges.isEmpty() -> {
                    missing++
                    val relationship = canonical.single()
                    val canonicalEvidence = relationship.normalizedEvidenceIds()
                    addDiagnostic(
                        diagnostic(
                            kind = GraphEvidenceReconciliationKind.MissingGraphEdge,
                            key = key,
                            canonicalEvidenceIds = canonicalEvidence.ids,
                            truncatedCanonicalEvidenceIds = canonicalEvidence.truncated,
                            reason = "canonical evidence relationship has no exact graph edge"
                        )
                    )
                }

                else -> {
                    val relationship = canonical.single()
                    val graphEdge = graphEdges.single()
                    val canonicalEvidence = relationship.normalizedEvidenceIds()
                    val graphEvidence = BoundedEvidenceIds(
                        graphEdge.normalizedEvidenceIds,
                        graphEdge.truncatedEvidenceIds
                    )
                    if (canonicalEvidence.truncated > 0 || graphEvidence.truncated > 0) {
                        ambiguous++
                        addDiagnostic(
                            diagnostic(
                                kind = GraphEvidenceReconciliationKind.Ambiguous,
                                key = key,
                                canonicalEvidenceIds = canonicalEvidence.ids,
                                graphEvidenceIds = graphEvidence.ids,
                                graphEdgeReferences = listOf(graphEdge.reference),
                                truncatedCanonicalEvidenceIds = canonicalEvidence.truncated,
                                truncatedGraphEvidenceIds = graphEvidence.truncated,
                                reason = "exact endpoint/relation match exceeds the bounded evidence-ID comparison limit"
                            )
                        )
                    } else if (canonicalEvidence.ids.isEmpty() && graphEvidence.ids.isEmpty()) {
                        ambiguous++
                        addDiagnostic(
                            diagnostic(
                                kind = GraphEvidenceReconciliationKind.Ambiguous,
                                key = key,
                                reason = "exact endpoint/relation match has no evidence IDs on either side"
                            )
                        )
                    } else if (canonicalEvidence.ids == graphEvidence.ids) {
                        matched++
                    } else {
                        conflicting++
                        addDiagnostic(
                            diagnostic(
                                kind = GraphEvidenceReconciliationKind.ConflictingEvidence,
                                key = key,
                                canonicalEvidenceIds = canonicalEvidence.ids,
                                graphEvidenceIds = graphEvidence.ids,
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
                        truncatedGraphEvidenceIds = edge.truncatedEvidenceIds,
                        reason = "graph edge endpoint entity is missing; exact endpoint comparison is unavailable"
                    )
                )
            }

        graphEntityProjections
            .asSequence()
            .filter { it.truncatedEvidenceIds > 0 }
            .sortedBy(GraphEntityProjection::reference)
            .forEach { entity ->
                addDiagnostic(
                    GraphEvidenceReconciliationDiagnostic(
                        kind = GraphEvidenceReconciliationKind.TruncatedEvidenceReference,
                        fromValue = "[graph entity projection]",
                        toValue = entity.reference,
                        relation = "EVIDENCE_REFERENCE",
                        graphEvidenceIds = entity.normalizedEvidenceIds,
                        graphEntityReferences = listOf(entity.reference),
                        truncatedGraphEntityEvidenceIds = entity.truncatedEvidenceIds,
                        reason = "graph entity evidence IDs exceed the bounded comparison limit"
                    )
                )
            }

        val evidenceLedger = evidenceRecords
            ?.mapNotNull { record ->
                val id = EvidenceIdPolicy.migrate(record.id.trim())
                id.takeIf(String::isNotBlank)
            }
            ?.toSet()
        val danglingCanonicalEvidenceIds = evidenceLedger?.let { available ->
            boundedCanonical.asSequence()
                .flatMap { relationship ->
                    relationship.normalizedEvidenceIds().ids.asSequence()
                }
                .filterNot(available::contains)
                .toSet()
                .also { missingIds ->
                    if (missingIds.isNotEmpty()) {
                        addDiagnostic(
                            GraphEvidenceReconciliationDiagnostic(
                                kind = GraphEvidenceReconciliationKind.DanglingEvidenceReference,
                                fromValue = "[canonical relationship ledger]",
                                toValue = "",
                                relation = "EVIDENCE_REFERENCE",
                                canonicalEvidenceIds = missingIds.sorted()
                                    .take(MAX_EVIDENCE_IDS_PER_DIAGNOSTIC),
                                truncatedCanonicalEvidenceIds =
                                    (missingIds.size - MAX_EVIDENCE_IDS_PER_DIAGNOSTIC).coerceAtLeast(0),
                                reason = "canonical relationship evidence IDs do not resolve to persisted evidence records"
                            )
                        )
                    }
                }
                .size
        } ?: 0
        val danglingGraphEvidenceIds = evidenceLedger?.let { available ->
            allGraphProjections
                .flatMap { projection -> projection.normalizedEvidenceIds.asSequence() }
                .filterNot(available::contains)
                .toSet()
                .also { missingIds ->
                    if (missingIds.isNotEmpty()) {
                        addDiagnostic(
                            GraphEvidenceReconciliationDiagnostic(
                                kind = GraphEvidenceReconciliationKind.DanglingEvidenceReference,
                                fromValue = "[graph edge projection]",
                                toValue = "",
                                relation = "EVIDENCE_REFERENCE",
                                graphEvidenceIds = missingIds.sorted()
                                    .take(MAX_EVIDENCE_IDS_PER_DIAGNOSTIC),
                                truncatedGraphEvidenceIds =
                                    (missingIds.size - MAX_EVIDENCE_IDS_PER_DIAGNOSTIC).coerceAtLeast(0),
                                reason = "graph edge evidence IDs do not resolve to persisted evidence records"
                            )
                        )
                    }
                }
                .size
        } ?: 0
        val danglingGraphEntityEvidenceIds = evidenceLedger?.let { available ->
            graphEntityProjections
                .asSequence()
                .flatMap { projection -> projection.normalizedEvidenceIds.asSequence() }
                .filterNot(available::contains)
                .toSet()
                .also { missingIds ->
                    if (missingIds.isNotEmpty()) {
                        addDiagnostic(
                            GraphEvidenceReconciliationDiagnostic(
                                kind = GraphEvidenceReconciliationKind.DanglingEvidenceReference,
                                fromValue = "[graph entity projection]",
                                toValue = "",
                                relation = "EVIDENCE_REFERENCE",
                                graphEvidenceIds = missingIds.sorted()
                                    .take(MAX_EVIDENCE_IDS_PER_DIAGNOSTIC),
                                graphEntityReferences = graphEntityProjections
                                    .filter { projection ->
                                        projection.normalizedEvidenceIds.any(missingIds::contains)
                                    }
                                    .map(GraphEntityProjection::reference)
                                    .distinct()
                                    .sorted()
                                    .take(MAX_EDGE_REFERENCES_PER_DIAGNOSTIC),
                                truncatedGraphEntityEvidenceIds =
                                    (missingIds.size - MAX_EVIDENCE_IDS_PER_DIAGNOSTIC).coerceAtLeast(0),
                                reason = "graph entity evidence IDs do not resolve to persisted evidence records"
                            )
                        )
                    }
                }
                .size
        } ?: 0

        return GraphEvidenceReconciliationReport(
            matchedRelationships = matched,
            missingGraphEdges = missing,
            extraGraphEdges = extra,
            conflictingEvidence = conflicting,
            ambiguousRelationships = ambiguous,
            diagnostics = diagnostics,
            truncatedCanonicalRelationships = (canonicalRelationships.size - boundedCanonical.size).coerceAtLeast(0),
            truncatedGraphEdges = (graph.edges.size - boundedEdges.size).coerceAtLeast(0),
            truncatedCanonicalEvidenceIds = truncatedCanonicalEvidenceIds,
            truncatedGraphEvidenceIds = truncatedGraphEvidenceIds,
            danglingCanonicalEvidenceIds = danglingCanonicalEvidenceIds,
            danglingGraphEvidenceIds = danglingGraphEvidenceIds,
            danglingGraphEntityEvidenceIds = danglingGraphEntityEvidenceIds,
            truncatedGraphEntityEvidenceIds = truncatedGraphEntityEvidenceIds
        )
    }

    fun validate(case: DossierCase): GraphEvidenceReconciliationReport =
        // Empty evidence records mean the legacy case has no available ledger;
        // do not reinterpret every legacy ID as dangling solely because the
        // record was not persisted by an older schema.
        validate(
            case.canonicalEvidenceRelationships(),
            case.entityGraph,
            case.evidenceRecords.takeIf(List<Evidence>::isNotEmpty)
        )

    private fun diagnostic(
        kind: GraphEvidenceReconciliationKind,
        key: RelationshipKey,
        canonicalEvidenceIds: List<String> = emptyList(),
        graphEvidenceIds: List<String> = emptyList(),
        graphEdgeReferences: List<String> = emptyList(),
        graphEntityReferences: List<String> = emptyList(),
        truncatedCanonicalEvidenceIds: Int = 0,
        truncatedGraphEvidenceIds: Int = 0,
        truncatedGraphEntityEvidenceIds: Int = 0,
        reason: String
    ): GraphEvidenceReconciliationDiagnostic = GraphEvidenceReconciliationDiagnostic(
        kind = kind,
        fromValue = key.fromValue,
        toValue = key.toValue,
        relation = key.relation,
        canonicalEvidenceIds = canonicalEvidenceIds,
        graphEvidenceIds = graphEvidenceIds,
        graphEdgeReferences = graphEdgeReferences,
        graphEntityReferences = graphEntityReferences,
        truncatedCanonicalEvidenceIds = truncatedCanonicalEvidenceIds,
        truncatedGraphEvidenceIds = truncatedGraphEvidenceIds,
        truncatedGraphEntityEvidenceIds = truncatedGraphEntityEvidenceIds,
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
        val truncatedEvidenceIds: Int,
        val reference: String
    ) {
    }

    private data class GraphEntityProjection(
        val reference: String,
        val normalizedEvidenceIds: List<String>,
        val truncatedEvidenceIds: Int
    )

    private data class BoundedEvidenceIds(
        val ids: List<String>,
        val truncated: Int
    )

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
        val evidenceIds = normalizeEvidenceIds(
            sequenceOf(edge.evidenceIds.asSequence(), edge.contradictingEvidenceIds.asSequence())
                .flatten()
        )
        return GraphEdgeProjection(
            key = key,
            fromDisplay = fromDisplay,
            toDisplay = toDisplay,
            normalizedRelation = normalizedRelation,
            normalizedEvidenceIds = evidenceIds.ids,
            truncatedEvidenceIds = evidenceIds.truncated,
            reference = listOf(edge.fromId, edge.toId, normalizedRelation)
                .joinToString("|")
        )
    }

    private fun EvidenceRelationship.key(): RelationshipKey = RelationshipKey(
        fromValue = normalizeEndpoint(fromValue),
        toValue = normalizeEndpoint(toValue),
        relation = relation.trim().uppercase(Locale.ROOT)
    )

    private fun EvidenceRelationship.normalizedEvidenceIds(): BoundedEvidenceIds =
        normalizeEvidenceIds(evidenceIds.asSequence())

    private fun normalizeEndpoint(value: String): String = value.trim().lowercase(Locale.ROOT)

    private fun normalizeEvidenceIds(ids: Sequence<String>): BoundedEvidenceIds {
        val normalized = LinkedHashSet<String>(MAX_EVIDENCE_IDS_PER_DIAGNOSTIC)
        var truncated = 0
        ids.forEach { raw ->
            val id = EvidenceIdPolicy.migrate(raw.trim())
            if (id.isBlank()) return@forEach
            if (normalized.size < MAX_EVIDENCE_IDS_PER_DIAGNOSTIC) {
                normalized += id
            } else {
                // Do not retain unbounded IDs merely to count/deduplicate them. Any
                // entry beyond the diagnostic bound makes exact reconciliation
                // unavailable, so the caller fails closed and reports truncation.
                truncated++
            }
        }
        return BoundedEvidenceIds(normalized.toList().sorted(), truncated)
    }

    private fun combineEvidenceIds(parts: Sequence<BoundedEvidenceIds>): BoundedEvidenceIds {
        val normalized = LinkedHashSet<String>(MAX_EVIDENCE_IDS_PER_DIAGNOSTIC)
        var truncated = 0
        parts.forEach { part ->
            truncated += part.truncated
            part.ids.forEach { id ->
                if (normalized.size < MAX_EVIDENCE_IDS_PER_DIAGNOSTIC) {
                    normalized += id
                } else {
                    truncated++
                }
            }
        }
        return BoundedEvidenceIds(normalized.toList().sorted(), truncated)
    }

    private fun canonicalEvidenceIds(
        relationships: List<EvidenceRelationship>
    ): BoundedEvidenceIds = combineEvidenceIds(
        relationships.asSequence().map { relationship -> relationship.normalizedEvidenceIds() }
    )

    private fun graphEvidenceIds(
        edges: List<GraphEdgeProjection>
    ): BoundedEvidenceIds = combineEvidenceIds(
        edges.asSequence().map { edge ->
            BoundedEvidenceIds(edge.normalizedEvidenceIds, edge.truncatedEvidenceIds)
        }
    )
}

/** Read-only case diagnostic entry point used by callers that have a case snapshot. */
fun DossierCase.graphEvidenceReconciliation(): GraphEvidenceReconciliationReport =
    GraphEvidenceReconciliation.validate(this)
