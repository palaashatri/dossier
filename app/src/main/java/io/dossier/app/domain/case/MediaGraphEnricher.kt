package io.dossier.app.domain.case

import io.dossier.app.domain.evidence.EvidenceIdPolicy
import io.dossier.app.domain.model.DossierEdge
import io.dossier.app.domain.model.DossierEntity
import io.dossier.app.domain.model.EntityGraph
import io.dossier.app.domain.model.EntityType
import io.dossier.app.domain.model.GraphEntityKind
import io.dossier.app.domain.model.GraphNodeState
import io.dossier.app.domain.model.RelationshipType
import io.dossier.app.domain.model.ReverseImageLookupResult
import io.dossier.app.domain.model.ReverseImageLookupResult.ImageAccountLinkageBasis
import io.dossier.app.domain.place.MediaIntelligenceSnapshot
import java.net.URI

/**
 * Adds inspectable image/repost evidence to a case graph without making identity
 * claims. Matched/clustered candidates and candidates observed on directly
 * verified account pages are promoted to nodes.
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
                        candidate.clusterId != null ||
                        candidate.accountLinkages.isNotEmpty() ||
                        hasConfirmedAccountPage(candidate, graph.entities)
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

                    addExplicitAccountLinks(
                        candidate = candidate,
                        imageNodeId = nodeId,
                        graphEntities = entities,
                        edges = edges
                    )
                    addVerifiedAccountPageLinks(
                        candidate = candidate,
                        imageNodeId = nodeId,
                        graphEntities = entities,
                        edges = edges
                    )
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
            edges = mergeEdges(edges).take(MAX_TOTAL_EDGES)
        )
    }

    /**
     * A directly verified account page is an independent source association,
     * so it can produce an account/image edge even before a reviewer stores an
     * explicit linkage record. The page match—not a visual score—is the basis.
     */
    private fun addVerifiedAccountPageLinks(
        candidate: ReverseImageLookupResult.ImageCandidateProvenance,
        imageNodeId: String,
        graphEntities: Map<String, DossierEntity>,
        edges: MutableList<DossierEdge>
    ) {
        graphEntities.values
            .asSequence()
            .filter { account ->
                (account.kind == GraphEntityKind.Account || account.type == EntityType.Profile) &&
                    account.state == GraphNodeState.Confirmed &&
                    account.sourceUrls.any { source -> samePublicPage(source, candidate.sourcePageUrl) }
            }
            .take(MAX_ACCOUNT_LINKS_PER_IMAGE)
            .forEach { account ->
                edges += DossierEdge(
                    fromId = account.id,
                    toId = imageNodeId,
                    relation = "USES_AVATAR",
                    evidence = "Directly verified account page exactly matches the candidate source page; " +
                        "this records where the public image was observed and does not establish " +
                        "that the image identifies a person.",
                    relationType = RelationshipType.USES_AVATAR,
                    evidenceIds = account.evidenceIds
                        .map(EvidenceIdPolicy::migrate)
                        .filter(String::isNotBlank)
                        .distinct()
                        .take(MAX_EVIDENCE_IDS_PER_LINKAGE),
                    // This is a page-observation edge, not a calibrated identity score.
                    confidence = null
                )
            }
    }

    /**
     * Adds a stronger account/image edge only when an explicit non-visual basis
     * exists. A candidate's match score, exact bytes, or cluster membership is
     * never sufficient to create USES_AVATAR.
     */
    private fun addExplicitAccountLinks(
        candidate: ReverseImageLookupResult.ImageCandidateProvenance,
        imageNodeId: String,
        graphEntities: Map<String, DossierEntity>,
        edges: MutableList<DossierEdge>
    ) {
        candidate.accountLinkages
            .take(MAX_ACCOUNT_LINKAGES_PER_CANDIDATE)
            .forEach { linkage ->
                val account = graphEntities.values.firstOrNull { entity ->
                    (entity.kind == GraphEntityKind.Account || entity.type == EntityType.Profile) &&
                        entity.state != GraphNodeState.Conflicting &&
                        entity.sourceUrls.any { source -> samePublicPage(source, linkage.accountUrl) }
                } ?: return@forEach

                val admissible = when (linkage.basis) {
                    ImageAccountLinkageBasis.VerifiedProfile ->
                        account.state == GraphNodeState.Confirmed &&
                            samePublicPage(candidate.sourcePageUrl, linkage.accountUrl) &&
                            linkage.evidenceIds.isNotEmpty()

                    ImageAccountLinkageBasis.UserReviewed ->
                        linkage.linkedAtEpochMillis != null || linkage.evidenceIds.isNotEmpty()
                }
                if (!admissible) return@forEach

                val evidenceIds = linkage.evidenceIds
                    .map(EvidenceIdPolicy::migrate)
                    .filter(String::isNotBlank)
                    .distinct()
                    .take(MAX_EVIDENCE_IDS_PER_LINKAGE)
                val basisText = when (linkage.basis) {
                    ImageAccountLinkageBasis.VerifiedProfile ->
                        "directly verified account page"
                    ImageAccountLinkageBasis.UserReviewed ->
                        "explicit user review"
                }
                val timestampText = linkage.linkedAtEpochMillis?.let { " at $it" }.orEmpty()
                edges += DossierEdge(
                    fromId = account.id,
                    toId = imageNodeId,
                    relation = "USES_AVATAR",
                    evidence = "Account/image association from $basisText$timestampText; " +
                        "this does not establish that the image identifies a person.",
                    relationType = RelationshipType.USES_AVATAR,
                    evidenceIds = evidenceIds,
                    // Explicit linkage records the reviewed/page association; it is not
                    // a probability that the image identifies the account owner.
                    confidence = null
                )
        }
    }

    private fun hasConfirmedAccountPage(
        candidate: ReverseImageLookupResult.ImageCandidateProvenance,
        graphEntities: List<DossierEntity>
    ): Boolean = graphEntities.any { account ->
        (account.kind == GraphEntityKind.Account || account.type == EntityType.Profile) &&
            account.state == GraphNodeState.Confirmed &&
            account.sourceUrls.any { source -> samePublicPage(source, candidate.sourcePageUrl) }
    }

    private fun mergeEdges(edges: List<DossierEdge>): List<DossierEdge> = edges
        .groupBy { edge -> "${edge.fromId}|${edge.toId}|${edge.relation}" }
        .values
        .map { duplicates ->
            val first = duplicates.first()
            first.copy(
                evidence = duplicates.asSequence()
                    .mapNotNull { it.evidence?.takeIf(String::isNotBlank) }
                    .firstOrNull()
                    ?: first.evidence,
                evidenceIds = duplicates
                    .flatMap(DossierEdge::evidenceIds)
                    .map(EvidenceIdPolicy::migrate)
                    .filter(String::isNotBlank)
                    .distinct()
                    .take(MAX_EVIDENCE_IDS_PER_EDGE),
                contradictingEvidenceIds = duplicates
                    .flatMap(DossierEdge::contradictingEvidenceIds)
                    .map(EvidenceIdPolicy::migrate)
                    .filter(String::isNotBlank)
                    .distinct()
                    .take(MAX_EVIDENCE_IDS_PER_EDGE),
                confidence = duplicates.mapNotNull(DossierEdge::confidence).maxOrNull()
                    ?: first.confidence,
                historical = duplicates.any(DossierEdge::historical)
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
    private const val MAX_ACCOUNT_LINKAGES_PER_CANDIDATE = 4
    private const val MAX_EVIDENCE_IDS_PER_LINKAGE = 8
    private const val MAX_EVIDENCE_IDS_PER_EDGE = 16
    private const val MAX_TOTAL_ENTITIES = 5_000
    private const val MAX_TOTAL_EDGES = 10_000
}
