package io.dossier.app.export

import io.dossier.app.domain.model.DossierEdge
import io.dossier.app.domain.model.DossierEntity
import io.dossier.app.domain.model.EntityGraph
import io.dossier.app.domain.model.GraphEntityKind
import java.security.MessageDigest

/**
 * Configuration for evidence-safe graph redaction.
 */
data class GraphRedactionPolicy(
    val mode: ExportRedactionMode = ExportRedactionMode.ShareSafe,
    val salt: String = DEFAULT_SALT,
    val maskLabels: Boolean = true,
    val stripUrls: Boolean = true,
    val stripEvidence: Boolean = true
) {
    companion object {
        const val DEFAULT_SALT = "dossier-share-safe-graph-v1"
        val RAW = GraphRedactionPolicy(
            mode = ExportRedactionMode.None,
            maskLabels = false,
            stripUrls = false,
            stripEvidence = false
        )
        val SHARE_SAFE = GraphRedactionPolicy(
            mode = ExportRedactionMode.ShareSafe,
            maskLabels = true,
            stripUrls = true,
            stripEvidence = true
        )
    }
}

/**
 * Deterministic, offline projection of an EntityGraph into a privacy-safe representation.
 *
 * Preserves topology, connectivity, relation types, node kinds, states, and confidence scores
 * while replacing direct identifiers (subject names, handles, URLs, and evidence IDs) with
 * stable salted opaque hashes and generic typed labels.
 */
object GraphRedactor {

    fun redact(
        graph: EntityGraph,
        policy: GraphRedactionPolicy = GraphRedactionPolicy.SHARE_SAFE
    ): EntityGraph {
        if (policy.mode == ExportRedactionMode.None) {
            return graph
        }

        val entityKindMap = graph.entities.associate { it.id to it.kind }
        val idMap = mutableMapOf<String, String>()
        fun opaqueId(rawId: String): String = idMap.getOrPut(rawId) {
            val kindPrefix = entityKindMap[rawId]?.name?.lowercase()?.let { "node-$it-" } ?: "node-"
            kindPrefix + sha256("${policy.salt}:$rawId").take(8)
        }

        val kindCounters = mutableMapOf<GraphEntityKind, Int>()
        val redactedEntities = graph.entities.map { entity ->
            val newId = opaqueId(entity.id)
            val count = kindCounters.getOrPut(entity.kind) { 0 } + 1
            kindCounters[entity.kind] = count
            val label = if (policy.maskLabels) {
                "[Redacted ${entity.kind.name} $count]"
            } else {
                entity.label
            }
            entity.copy(
                id = newId,
                label = label,
                sourceUrls = if (policy.stripUrls) emptyList() else entity.sourceUrls,
                evidenceIds = if (policy.stripEvidence) emptyList() else entity.evidenceIds
            )
        }

        val redactedEdges = graph.edges.map { edge ->
            edge.copy(
                fromId = opaqueId(edge.fromId),
                toId = opaqueId(edge.toId),
                evidence = if (policy.stripEvidence) null else edge.evidence,
                evidenceIds = if (policy.stripEvidence) emptyList() else edge.evidenceIds,
                contradictingEvidenceIds = if (policy.stripEvidence) emptyList() else edge.contradictingEvidenceIds
            )
        }

        return EntityGraph(
            entities = redactedEntities,
            edges = redactedEdges,
            schemaVersion = graph.schemaVersion
        )
    }

    fun redact(
        graph: EntityGraph,
        mode: ExportRedactionMode
    ): EntityGraph = if (mode == ExportRedactionMode.ShareSafe) {
        redact(graph, GraphRedactionPolicy.SHARE_SAFE)
    } else {
        graph
    }

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
