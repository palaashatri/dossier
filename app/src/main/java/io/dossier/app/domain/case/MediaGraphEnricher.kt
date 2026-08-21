package io.dossier.app.domain.case

import io.dossier.app.domain.model.DossierEdge
import io.dossier.app.domain.model.DossierEntity
import io.dossier.app.domain.model.EntityGraph
import io.dossier.app.domain.model.EntityType
import io.dossier.app.domain.model.GraphEntityKind
import io.dossier.app.domain.model.GraphNodeState
import io.dossier.app.domain.model.RelationshipType
import io.dossier.app.domain.model.ReverseImageLookupResult
import io.dossier.app.domain.place.MediaIntelligenceSnapshot
import java.net.URI

/**
 * Adds inspectable image/repost evidence to a case graph without making identity
 * claims. Only matched/clustered public image candidates are promoted to nodes.
 */
object MediaGraphEnricher {
    fun enrich(graph: EntityGraph, media: MediaIntelligenceSnapshot): EntityGraph {
        if (media.imageResults.isEmpty()) return graph

        val entities = graph.entities.associateByTo(linkedMapOf(), DossierEntity::id)
        val edges = graph.edges.toMutableList()
        val candidatesById = linkedMapOf<String, ReverseImageLookupResult.ImageCandidateProvenance>()

        media.imageResults.forEach { result ->
            result.visualCandidates
                .filter { candidate ->
                    candidate.state == ReverseImageLookupResult.ImageCandidateState.Matched ||
                        candidate.clusterId != null
                }
                .take(MAX_CANDIDATES_PER_RESULT)
                .forEach { candidate ->
                    candidatesById[candidate.id] = candidate
                    val nodeId = imageNodeId(candidate.id)
                    entities.putIfAbsent(
                        nodeId,
                        DossierEntity(
                            id = nodeId,
                            type = EntityType.Image,
                            label = candidate.title.ifBlank { candidate.source }.take(160),
                            confidence = (candidate.comparisonScore ?: 0.5f).coerceIn(0f, 1f),
                            sourceUrls = listOf(candidate.imageUrl, candidate.sourcePageUrl)
                                .filter(String::isNotBlank)
                                .distinct(),
                            kind = GraphEntityKind.Image,
                            state = if (candidate.state == ReverseImageLookupResult.ImageCandidateState.Matched) {
                                GraphNodeState.High
                            } else {
                                GraphNodeState.Medium
                            }
                        )
                    )

                    graph.entities
                        .asSequence()
                        .filter { it.kind == GraphEntityKind.Account || it.type == EntityType.Profile }
                        .filter { account ->
                            account.sourceUrls.any { samePublicPage(it, candidate.sourcePageUrl) }
                        }
                        .take(MAX_ACCOUNT_LINKS_PER_IMAGE)
                        .forEach { account ->
                            edges += DossierEdge(
                                fromId = account.id,
                                toId = nodeId,
                                relation = "HOSTS_PUBLIC_IMAGE",
                                evidence = "Public image candidate was discovered on the account source page; this edge does not establish image identity.",
                                relationType = RelationshipType.LINKS_TO,
                                confidence = candidate.comparisonScore?.coerceIn(0f, 1f)
                            )
                        }
                }

            result.visualClusters.take(MAX_CLUSTERS_PER_RESULT).forEach { cluster ->
                val representativeId = cluster.representativeCandidateId
                val representative = candidatesById[representativeId] ?: return@forEach
                val fromNode = imageNodeId(representative.id)
                cluster.memberCandidateIds
                    .asSequence()
                    .filter { it != representativeId }
                    .mapNotNull(candidatesById::get)
                    .take(MAX_CLUSTER_MEMBERS)
                    .forEach { member ->
                        edges += DossierEdge(
                            fromId = fromNode,
                            toId = imageNodeId(member.id),
                            relation = when (cluster.type) {
                                ReverseImageLookupResult.ImageClusterType.ExactContent -> "SAME_IMAGE_CONTENT"
                                ReverseImageLookupResult.ImageClusterType.PerceptualNearDuplicate -> "PERCEPTUAL_NEAR_DUPLICATE"
                            },
                            evidence = "Local whole-image fingerprint/visual comparison cluster ${cluster.id}",
                            relationType = when (cluster.type) {
                                ReverseImageLookupResult.ImageClusterType.ExactContent -> RelationshipType.SAME_IMAGE_AS
                                ReverseImageLookupResult.ImageClusterType.PerceptualNearDuplicate -> RelationshipType.SIMILAR_IMAGE_TO
                            },
                            confidence = listOfNotNull(
                                representative.comparisonScore,
                                member.comparisonScore
                            ).minOrNull()
                        )
                    }
            }
        }

        return graph.copy(
            entities = entities.values.take(MAX_TOTAL_ENTITIES),
            edges = edges
                .distinctBy { "${it.fromId}|${it.toId}|${it.relation}|${it.evidence.orEmpty()}" }
                .take(MAX_TOTAL_EDGES)
        )
    }

    private fun imageNodeId(candidateId: String): String = "media-image:$candidateId"

    private fun samePublicPage(a: String, b: String): Boolean = canonicalUrl(a) == canonicalUrl(b)

    private fun canonicalUrl(raw: String): String = runCatching {
        val uri = URI(raw.trim())
        val scheme = uri.scheme?.lowercase().orEmpty()
        val host = uri.host?.lowercase()?.removePrefix("www.").orEmpty()
        val path = uri.path.orEmpty().trimEnd('/').ifBlank { "/" }
        "$scheme://$host$path"
    }.getOrElse { raw.trim().trimEnd('/').lowercase() }

    private const val MAX_CANDIDATES_PER_RESULT = 80
    private const val MAX_CLUSTERS_PER_RESULT = 40
    private const val MAX_CLUSTER_MEMBERS = 40
    private const val MAX_ACCOUNT_LINKS_PER_IMAGE = 4
    private const val MAX_TOTAL_ENTITIES = 5_000
    private const val MAX_TOTAL_EDGES = 10_000
}
