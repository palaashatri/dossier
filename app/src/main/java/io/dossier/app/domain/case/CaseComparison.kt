package io.dossier.app.domain.case

import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.ReverseImageLookupResult
import io.dossier.app.domain.model.RiskLevel
import java.util.Locale

/** Pure before/after comparison for encrypted saved cases. */
class CaseComparison {

    /**
     * A bounded, provenance-preserving view of one public-image duplicate cluster in a
     * saved case. These records describe whole-image evidence only; they do not assert
     * that the pages belong to the same person or account owner.
     */
    data class MediaClusterMember(
        val candidateId: String,
        val title: String,
        val imageUrl: String,
        val sourcePageUrl: String,
        val source: String,
        val retrievedAtEpochMillis: Long?,
        val contentSha256: String?,
        val perceptualHashHex: String?,
        val state: ReverseImageLookupResult.ImageCandidateState
    )

    data class MediaClusterObservation(
        val caseId: String,
        val caseLabel: String,
        val clusterId: String,
        val type: ReverseImageLookupResult.ImageClusterType,
        val representativeCandidateId: String,
        val members: List<MediaClusterMember>
    )

    /**
     * A history group keyed by a shared whole-image fingerprint when one is available.
     * A null fingerprint means the observation remains case-local rather than being
     * guessed to match another case.
     */
    data class MediaClusterHistoryEntry(
        val historyKey: String,
        val type: ReverseImageLookupResult.ImageClusterType,
        val fingerprint: String?,
        val observations: List<MediaClusterObservation>
    ) {
        val caseCount: Int
            get() = observations.map(MediaClusterObservation::caseId).distinct().size

        val memberCount: Int
            get() = observations.sumOf { observation -> observation.members.size }
    }

    data class FindingChange(
        val finding: Finding,
        val change: ChangeKind,
        val riskChanged: Boolean = false
    )

    enum class ChangeKind { ADDED, REMOVED, CHANGED, UNCHANGED }

    enum class RemediationVerificationState {
        NotRechecked,
        StillObserved,
        NotObservedInLatestScan,
        StatusChanged
    }

    data class RemediationVerification(
        val remediationId: String,
        val findingKey: String,
        val beforeStatus: RemediationStatus,
        val afterStatus: RemediationStatus?,
        val state: RemediationVerificationState,
        val explanation: String
    )

    data class MediaDiff(
        val exactContentReused: Int = 0,
        val perceptualFingerprintsReused: Int = 0,
        val clustersAdded: Int = 0,
        val clustersRemoved: Int = 0,
        val sourcePagesAdded: Int = 0,
        val sourcePagesRemoved: Int = 0
    )

    data class CaseDiff(
        val added: List<Finding>,
        val removed: List<Finding>,
        val changed: List<FindingChange>,
        val unchanged: List<Finding>,
        val profilesAdded: Int,
        val profilesRemoved: Int,
        val breachesAdded: Int,
        val breachesRemoved: Int,
        val riskDelta: Int,
        val exposureDelta: Int,
        val media: MediaDiff = MediaDiff(),
        val remediationVerification: List<RemediationVerification> = emptyList()
    )

    fun compare(before: DossierCase, after: DossierCase): CaseDiff {
        val beforeMap = before.findings.associateBy(::key)
        val afterMap = after.findings.associateBy(::key)

        val added = after.findings.filter { key(it) !in beforeMap }
        val removed = before.findings.filter { key(it) !in afterMap }

        val changed = mutableListOf<FindingChange>()
        val unchanged = mutableListOf<Finding>()
        afterMap.forEach { (findingKey, current) ->
            val previous = beforeMap[findingKey] ?: return@forEach
            if (
                previous.risk != current.risk ||
                previous.confidence != current.confidence ||
                previous.evidenceSnippet != current.evidenceSnippet
            ) {
                changed += FindingChange(
                    finding = current,
                    change = ChangeKind.CHANGED,
                    riskChanged = previous.risk != current.risk
                )
            } else {
                unchanged += current
            }
        }

        val beforeProfiles = before.profileResults.map { it.candidate.url }.toSet()
        val afterProfiles = after.profileResults.map { it.candidate.url }.toSet()
        val beforeBreaches = before.breachDigests.map { it.email }.toSet()
        val afterBreaches = after.breachDigests.map { it.email }.toSet()

        val riskDelta = riskWeight(after.riskLevel) - riskWeight(before.riskLevel)
        val exposureDelta = (after.exposure?.overall ?: 0) - (before.exposure?.overall ?: 0)

        return CaseDiff(
            added = added,
            removed = removed,
            changed = changed,
            unchanged = unchanged,
            profilesAdded = (afterProfiles - beforeProfiles).size,
            profilesRemoved = (beforeProfiles - afterProfiles).size,
            breachesAdded = (afterBreaches - beforeBreaches).size,
            breachesRemoved = (beforeBreaches - afterBreaches).size,
            riskDelta = riskDelta,
            exposureDelta = exposureDelta,
            media = compareMedia(before, after),
            remediationVerification = verifyRemediation(before, after, afterMap.keys)
        )
    }

    /**
     * Returns bounded, deterministic cluster history for saved-case review.
     *
     * Exact-content groups use SHA-256 values and near-duplicate groups use the
     * available perceptual hashes. Clusters without a usable fingerprint are kept as
     * case-local observations and are never merged by cluster ID, because cluster IDs
     * are derived from a scan's candidate IDs. Every member is retained with its source
     * provider/page and retrieval timestamp for inspection.
     */
    fun mediaClusterHistory(cases: List<DossierCase>): List<MediaClusterHistoryEntry> {
        val groups = linkedMapOf<String, MutableHistoryGroup>()
        val seenObservations = hashSetOf<String>()

        cases.takeLast(MAX_MEDIA_HISTORY_CASES).forEach { case ->
            case.mediaIntelligence.imageResults
                .takeLast(MAX_IMAGE_RESULTS_PER_CASE)
                .forEach { result ->
                    val candidatesById = result.visualCandidates
                        .asSequence()
                        .filter { candidate -> candidate.id.isNotBlank() }
                        .distinctBy(ReverseImageLookupResult.ImageCandidateProvenance::id)
                        .take(MAX_CANDIDATES_PER_RESULT)
                        .associateBy(ReverseImageLookupResult.ImageCandidateProvenance::id)

                    result.visualClusters
                        .asSequence()
                        .filter { cluster ->
                            cluster.id.isNotBlank() &&
                                cluster.representativeCandidateId in candidatesById
                        }
                        .distinctBy { cluster -> "${cluster.type.name}|${cluster.id}" }
                        .take(MAX_CLUSTERS_PER_RESULT)
                        .forEach { cluster ->
                            val observationKey =
                                "${case.caseId}|${cluster.type.name}|${cluster.id}"
                            if (!seenObservations.add(observationKey)) return@forEach

                            val memberIds = cluster.memberCandidateIds
                                .asSequence()
                                .distinct()
                                .mapNotNull(candidatesById::get)
                                .take(MAX_CLUSTER_MEMBERS)
                                .toList()
                            if (memberIds.size < MIN_CLUSTER_MEMBERS ||
                                cluster.representativeCandidateId !in memberIds.map { it.id }
                            ) {
                                return@forEach
                            }

                            val observation = MediaClusterObservation(
                                caseId = case.caseId,
                                caseLabel = case.label,
                                clusterId = cluster.id,
                                type = cluster.type,
                                representativeCandidateId = cluster.representativeCandidateId,
                                members = memberIds.map(::toMediaClusterMember)
                            )
                            val fingerprint = clusterFingerprint(cluster.type, memberIds)
                            val historyKey = fingerprint?.let {
                                "${cluster.type.name}|fingerprint:$it"
                            } ?: "${cluster.type.name}|case:${case.caseId}|cluster:${cluster.id}"
                            val group = groups.getOrPut(historyKey) {
                                MutableHistoryGroup(
                                    type = cluster.type,
                                    fingerprint = fingerprint
                                )
                            }
                            if (group.observations.size < MAX_OBSERVATIONS_PER_HISTORY_ENTRY) {
                                group.observations += observation
                            }
                        }
                }
        }

        return groups.entries
            .take(MAX_HISTORY_ENTRIES)
            .map { (historyKey, group) ->
                MediaClusterHistoryEntry(
                    historyKey = historyKey,
                    type = group.type,
                    fingerprint = group.fingerprint,
                    observations = group.observations.toList()
                )
            }
    }

    private data class MutableHistoryGroup(
        val type: ReverseImageLookupResult.ImageClusterType,
        val fingerprint: String?,
        val observations: MutableList<MediaClusterObservation> = mutableListOf()
    )

    private fun toMediaClusterMember(
        candidate: ReverseImageLookupResult.ImageCandidateProvenance
    ): MediaClusterMember = MediaClusterMember(
        candidateId = candidate.id,
        title = candidate.title,
        imageUrl = candidate.imageUrl,
        sourcePageUrl = candidate.sourcePageUrl,
        source = candidate.source,
        retrievedAtEpochMillis = candidate.retrievedAtEpochMillis,
        contentSha256 = candidate.contentSha256,
        perceptualHashHex = candidate.perceptualHashHex,
        state = candidate.state
    )

    private fun clusterFingerprint(
        type: ReverseImageLookupResult.ImageClusterType,
        members: List<ReverseImageLookupResult.ImageCandidateProvenance>
    ): String? {
        val values = when (type) {
            ReverseImageLookupResult.ImageClusterType.ExactContent ->
                members.mapNotNull { it.contentSha256 }
            ReverseImageLookupResult.ImageClusterType.PerceptualNearDuplicate ->
                members.mapNotNull { it.perceptualHashHex }
        }
        return values
            .asSequence()
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotBlank() && it.length <= MAX_FINGERPRINT_LENGTH }
            .distinct()
            .sorted()
            .joinToString(",")
            .takeIf(String::isNotBlank)
    }

    private data class MediaState(
        val contentHashes: Set<String>,
        val perceptualHashes: Set<String>,
        val clusterSignatures: Set<String>,
        val sourcePages: Set<String>
    )

    private fun compareMedia(before: DossierCase, after: DossierCase): MediaDiff {
        val left = mediaState(before)
        val right = mediaState(after)
        return MediaDiff(
            exactContentReused = left.contentHashes.intersect(right.contentHashes).size,
            perceptualFingerprintsReused = left.perceptualHashes.intersect(right.perceptualHashes).size,
            clustersAdded = (right.clusterSignatures - left.clusterSignatures).size,
            clustersRemoved = (left.clusterSignatures - right.clusterSignatures).size,
            sourcePagesAdded = (right.sourcePages - left.sourcePages).size,
            sourcePagesRemoved = (left.sourcePages - right.sourcePages).size
        )
    }

    private fun mediaState(case: DossierCase): MediaState {
        val contentHashes = linkedSetOf<String>()
        val perceptualHashes = linkedSetOf<String>()
        val clusterSignatures = linkedSetOf<String>()
        val sourcePages = linkedSetOf<String>()

        case.mediaIntelligence.imageResults.forEach { result ->
            val byId = result.visualCandidates.associateBy { it.id }
            result.visualCandidates.forEach { candidate ->
                candidate.contentSha256?.lowercase()?.takeIf(String::isNotBlank)?.let(contentHashes::add)
                candidate.perceptualHashHex?.lowercase()?.takeIf(String::isNotBlank)?.let(perceptualHashes::add)
                candidate.sourcePageUrl.trim().lowercase().takeIf(String::isNotBlank)?.let(sourcePages::add)
            }
            result.visualClusters.forEach { cluster ->
                val signatures = cluster.memberCandidateIds.mapNotNull(byId::get)
                    .mapNotNull { candidate ->
                        when (cluster.type) {
                            ReverseImageLookupResult.ImageClusterType.ExactContent ->
                                candidate.contentSha256?.lowercase()?.takeIf(String::isNotBlank)
                            ReverseImageLookupResult.ImageClusterType.PerceptualNearDuplicate ->
                                candidate.perceptualHashHex?.lowercase()?.takeIf(String::isNotBlank)
                        } ?: candidate.imageUrl.trim().lowercase().takeIf(String::isNotBlank)
                    }
                    .distinct()
                    .sorted()
                if (signatures.isNotEmpty()) {
                    clusterSignatures += "${cluster.type.name}:${signatures.joinToString("|")}"
                }
            }
        }
        return MediaState(contentHashes, perceptualHashes, clusterSignatures, sourcePages)
    }

    private fun verifyRemediation(
        before: DossierCase,
        after: DossierCase,
        latestFindingKeys: Set<String>
    ): List<RemediationVerification> {
        val afterById = after.remediationRecords.associateBy(RemediationRecord::remediationId)
        return before.remediationRecords.map { previous ->
            val current = afterById[previous.remediationId]
            val stillObserved = previous.findingKey in latestFindingKeys
            val state = when {
                current != null && current.status != previous.status -> RemediationVerificationState.StatusChanged
                previous.status == RemediationStatus.Completed && stillObserved -> RemediationVerificationState.StillObserved
                previous.status == RemediationStatus.Completed && !stillObserved -> RemediationVerificationState.NotObservedInLatestScan
                else -> RemediationVerificationState.NotRechecked
            }
            val explanation = when (state) {
                RemediationVerificationState.StatusChanged ->
                    "Remediation status changed from ${previous.status} to ${current?.status}."
                RemediationVerificationState.StillObserved ->
                    "The current assessment still observed evidence matching this finding; remediation is not verified."
                RemediationVerificationState.NotObservedInLatestScan ->
                    "The latest assessment did not observe this finding. This is not proof of global deletion; indexes, caches or archives may still retain it."
                RemediationVerificationState.NotRechecked ->
                    "No conclusive before/after verification is available for this remediation record."
            }
            RemediationVerification(
                remediationId = previous.remediationId,
                findingKey = previous.findingKey,
                beforeStatus = previous.status,
                afterStatus = current?.status,
                state = state,
                explanation = explanation
            )
        }
    }

    private fun key(finding: Finding): String =
        "${finding.type.name}|${finding.value}|${finding.sourceUrl.orEmpty()}"

    private fun riskWeight(risk: RiskLevel): Int = when (risk) {
        RiskLevel.Low -> 25
        RiskLevel.Medium -> 50
        RiskLevel.High -> 80
        RiskLevel.Critical -> 100
    }

    private companion object {
        const val MAX_MEDIA_HISTORY_CASES = 24
        const val MAX_IMAGE_RESULTS_PER_CASE = 12
        const val MAX_CANDIDATES_PER_RESULT = 100
        const val MAX_CLUSTERS_PER_RESULT = 50
        const val MAX_CLUSTER_MEMBERS = 100
        const val MAX_OBSERVATIONS_PER_HISTORY_ENTRY = 24
        const val MAX_HISTORY_ENTRIES = 128
        const val MIN_CLUSTER_MEMBERS = 2
        const val MAX_FINGERPRINT_LENGTH = 256
    }
}
