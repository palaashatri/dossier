package io.dossier.app.domain.case

import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceIdPolicy
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.FindingType
import io.dossier.app.domain.model.ReverseImageLookupResult
import io.dossier.app.domain.model.ReverseImageLookupResult.ImageAccountLinkageBasis
import io.dossier.app.domain.model.RiskLevel
import java.net.URI
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

    /**
     * One persisted, explicit account association observed for a media candidate.
     * The fingerprint is image-content provenance only; it is never an identity key.
     */
    data class MediaAccountLinkageObservation(
        val caseId: String,
        val caseLabel: String,
        val candidateId: String,
        val accountUrl: String,
        val basis: ImageAccountLinkageBasis,
        val sourcePageUrl: String,
        val linkedAtEpochMillis: Long?,
        val evidenceIds: List<String>,
        val fingerprintType: ReverseImageLookupResult.ImageClusterType?,
        val fingerprint: String?
    )

    /**
     * Bounded saved-case history for explicit account/image associations.
     * Entries are grouped only by the account URL plus an exact/perceptual
     * candidate fingerprint. Candidates without a fingerprint remain case-local.
     */
    data class MediaAccountLinkageHistoryEntry(
        val historyKey: String,
        val accountUrl: String,
        val fingerprintType: ReverseImageLookupResult.ImageClusterType?,
        val fingerprint: String?,
        val observations: List<MediaAccountLinkageObservation>
    ) {
        val caseCount: Int
            get() = observations.map(MediaAccountLinkageObservation::caseId).distinct().size
    }

    data class FindingChange(
        val finding: Finding,
        val change: ChangeKind,
        val riskChanged: Boolean = false
    )

    /**
     * A source-scoped comparison of evidence records from two saved cases.
     *
     * This is deliberately not an identity assertion: it only reports that a
     * record with the same semantic kind and canonical source target changed
     * between two local case snapshots. Records without a source URL are not
     * compared because there is no safe context for deciding that two values
     * refer to the same observation.
     */
    data class EvidenceChange(
        val key: String,
        val change: EvidenceChangeKind,
        val before: Evidence? = null,
        val after: Evidence? = null,
        val explanation: String
    ) {
        val historical: Boolean
            get() = before?.historical == true || after?.historical == true

        val sourceUrl: String?
            get() = after?.sourceUrl ?: before?.sourceUrl

        val semanticKind: String
            get() = (after ?: before)?.attributeKind?.name
                ?: (after ?: before)?.kind?.name.orEmpty()
    }

    enum class EvidenceChangeKind {
        ADDED,
        NOT_OBSERVED_IN_LATEST_CASE,
        CHANGED,
        UNCHANGED,
        UNAVAILABLE
    }

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
        val explanation: String,
        /** Exact current evidence record that kept this target observable, when present. */
        val observedEvidenceId: String? = null,
        /** Successful scan that supplied the before/after recheck, when available. */
        val verificationScanId: String? = null
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
        val remediationVerification: List<RemediationVerification> = emptyList(),
        /** Bounded, source-scoped comparison of persisted evidence records. */
        val evidenceChanges: List<EvidenceChange> = emptyList()
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
            remediationVerification = verifyRemediation(before, after, afterMap.keys),
            evidenceChanges = compareEvidence(before, after)
        )
    }

    /**
     * Compare the latest source-scoped evidence observation in each case.
     *
     * Archive captures and direct-provider observations are intentionally kept
     * in the same result but retain their historical flag and full evidence
     * records. An explicit Unavailable record is surfaced as UNAVAILABLE rather
     * than being mistaken for a removal. A missing record is described as not
     * observed in the latest case; it is never presented as proof of deletion.
     */
    private fun compareEvidence(before: DossierCase, after: DossierCase): List<EvidenceChange> {
        val left = indexEvidence(before.evidenceRecords)
        val right = indexEvidence(after.evidenceRecords)
        val keys = (left.keys + right.keys).distinct().sorted().take(MAX_EVIDENCE_KEYS)

        return keys.mapNotNull { key ->
            val previous = left[key]
            val current = right[key]
            when {
                current?.state == EvidenceState.Unavailable -> EvidenceChange(
                    key = key,
                    change = EvidenceChangeKind.UNAVAILABLE,
                    before = previous,
                    after = current,
                    explanation = "The latest case recorded this source as unavailable; no historical or current observation is asserted."
                )

                previous == null && current != null -> EvidenceChange(
                    key = key,
                    change = EvidenceChangeKind.ADDED,
                    after = current,
                    explanation = "This source-scoped observation was recorded in the latest case."
                )

                previous != null && current == null -> EvidenceChange(
                    key = key,
                    change = EvidenceChangeKind.NOT_OBSERVED_IN_LATEST_CASE,
                    before = previous,
                    explanation = "The latest case did not observe this source-scoped record; this is not proof that the source or archived copy was removed."
                )

                previous != null && current != null -> {
                    val changed = comparableValue(previous) != comparableValue(current) ||
                        previous.state != current.state ||
                        previous.historical != current.historical
                    EvidenceChange(
                        key = key,
                        change = if (changed) EvidenceChangeKind.CHANGED else EvidenceChangeKind.UNCHANGED,
                        before = previous,
                        after = current,
                        explanation = if (changed) {
                            "The source-scoped observation changed between saved cases; review both evidence records and their timestamps."
                        } else {
                            "The source-scoped observation is unchanged between saved cases."
                        }
                    )
                }

                else -> null
            }
        }.sortedWith(
            compareBy<EvidenceChange> { it.change == EvidenceChangeKind.UNCHANGED }
                .thenBy { it.semanticKind }
                .thenBy { it.key }
        )
    }

    private fun indexEvidence(records: List<Evidence>): Map<String, Evidence> = records
        .asSequence()
        .filter { it.state != EvidenceState.Rejected }
        .mapNotNull { evidence ->
            val key = evidenceComparisonKey(evidence) ?: return@mapNotNull null
            key to evidence
        }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, values) ->
            values.sortedWith(
                compareByDescending<Evidence> { it.observedAtEpochMillis ?: Long.MIN_VALUE }
                    .thenByDescending { it.retrievedAtEpochMillis ?: Long.MIN_VALUE }
                    .thenByDescending(Evidence::id)
            ).first()
        }

    private fun evidenceComparisonKey(evidence: Evidence): String? {
        val rawSource = evidence.sourceUrl?.trim()?.takeIf(String::isNotBlank) ?: return null
        val source = canonicalSourceTarget(rawSource).takeIf(String::isNotBlank) ?: return null
        val semanticKind = evidence.attributeKind?.name ?: evidence.kind.name
        return "$semanticKind|$source"
    }

    private fun comparableValue(evidence: Evidence): String = evidence.value
        .trim()
        .replace(Regex("\\s+"), " ")
        .lowercase(Locale.ROOT)

    /** Canonicalizes archive replay URLs without discarding source provenance. */
    private fun canonicalSourceTarget(raw: String): String = runCatching {
        val value = raw.trim().substringBefore('#')
        val archiveMarker = value.indexOf("web.archive.org/web/", ignoreCase = true)
        val target = if (archiveMarker >= 0) {
            value.substring(archiveMarker + "web.archive.org/web/".length)
                .substringAfter("_/", "")
                .ifBlank { value.substring(archiveMarker + "web.archive.org/web/".length).substringAfter('/', "") }
        } else {
            value
        }
        val uri = URI(target)
        val host = uri.host?.removePrefix("www.")?.lowercase(Locale.ROOT).orEmpty()
        val path = uri.path.orEmpty().ifBlank { "/" }.trimEnd('/').ifBlank { "/" }
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        if (host.isBlank()) target.lowercase(Locale.ROOT).trimEnd('/') else "$host$path$query"
    }.getOrDefault(raw.trim().lowercase(Locale.ROOT).trimEnd('/'))

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

    /**
     * Returns explicit account-linkage history without deriving ownership from
     * image similarity. A repeated fingerprint only groups provenance reviews;
     * it does not merge accounts or assert that the same person is depicted.
     */
    fun mediaAccountLinkageHistory(
        cases: List<DossierCase>
    ): List<MediaAccountLinkageHistoryEntry> {
        val groups = linkedMapOf<String, MutableAccountHistoryGroup>()
        val seenObservations = hashSetOf<String>()

        cases.takeLast(MAX_MEDIA_HISTORY_CASES).forEach { case ->
            case.mediaIntelligence.imageResults
                .takeLast(MAX_IMAGE_RESULTS_PER_CASE)
                .forEach { result ->
                    result.visualCandidates
                        .asSequence()
                        .filter { candidate -> candidate.id.isNotBlank() }
                        .distinctBy(ReverseImageLookupResult.ImageCandidateProvenance::id)
                        .take(MAX_CANDIDATES_PER_RESULT)
                        .forEach { candidate ->
                            val fingerprint = mediaLinkageFingerprint(candidate)
                            candidate.accountLinkages
                                .asSequence()
                                .filter { linkage -> linkage.accountUrl.isNotBlank() }
                                .distinctBy { linkage ->
                                    "${linkage.basis.name}|${canonicalMediaAccountTarget(linkage.accountUrl).orEmpty()}"
                                }
                                .take(MAX_ACCOUNT_LINKAGES_PER_CANDIDATE)
                                .forEach { linkage ->
                                    val accountTarget = canonicalMediaAccountTarget(linkage.accountUrl)
                                        ?: return@forEach
                                    val observationKey = listOf(
                                        case.caseId,
                                        candidate.id,
                                        linkage.basis.name,
                                        accountTarget
                                    ).joinToString("|")
                                    if (!seenObservations.add(observationKey)) return@forEach

                                    val historyKey = if (fingerprint != null) {
                                        "account:$accountTarget|${fingerprint.first.name}:${fingerprint.second}"
                                    } else {
                                        "case:${case.caseId}|candidate:${candidate.id}|account:$accountTarget"
                                    }
                                    val group = groups.getOrPut(historyKey) {
                                        MutableAccountHistoryGroup(
                                            accountUrl = linkage.accountUrl.trim(),
                                            fingerprintType = fingerprint?.first,
                                            fingerprint = fingerprint?.second
                                        )
                                    }
                                    if (group.observations.size < MAX_OBSERVATIONS_PER_HISTORY_ENTRY) {
                                        group.observations += MediaAccountLinkageObservation(
                                            caseId = case.caseId,
                                            caseLabel = case.label,
                                            candidateId = candidate.id,
                                            accountUrl = linkage.accountUrl.trim(),
                                            basis = linkage.basis,
                                            sourcePageUrl = candidate.sourcePageUrl,
                                            linkedAtEpochMillis = linkage.linkedAtEpochMillis,
                                            evidenceIds = linkage.evidenceIds
                                                .map(String::trim)
                                                .filter(String::isNotBlank)
                                                .distinct()
                                                .take(MAX_EVIDENCE_IDS_PER_LINKAGE),
                                            fingerprintType = fingerprint?.first,
                                            fingerprint = fingerprint?.second
                                        )
                                    }
                                }
                        }
                }
        }

        return groups.entries
            .take(MAX_HISTORY_ENTRIES)
            .map { (historyKey, group) ->
                MediaAccountLinkageHistoryEntry(
                    historyKey = historyKey,
                    accountUrl = group.accountUrl,
                    fingerprintType = group.fingerprintType,
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

    private data class MutableAccountHistoryGroup(
        val accountUrl: String,
        val fingerprintType: ReverseImageLookupResult.ImageClusterType?,
        val fingerprint: String?,
        val observations: MutableList<MediaAccountLinkageObservation> = mutableListOf()
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

    private fun mediaLinkageFingerprint(
        candidate: ReverseImageLookupResult.ImageCandidateProvenance
    ): Pair<ReverseImageLookupResult.ImageClusterType, String>? {
        val exact = candidate.contentSha256
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() && it.length <= MAX_FINGERPRINT_LENGTH }
        if (exact != null) {
            return ReverseImageLookupResult.ImageClusterType.ExactContent to exact
        }
        val perceptual = candidate.perceptualHashHex
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() && it.length <= MAX_FINGERPRINT_LENGTH }
        return perceptual?.let {
            ReverseImageLookupResult.ImageClusterType.PerceptualNearDuplicate to it
        }
    }

    private fun canonicalMediaAccountTarget(raw: String): String? = runCatching {
        val uri = URI(raw.trim())
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
            ?.takeIf { it == "http" || it == "https" }
            ?: return null
        val host = uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.")
            ?.takeIf(String::isNotBlank)
            ?: return null
        val path = uri.path.orEmpty().trimEnd('/').ifBlank { "/" }
        "$scheme://$host$path"
    }.getOrNull()

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
        val beforeScanIds = before.scanHistory.mapTo(hashSetOf(), CaseScanHistoryEntry::scanId)
        val latestNewScan = after.scanHistory
            .asSequence()
            .filter { scan -> scan.scanId !in beforeScanIds }
            .maxByOrNull { it.startedAtUtc }
        val newSuccessfulScan = latestNewScan?.takeIf { scan ->
            scan.completedAtUtc != null && !scan.failed && !scan.cancelled
        }
        val hasNewScan = latestNewScan != null
        return before.remediationRecords.map { previous ->
            val current = afterById[previous.remediationId]
            val observedEvidence = findObservedEvidence(previous, after)
            val stillObserved = previous.findingKey in latestFindingKeys || observedEvidence != null
            val scanId = if (previous.status == RemediationStatus.Completed) {
                current?.verifiedByScanId?.takeIf(String::isNotBlank) ?: newSuccessfulScan?.scanId
            } else {
                null
            }
            val incompleteNewScan = hasNewScan && newSuccessfulScan == null
            val state = when {
                current != null && current.status != previous.status -> RemediationVerificationState.StatusChanged
                previous.status == RemediationStatus.Completed && stillObserved -> RemediationVerificationState.StillObserved
                previous.status == RemediationStatus.Completed && incompleteNewScan -> RemediationVerificationState.NotRechecked
                previous.status == RemediationStatus.Completed && !stillObserved -> RemediationVerificationState.NotObservedInLatestScan
                else -> RemediationVerificationState.NotRechecked
            }
            val explanation = when (state) {
                RemediationVerificationState.StatusChanged ->
                    "Remediation status changed from ${previous.status} to ${current?.status}."
                RemediationVerificationState.StillObserved ->
                    "The current assessment still observed evidence matching this finding; remediation is not verified." +
                        (scanId?.let { " Rechecked by scan $it." } ?: "")
                RemediationVerificationState.NotRechecked ->
                    if (incompleteNewScan) {
                        "The newer scan did not complete successfully; remediation was not rechecked."
                    } else {
                        "No conclusive before/after verification is available for this remediation record."
                    }
                RemediationVerificationState.NotObservedInLatestScan ->
                    "The latest assessment did not observe this finding" +
                        (scanId?.let { " (scan $it)" } ?: "") +
                        ". This is not proof of global deletion; indexes, caches or archives may still retain it."
            }
            RemediationVerification(
                remediationId = previous.remediationId,
                findingKey = previous.findingKey,
                beforeStatus = previous.status,
                afterStatus = current?.status,
                state = state,
                explanation = explanation,
                observedEvidenceId = observedEvidence?.id,
                verificationScanId = scanId
            )
        }
    }

    /**
     * Match a prior remediation target to a current observation only through
     * an explicit evidence ID or the full finding key (type, value, source).
     * A shared username/value without the source/type is never enough.
     */
    private fun findObservedEvidence(
        remediation: RemediationRecord,
        after: DossierCase
    ): Evidence? {
        val currentEvidence = after.evidenceRecords
            .asSequence()
            .filter(::isCurrentUsableEvidence)
            .take(MAX_REMEDIATION_EVIDENCE_RECORDS)
            .toList()

        remediation.evidenceId?.takeIf(String::isNotBlank)?.let { targetId ->
            val migrated = EvidenceIdPolicy.migrate(targetId)
            currentEvidence.firstOrNull { evidence ->
                EvidenceIdPolicy.migrate(evidence.id) == migrated
            }?.let { return it }
        }

        val parsed = parseFindingKey(remediation.findingKey) ?: return null
        val expectedKind = findingTypeToEvidenceKind(parsed.typeName) ?: return null
        return currentEvidence.firstOrNull { evidence ->
            evidence.kind == expectedKind &&
                comparable(evidence.value) == comparable(parsed.value) &&
                sourceMatches(evidence.sourceUrl, parsed.sourceUrl)
        }
    }

    private data class ParsedFindingKey(
        val typeName: String,
        val value: String,
        val sourceUrl: String
    )

    private fun parseFindingKey(value: String): ParsedFindingKey? {
        val parts = value.split('|', limit = 3)
        if (parts.size != 3 || parts[0].isBlank() || parts[1].isBlank()) return null
        return ParsedFindingKey(
            typeName = parts[0].trim(),
            value = parts[1].trim(),
            sourceUrl = parts[2].trim()
        )
    }

    private fun findingTypeToEvidenceKind(typeName: String): EvidenceKind? {
        val findingType = FindingType.entries.firstOrNull { it.name.equals(typeName, ignoreCase = true) }
            ?: return null
        return when (findingType) {
            FindingType.Email -> EvidenceKind.Email
            FindingType.Phone -> EvidenceKind.Phone
            FindingType.Address -> EvidenceKind.Address
            FindingType.Location -> EvidenceKind.Location
            FindingType.Username -> EvidenceKind.Username
            FindingType.Profile -> EvidenceKind.Profile
            FindingType.Organization -> EvidenceKind.Organization
            FindingType.UsernameReuse -> EvidenceKind.UsernameReuse
            FindingType.PlausibleProfileMatch -> EvidenceKind.PlausibleProfileMatch
            FindingType.PublicSearchEvidence -> EvidenceKind.PublicSearchEvidence
            FindingType.PublicImageEvidence -> EvidenceKind.PublicImageEvidence
            FindingType.ImageConsistency -> EvidenceKind.ImageConsistency
            FindingType.SensitiveSnippet -> EvidenceKind.SensitiveSnippet
        }
    }

    private fun isCurrentUsableEvidence(evidence: Evidence): Boolean =
        !evidence.historical &&
            evidence.state != EvidenceState.Rejected &&
            evidence.state != EvidenceState.Unavailable

    private fun sourceMatches(current: String?, target: String): Boolean {
        if (target.isBlank()) return current.isNullOrBlank()
        val currentValue = current?.takeIf(String::isNotBlank) ?: return false
        return canonicalSourceTarget(currentValue) == canonicalSourceTarget(target)
    }

    private fun comparable(value: String): String = value
        .trim()
        .replace(Regex("\\s+"), " ")
        .lowercase(Locale.ROOT)

    private fun key(finding: Finding): String =
        "${finding.type.name}|${finding.value}|${finding.sourceUrl.orEmpty()}"

    private fun riskWeight(risk: RiskLevel): Int = when (risk) {
        RiskLevel.Low -> 25
        RiskLevel.Medium -> 50
        RiskLevel.High -> 80
        RiskLevel.Critical -> 100
    }

    private companion object {
        const val MAX_EVIDENCE_KEYS = 256
        const val MAX_MEDIA_HISTORY_CASES = 24
        const val MAX_IMAGE_RESULTS_PER_CASE = 12
        const val MAX_CANDIDATES_PER_RESULT = 100
        const val MAX_CLUSTERS_PER_RESULT = 50
        const val MAX_CLUSTER_MEMBERS = 100
        const val MAX_OBSERVATIONS_PER_HISTORY_ENTRY = 24
        const val MAX_HISTORY_ENTRIES = 128
        const val MIN_CLUSTER_MEMBERS = 2
        const val MAX_FINGERPRINT_LENGTH = 256
        const val MAX_ACCOUNT_LINKAGES_PER_CANDIDATE = 4
        const val MAX_EVIDENCE_IDS_PER_LINKAGE = 8
        const val MAX_REMEDIATION_EVIDENCE_RECORDS = 256
    }
}
