package io.dossier.app.domain.place

import io.dossier.app.domain.model.ReverseImageLookupResult
import java.net.URI

/**
 * Bounds and repairs persisted reverse-media provenance before it is rehydrated.
 *
 * Media results are evidence, not identity claims. This policy only removes malformed or
 * unbounded references introduced by an old/corrupt case; it never manufactures matches,
 * clusters, or confidence. In particular, a cluster member must refer to a retained candidate
 * from the same image result before it is kept.
 */
internal object MediaIntelligenceSnapshotPolicy {

    fun normalize(snapshot: MediaIntelligenceSnapshot): MediaIntelligenceSnapshot = snapshot.copy(
        imageResults = snapshot.imageResults
            .takeLast(MAX_IMAGE_RESULTS)
            .map(::normalizeImageResult),
        videoResults = snapshot.videoResults.takeLast(MAX_VIDEO_RESULTS)
    )

    private fun normalizeImageResult(
        result: ReverseImageLookupResult
    ): ReverseImageLookupResult {
        val candidates = result.visualCandidates
            .asSequence()
            .filter { it.id.isNotBlank() }
            .distinctBy(ReverseImageLookupResult.ImageCandidateProvenance::id)
            .take(MAX_CANDIDATES_PER_RESULT)
            .toList()
        val candidateIds = candidates.mapTo(hashSetOf(), ReverseImageLookupResult.ImageCandidateProvenance::id)

        val clusters = result.visualClusters
            .asSequence()
            .filter { cluster ->
                cluster.id.isNotBlank() && cluster.representativeCandidateId in candidateIds
            }
            .distinctBy(ReverseImageLookupResult.ImageCluster::id)
            .mapNotNull { cluster ->
                val members = cluster.memberCandidateIds
                    .asSequence()
                    .filter(candidateIds::contains)
                    .distinct()
                    .take(MAX_CLUSTER_MEMBERS)
                    .toList()
                if (members.size < MIN_CLUSTER_MEMBERS ||
                    cluster.representativeCandidateId !in members
                ) {
                    null
                } else {
                    cluster.copy(memberCandidateIds = members)
                }
            }
            .take(MAX_CLUSTERS_PER_RESULT)
            .toList()
        val clusterIds = clusters.mapTo(hashSetOf(), ReverseImageLookupResult.ImageCluster::id)
        val clusterMembers = clusters.associate { cluster ->
            cluster.id to cluster.memberCandidateIds.toHashSet()
        }

        return result.copy(
            visualCandidates = candidates.map { candidate ->
                candidate.copy(
                    clusterId = candidate.clusterId?.takeIf { clusterId ->
                        clusterId in clusterIds && candidate.id in clusterMembers[clusterId].orEmpty()
                    },
                    accountLinkages = normalizeAccountLinkages(candidate.accountLinkages)
                )
            },
            visualMatches = result.visualMatches
                .take(MAX_VISUAL_MATCHES)
                .map { match ->
                match.copy(
                    candidateId = match.candidateId?.takeIf(candidateIds::contains),
                    clusterId = match.clusterId?.takeIf { clusterId ->
                        clusterId in clusterIds &&
                            (match.candidateId == null ||
                                match.candidateId in clusterMembers[clusterId].orEmpty())
                    }
                )
            },
            visualClusters = clusters
        )
    }

    /**
     * Keep explicit account associations bounded and canonical without creating
     * any new association. Account URLs are identifiers, so fragments and query
     * parameters are discarded for deterministic saved-case matching.
     */
    private fun normalizeAccountLinkages(
        linkages: List<ReverseImageLookupResult.ImageAccountLinkage>
    ): List<ReverseImageLookupResult.ImageAccountLinkage> = linkages
        .asSequence()
        .mapNotNull { linkage ->
            val accountUrl = canonicalAccountUrl(linkage.accountUrl) ?: return@mapNotNull null
            val evidenceIds = linkage.evidenceIds
                .asSequence()
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
                .take(MAX_EVIDENCE_IDS_PER_LINKAGE)
                .toList()
            linkage.copy(accountUrl = accountUrl, evidenceIds = evidenceIds)
        }
        .distinctBy { "${it.basis.name}|${it.accountUrl}" }
        .take(MAX_ACCOUNT_LINKAGES_PER_CANDIDATE)
        .toList()

    private fun canonicalAccountUrl(raw: String): String? = runCatching {
        val uri = URI(raw.trim())
        val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" }
            ?: return null
        val host = uri.host?.lowercase()?.removePrefix("www.")?.takeIf(String::isNotBlank)
            ?: return null
        val path = uri.path.orEmpty().trimEnd('/').ifBlank { "/" }
        "$scheme://$host$path"
    }.getOrNull()

    private const val MAX_IMAGE_RESULTS = 12
    private const val MAX_VIDEO_RESULTS = 6
    private const val MAX_CANDIDATES_PER_RESULT = 100
    private const val MAX_VISUAL_MATCHES = 100
    private const val MAX_CLUSTERS_PER_RESULT = 50
    private const val MAX_CLUSTER_MEMBERS = 100
    private const val MIN_CLUSTER_MEMBERS = 2
    private const val MAX_ACCOUNT_LINKAGES_PER_CANDIDATE = 4
    private const val MAX_EVIDENCE_IDS_PER_LINKAGE = 8
}
