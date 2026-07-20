package io.dossier.app.domain.evidence

import io.dossier.app.domain.model.DossierEdge
import io.dossier.app.domain.model.DossierEntity
import io.dossier.app.domain.model.EntityGraph
import io.dossier.app.domain.model.EntityType
import kotlinx.serialization.Serializable

/**
 * Attack-path discovery (ROADMAP Milestone 10).
 *
 * Produces explainable chains from the subject (Person) to a high-risk endpoint
 * (e.g. a breach, or a node reached through high-confidence edges), so the user
 * can see *how* a piece of exposure is reachable — not just that it exists.
 *
 * Paths are found by BFS over the undirected [EntityGraph] so a link works in
 * either direction. Each step records the relation and (when available) the
 * scored confidence, satisfying Principle 3 (every step explains itself). This
 * never invents edges; it only walks edges the correlation step already built.
 */
class AttackPathFinder {

    @Serializable
    data class Step(
        val fromLabel: String,
        val toLabel: String,
        val relation: String,
        val evidence: String?,
        val confidence: Float?   // null when no contributor scored this edge
    )

    @Serializable
    data class AttackPath(
        val endpointType: EntityType,
        val endpointLabel: String,
        val steps: List<Step>,
        val riskHint: String
    )

    fun findPaths(
        graph: EntityGraph,
        confidenceByEdge: Map<String, RelationshipConfidence> = emptyMap(),
        maxPaths: Int = 5
    ): List<AttackPath> {
        val subject = graph.entities.firstOrNull { it.type == EntityType.Person } ?: return emptyList()
        val adjacency = buildAdjacency(graph)

        // BFS parent map from subject.
        val parent = mutableMapOf<String, String?>();
        parent[subject.id] = null
        val queue = ArrayDeque<String>().apply { add(subject.id) }
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            adjacency[cur].orEmpty().forEach { nxt ->
                if (nxt !in parent) {
                    parent[nxt] = cur
                    queue.add(nxt)
                }
            }
        }

        val highRiskEndpoints = graph.entities.filter { it.type == EntityType.Breach }
        val endpoints = if (highRiskEndpoints.isNotEmpty()) highRiskEndpoints else emptyList()

        val paths = endpoints.mapNotNull { endpoint ->
            reconstructPath(endpoint.id, parent, graph, confidenceByEdge)
        }.sortedByDescending { it.steps.size }

        return paths.take(maxPaths)
    }

    private fun reconstructPath(
        endpointId: String,
        parent: Map<String, String?>,
        graph: EntityGraph,
        confidenceByEdge: Map<String, RelationshipConfidence>
    ): AttackPath? {
        val entityById = graph.entities.associateBy { it.id }
        val edgeBetween = graph.edges.groupBy { setOf(it.fromId, it.toId) }

        val chain = mutableListOf<String>()
        var cur: String? = endpointId
        while (cur != null) {
            chain.add(cur)
            cur = parent[cur]
        }
        chain.reverse()
        if (chain.size < 2) return null

        val steps = mutableListOf<Step>()
        for (i in 0 until chain.size - 1) {
            val a = entityById[chain[i]] ?: return null
            val b = entityById[chain[i + 1]] ?: return null
            val edge = edgeBetween[setOf(a.id, b.id)]?.firstOrNull { it.fromId == a.id || it.toId == a.id }
                ?: return null
            val fromLabel = if (edge.fromId == a.id) a.label else b.label
            val toLabel = if (edge.fromId == a.id) b.label else a.label
            val key = ConfidenceEngine.edgeKey(edge.fromId, edge.toId, edge.relation)
            val scored = confidenceByEdge[key]
            steps.add(
                Step(
                    fromLabel = fromLabel,
                    toLabel = toLabel,
                    relation = edge.relation,
                    evidence = edge.evidence,
                    confidence = scored?.score
                )
            )
        }
        val endpoint = entityById[chain.last()] ?: return null
        return AttackPath(
            endpointType = endpoint.type,
            endpointLabel = endpoint.label,
            steps = steps,
            riskHint = "Reachable from subject in ${steps.size} hop(s)"
        )
    }

    private fun buildAdjacency(graph: EntityGraph): Map<String, Set<String>> {
        val adj = mutableMapOf<String, MutableSet<String>>()
        fun add(a: String, b: String) {
            adj.getOrPut(a) { mutableSetOf() }.add(b)
            adj.getOrPut(b) { mutableSetOf() }.add(a)
        }
        graph.edges.forEach { add(it.fromId, it.toId) }
        return adj
    }
}
