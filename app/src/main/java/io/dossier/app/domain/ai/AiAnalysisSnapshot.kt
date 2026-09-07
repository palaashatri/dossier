package io.dossier.app.domain.ai

import io.dossier.app.domain.case.DossierCase
import io.dossier.app.domain.case.CaseScanHistoryEntry
import io.dossier.app.domain.case.RemediationRecord
import io.dossier.app.domain.case.UserCorrection
import io.dossier.app.domain.case.UserCorrectionEngine
import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.ExposureEngine.ExposureResult
import io.dossier.app.domain.evidence.toEvidence
import io.dossier.app.domain.model.BreachDigest
import io.dossier.app.domain.model.DossierEdge
import io.dossier.app.domain.model.DossierEntity
import io.dossier.app.domain.model.EntityGraph
import io.dossier.app.domain.model.EntityType
import io.dossier.app.domain.model.FaceConsistencyMatch
import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.ProfileScanResult
import io.dossier.app.domain.place.MediaIntelligenceSnapshot
import kotlinx.serialization.Serializable
import io.dossier.app.domain.evidence.EvidenceIdPolicy
import io.dossier.app.domain.model.FindingType
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale

@Serializable
enum class AiRemediationLinkState {
    Effective,
    Excluded,
    Unavailable,
    Unmatched
}

@Serializable
data class AiRemediationLink(
    val record: RemediationRecord,
    val evidenceId: String? = null,
    val effective: Boolean = true,
    val state: AiRemediationLinkState = AiRemediationLinkState.Effective,
    /**
     * True only when [RemediationRecord.verifiedByScanId] resolves to a
     * completed, non-failed, non-cancelled durable scan-history entry.
     * A nonblank ID alone is not evidence that a verification scan ran.
     */
    val verificationScanPresent: Boolean = false
) {
    val remediationRecord: RemediationRecord get() = record
}

/**
 * Deterministic, corrected view of a saved investigation supplied to an AI
 * model. Raw evidence is never mutated or deleted; ignored observations are
 * omitted from this effective snapshot and rejected observations remain marked
 * rejected so a model cannot silently turn a user correction into proof.
 */
@Serializable
data class AiAnalysisSnapshot(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val input: IdentityInput,
    val profileResults: List<ProfileScanResult> = emptyList(),
    val findings: List<Finding> = emptyList(),
    val evidence: List<Evidence> = emptyList(),
    val graph: EntityGraph = EntityGraph(),
    val breachDigests: List<BreachDigest> = emptyList(),
    val faceMatches: List<FaceConsistencyMatch> = emptyList(),
    val exposure: ExposureResult? = null,
    val mediaIntelligence: MediaIntelligenceSnapshot = MediaIntelligenceSnapshot(),
    val scanHistory: List<CaseScanHistoryEntry> = emptyList(),
    val corrections: List<UserCorrection> = emptyList(),
    val remediationRecords: List<RemediationRecord> = emptyList(),
    val excludedEvidenceIds: List<String> = emptyList(),
    val confirmedEntityIds: List<String> = emptyList(),
    val rejectedEntityIds: List<String> = emptyList(),
    val remediationLinks: List<AiRemediationLink> = emptyList()
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) { "Unsupported AI snapshot schema." }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1

        /** Builds the effective model input while preserving correction provenance. */
        fun from(
            input: IdentityInput,
            profileResults: List<ProfileScanResult> = emptyList(),
            findings: List<Finding> = emptyList(),
            evidence: List<Evidence> = emptyList(),
            graph: EntityGraph = EntityGraph(),
            breachDigests: List<BreachDigest> = emptyList(),
            faceMatches: List<FaceConsistencyMatch> = emptyList(),
            exposure: ExposureResult? = null,
            mediaIntelligence: MediaIntelligenceSnapshot = MediaIntelligenceSnapshot(),
            scanHistory: List<CaseScanHistoryEntry> = emptyList(),
            corrections: List<UserCorrection> = emptyList(),
            remediationRecords: List<RemediationRecord> = emptyList()
        ): AiAnalysisSnapshot {
            val orderedCorrections = corrections
                .sortedWith(compareBy<UserCorrection> { it.createdAtUtc }.thenBy { it.correctionId })
            val effective = UserCorrectionEngine.apply(evidence, graph, orderedCorrections)
            val evidenceIdMap = effective.evidence.associate { it.id to normalizeEvidenceId(it.id) }
            fun normalizedEvidenceId(id: String): String = evidenceIdMap[id] ?: normalizeEvidenceId(id)
            // Include ignored records from the raw list as well: UserCorrectionEngine
            // removes them before returning its effective view, but a duplicate
            // projection with another ID must not bypass the same correction.
            val excludedEvidenceKeys = evidence
                .filter { it.id in effective.excludedEvidenceIds }
                .map(::evidenceMatchKey)
                .toSet()
            val excludedProvenanceIds = effective.evidence
                .filter { it.id in effective.excludedEvidenceIds || evidenceMatchKey(it) in excludedEvidenceKeys }
                .map(Evidence::id)
                .toSet() + effective.excludedEvidenceIds
            val rejectedEntities = effective.graph.entities
                .filter { it.id in effective.rejectedEntityIds }
            val rejectedEntityEvidenceIds = rejectedEntities
                .flatMap { it.evidenceIds }
                .toSet()
            // Entity corrections are deliberately provenance-based. Direct edge
            // evidence IDs win; source URLs and type-compatible labels provide a
            // conservative legacy fallback, never a global same-username rule.
            val rejectedEntityEvidenceKeys = effective.evidence
                .filter { item ->
                    item.id in rejectedEntityEvidenceIds || rejectedEntities.any { entity ->
                        rejectedEntityMatches(entity, item)
                    }
                }
                .map(::evidenceMatchKey)
                .toSet()
            val rejectedEntityProvenanceIds = effective.evidence
                .filter { it.id in rejectedEntityEvidenceIds || evidenceMatchKey(it) in rejectedEntityEvidenceKeys }
                .map(Evidence::id)
                .toSet() + rejectedEntityEvidenceIds
            val unusableEvidenceIds = effective.evidence
                .filter {
                    it.state == EvidenceState.Rejected ||
                        it.state == EvidenceState.Unavailable
                }
                .map(Evidence::id)
                .toSet()
            val graphExcludedProvenanceIds = excludedProvenanceIds +
                unusableEvidenceIds
            val normalizedEvidence = effective.evidence.map { item ->
                val rejectedByEntity = item.id in rejectedEntityProvenanceIds || evidenceMatchKey(item) in rejectedEntityEvidenceKeys
                item.copy(
                    id = normalizedEvidenceId(item.id),
                    state = if (rejectedByEntity) EvidenceState.Rejected else item.state
                )
            }
            // Only evidence that survived an explicit correction may support a
            // derived profile or finding. Rejected observations remain in the
            // snapshot as an auditable state, but never keep an exposure claim
            // alive. A missing match is treated as unsupported (fail closed).
            val supportedEvidence = effective.evidence.filterNot { item ->
                    item.id in excludedProvenanceIds ||
                    item.id in rejectedEntityProvenanceIds ||
                    evidenceMatchKey(item) in rejectedEntityEvidenceKeys ||
                    item.state == EvidenceState.Rejected ||
                    item.state == EvidenceState.Unavailable
            }
            val supportedEvidenceKeys = supportedEvidence
                .map(::evidenceMatchKey)
                .toSet()
            val effectiveFindings = findings.filter { finding ->
                evidenceMatchKey(finding.toEvidence()) in supportedEvidenceKeys
            }
            val effectiveProfiles = profileResults.mapNotNull { result ->
                val profileSupported = !result.exists || supportedEvidence.any { item ->
                    item.kind == EvidenceKind.Profile && profileMatches(item, result.candidate.url)
                }
                if (!profileSupported) return@mapNotNull null
                result.copy(
                    findings = result.findings.filter { finding ->
                        evidenceMatchKey(finding.toEvidence()) in supportedEvidenceKeys
                    }
                )
            }

            // Evidence-only nodes/edges are removed once all their provenance
            // was excluded. Subject/seed nodes and evidence-less legacy nodes
            // may remain as isolated context, but an evidence-less relationship
            // cannot support an AI claim unless both endpoints are explicit
            // seeds or the user explicitly corrected an endpoint.
            val normalizedEntities = effective.graph.entities.mapNotNull { entity ->
                val retainedEvidenceIds = entity.evidenceIds
                    .filterNot(graphExcludedProvenanceIds::contains)
                val retain = retainedEvidenceIds.isNotEmpty() ||
                    entity.evidenceIds.isEmpty() ||
                    isExplicitlySuppliedEntity(entity, input)
                if (!retain) {
                    null
                } else {
                    entity.copy(evidenceIds = retainedEvidenceIds.map(::normalizedEvidenceId))
                }
            }
            val retainedEntityIds = normalizedEntities.mapTo(hashSetOf(), DossierEntity::id)
            val normalizedEdges = effective.graph.edges.mapNotNull { edge ->
                if (edge.fromId !in retainedEntityIds || edge.toId !in retainedEntityIds) {
                    return@mapNotNull null
                }
                val fromEntity = normalizedEntities.first { it.id == edge.fromId }
                val toEntity = normalizedEntities.first { it.id == edge.toId }
                val explicitCorrection = edge.fromId in effective.confirmedEntityIds ||
                    edge.fromId in effective.rejectedEntityIds ||
                    edge.toId in effective.confirmedEntityIds ||
                    edge.toId in effective.rejectedEntityIds
                val explicitSeedRelationship = isExplicitlySuppliedEntity(fromEntity, input) &&
                    isExplicitlySuppliedEntity(toEntity, input)
                val retainedEvidenceIds = edge.evidenceIds
                    .filterNot(graphExcludedProvenanceIds::contains)
                val retainedContradictingIds = edge.contradictingEvidenceIds
                    .filterNot(graphExcludedProvenanceIds::contains)
                val provenanceIds = (edge.evidenceIds + edge.contradictingEvidenceIds).distinct()
                val unsupported = when {
                    explicitCorrection -> false
                    provenanceIds.isEmpty() -> !explicitSeedRelationship
                    else -> retainedEvidenceIds.isEmpty() && retainedContradictingIds.isEmpty()
                }
                if (unsupported) {
                    null
                } else {
                    edge.copy(
                        evidenceIds = retainedEvidenceIds.map(::normalizedEvidenceId),
                        contradictingEvidenceIds = retainedContradictingIds.map(::normalizedEvidenceId)
                    )
                }
            }
            val normalizedGraph = effective.graph.copy(
                entities = normalizedEntities,
                edges = normalizedEdges
            )
            val normalizedCorrections = orderedCorrections.map { correction ->
                correction.copy(evidenceId = correction.evidenceId?.let(::normalizedEvidenceId))
            }
            val remediationLinks = resolveRemediationLinks(
                remediationRecords = remediationRecords,
                rawEvidence = evidence,
                effectiveEvidence = effective.evidence,
                normalizedEvidenceId = ::normalizedEvidenceId,
                excludedProvenanceIds = excludedProvenanceIds,
                excludedEvidenceKeys = excludedEvidenceKeys,
                rejectedEntityProvenanceIds = rejectedEntityProvenanceIds,
                rejectedEntityEvidenceKeys = rejectedEntityEvidenceKeys,
                unusableEvidenceIds = unusableEvidenceIds,
                scanHistory = scanHistory
            )
            return AiAnalysisSnapshot(
                input = input,
                profileResults = effectiveProfiles.sortedBy { it.candidate.url.lowercase() },
                findings = effectiveFindings.sortedWith(
                    compareBy<Finding> { it.type.name }
                        .thenBy { it.value }
                        .thenBy { it.sourceUrl.orEmpty() }
                ),
                evidence = normalizedEvidence.sortedBy(Evidence::id),
                graph = normalizedGraph.normalizedForAi(),
                breachDigests = breachDigests.take(MAX_CONTEXT_RECORDS),
                faceMatches = faceMatches.take(MAX_CONTEXT_RECORDS),
                exposure = exposure,
                mediaIntelligence = mediaIntelligence,
                scanHistory = scanHistory.take(MAX_CONTEXT_RECORDS),
                corrections = normalizedCorrections,
                remediationRecords = remediationLinks.map { it.record },
                excludedEvidenceIds = effective.excludedEvidenceIds.map(::normalizedEvidenceId).sorted(),
                confirmedEntityIds = effective.confirmedEntityIds.toList().sorted(),
                rejectedEntityIds = effective.rejectedEntityIds.toList().sorted(),
                remediationLinks = remediationLinks
            )
        }

        fun fromCase(case: DossierCase): AiAnalysisSnapshot = from(
            input = case.input,
            profileResults = case.profileResults,
            findings = case.findings,
            evidence = mergeCaseEvidence(case),
            graph = case.entityGraph,
            breachDigests = case.breachDigests,
            faceMatches = case.faceMatches,
            exposure = case.exposure,
            mediaIntelligence = case.mediaIntelligence,
            scanHistory = case.scanHistory,
            corrections = case.userCorrections,
            remediationRecords = case.remediationRecords
        )

        /**
         * Older cases may persist plugin evidence without the profile/finding
         * projections used by the AI path. Project only those already-stored
         * records into stable evidence IDs; never synthesize values unrelated
         * to the saved projections, and bound the compatibility work.
         */
        private fun mergeCaseEvidence(case: DossierCase): List<Evidence> {
            val stored = case.evidenceRecords
            val projectedProfiles = case.profileResults
                .asSequence()
                .filter { it.exists }
                .take(MAX_PROJECTION_RECORDS)
                .map { result ->
                    val canonical = result.candidate.url
                    Evidence(
                        id = "profile:${stableProfileId(canonical)}",
                        kind = EvidenceKind.Profile,
                        value = canonical,
                        sourceUrl = canonical,
                        snippet = result.verificationStatus,
                        confidence = result.candidate.confidence.coerceIn(0f, 1f),
                        state = if (result.verified) EvidenceState.Verified else EvidenceState.Candidate,
                        reliability = if (result.verified) {
                            io.dossier.app.domain.evidence.EvidenceReliability.DirectPublicProfile
                        } else {
                            io.dossier.app.domain.evidence.EvidenceReliability.SearchEngineCandidate
                        }
                    )
                }
            val projectedFindings = case.findings
                .asSequence()
                .take(MAX_PROJECTION_RECORDS)
                .map(Finding::toEvidence)
            val storedKeys = stored.map(::evidenceMatchKey).toSet()
            val projections = (projectedProfiles + projectedFindings)
                .filterNot { evidenceMatchKey(it) in storedKeys }
                .toList()
            return (stored + projections).distinctBy(Evidence::id)
        }

        private fun EntityGraph.normalizedForAi(): EntityGraph = copy(
            entities = entities.sortedBy { it.id },
            edges = edges.sortedWith(
                compareBy<DossierEdge> { it.fromId }
                    .thenBy { it.toId }
                    .thenBy { it.relation }
            )
        )

        private data class EvidenceMatchKey(
            val kind: EvidenceKind,
            val value: String,
            val sourceUrl: String
        )

        private fun evidenceMatchKey(evidence: Evidence): EvidenceMatchKey = EvidenceMatchKey(
            kind = evidence.kind,
            value = comparable(evidence.value),
            sourceUrl = comparable(evidence.sourceUrl.orEmpty())
        )

        private fun profileMatches(evidence: Evidence, profileUrl: String): Boolean {
            if (evidence.kind != EvidenceKind.Profile) return false
            val target = comparable(profileUrl)
            return comparable(evidence.value) == target || comparable(evidence.sourceUrl.orEmpty()) == target
        }

        private fun rejectedEntityMatches(entity: DossierEntity, evidence: Evidence): Boolean {
            if (evidence.id in entity.evidenceIds) return true
            val sourceMatches = entity.sourceUrls.any { source ->
                val comparableSource = comparable(source)
                comparableSource.isNotBlank() && (
                    comparable(evidence.sourceUrl.orEmpty()) == comparableSource ||
                        comparable(evidence.value) == comparableSource
                    )
            }
            if (sourceMatches) return true
            val compatibleKinds = when (entity.type) {
                EntityType.Email -> setOf(EvidenceKind.Email)
                EntityType.Phone -> setOf(EvidenceKind.Phone)
                // A username label alone is intentionally insufficient: this
                // path must not become a same-username identity shortcut.
                EntityType.Username -> emptySet()
                EntityType.Profile -> setOf(EvidenceKind.Profile, EvidenceKind.PlausibleProfileMatch)
                EntityType.Organization -> setOf(EvidenceKind.Organization)
                EntityType.Location -> setOf(EvidenceKind.Location, EvidenceKind.Address)
                EntityType.Image -> setOf(EvidenceKind.ImageConsistency, EvidenceKind.PublicImageEvidence)
                EntityType.Website -> setOf(EvidenceKind.PublicSearchEvidence, EvidenceKind.PublicImageEvidence)
                EntityType.Person,
                EntityType.Breach -> emptySet()
            }
            val label = comparable(entity.label)
            return label.isNotBlank() && evidence.kind in compatibleKinds && comparable(evidence.value) == label
        }

        private const val MAX_PROJECTION_RECORDS = 2_000
        private const val MAX_CONTEXT_RECORDS = 80

        private fun stableProfileId(value: String): String = MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { "%02x".format(it) }

        private fun comparable(value: String): String = value
            .trim()
            .replace(Regex("\\s+"), " ")
            .lowercase(Locale.US)

        /**
         * A verification ID is only evidence when it points to a later scan
         * that completed successfully. Undated or malformed legacy timestamps
         * fail closed instead of being treated as chronological proof.
         */
        private fun hasLaterCompletedVerificationScan(
            record: RemediationRecord,
            scanHistory: List<CaseScanHistoryEntry>
        ): Boolean {
            val verificationScanId = record.verifiedByScanId?.takeIf(String::isNotBlank) ?: return false
            val remediationUpdatedAt = parseInstant(record.updatedAtUtc) ?: return false
            return scanHistory.any { scan ->
                val startedAt = parseInstant(scan.startedAtUtc)
                val completedAt = scan.completedAtUtc?.let(::parseInstant)
                scan.scanId == verificationScanId &&
                    !scan.failed &&
                    !scan.cancelled &&
                    startedAt != null &&
                    completedAt != null &&
                    startedAt.isAfter(remediationUpdatedAt) &&
                    !completedAt.isBefore(startedAt)
            }
        }

        private fun parseInstant(value: String): Instant? =
            runCatching { Instant.parse(value.trim()) }.getOrNull()

        private fun isExplicitlySuppliedEntity(entity: DossierEntity, input: IdentityInput): Boolean {
            if (entity.kind == io.dossier.app.domain.model.GraphEntityKind.Subject || entity.type == EntityType.Person) {
                return true
            }
            val suppliedValues = when (entity.type) {
                EntityType.Email -> input.emails
                EntityType.Phone -> input.phones
                EntityType.Username -> buildList {
                    input.primaryUsername?.let(::add)
                    addAll(input.usernames)
                }
                EntityType.Organization -> input.organizations
                EntityType.Location -> input.locations
                EntityType.Profile -> input.profileUrls
                else -> emptyList()
            }
            return suppliedValues.any { comparable(it) == comparable(entity.label) }
        }

        private fun resolveRemediationLinks(
            remediationRecords: List<RemediationRecord>,
            rawEvidence: List<Evidence>,
            effectiveEvidence: List<Evidence>,
            normalizedEvidenceId: (String) -> String,
            excludedProvenanceIds: Set<String>,
            excludedEvidenceKeys: Set<EvidenceMatchKey>,
            rejectedEntityProvenanceIds: Set<String>,
            rejectedEntityEvidenceKeys: Set<EvidenceMatchKey>,
            unusableEvidenceIds: Set<String>,
            scanHistory: List<CaseScanHistoryEntry>
        ): List<AiRemediationLink> {
            val allEvidence = (effectiveEvidence + rawEvidence).distinctBy(Evidence::id)
            return remediationRecords.sortedWith(
                compareBy<RemediationRecord> { it.findingKey }.thenBy { it.remediationId }
            ).map { record ->
                val matched = findMatchingEvidence(record, allEvidence, normalizedEvidenceId)
                if (matched != null) {
                    val localEvidenceId = normalizedEvidenceId(matched.id)
                    val verificationScanPresent = hasLaterCompletedVerificationScan(record, scanHistory)
                    val isExcluded = matched.id in excludedProvenanceIds ||
                        evidenceMatchKey(matched) in excludedEvidenceKeys ||
                        matched.id in rejectedEntityProvenanceIds ||
                        evidenceMatchKey(matched) in rejectedEntityEvidenceKeys ||
                        matched.state == EvidenceState.Rejected
                    val isUnavailable = matched.state == EvidenceState.Unavailable ||
                        matched.id in unusableEvidenceIds
                    val linkState = when {
                        isExcluded -> AiRemediationLinkState.Excluded
                        isUnavailable -> AiRemediationLinkState.Unavailable
                        else -> AiRemediationLinkState.Effective
                    }
                    AiRemediationLink(
                        record = record.copy(
                            evidenceId = record.evidenceId?.let(normalizedEvidenceId)
                        ),
                        evidenceId = localEvidenceId,
                        effective = linkState == AiRemediationLinkState.Effective,
                        state = linkState,
                        verificationScanPresent = verificationScanPresent
                    )
                } else {
                    AiRemediationLink(
                        record = record.copy(
                            evidenceId = record.evidenceId?.let(normalizedEvidenceId)
                        ),
                        evidenceId = null,
                        effective = false,
                        state = AiRemediationLinkState.Unmatched,
                        verificationScanPresent = false
                    )
                }
            }
        }

        private fun findMatchingEvidence(
            record: RemediationRecord,
            candidates: List<Evidence>,
            normalizedEvidenceId: (String) -> String
        ): Evidence? {
            if (!record.evidenceId.isNullOrBlank()) {
                val targetId = record.evidenceId
                val targetNorm = normalizedEvidenceId(targetId)
                val migratedTarget = EvidenceIdPolicy.migrate(targetId)
                val directMatch = candidates.firstOrNull { candidate ->
                    candidate.id == targetId ||
                        normalizedEvidenceId(candidate.id) == targetNorm ||
                        EvidenceIdPolicy.migrate(candidate.id) == migratedTarget
                }
                if (directMatch != null) return directMatch
            }
            val parsedKey = parseFindingKey(record.findingKey)
            return if (parsedKey != null) {
                candidates.firstOrNull { candidate ->
                    findingKeyMatchesEvidence(parsedKey, candidate)
                }
            } else {
                candidates.firstOrNull { candidate ->
                    exactFindingKeyMatchesEvidence(record.findingKey, record.sourceUrl, candidate)
                }
            }
        }

        private data class ParsedFindingKey(
            val typeName: String,
            val value: String,
            val sourceUrl: String
        )

        private fun parseFindingKey(findingKey: String): ParsedFindingKey? {
            val parts = findingKey.split('|', limit = 3)
            if (parts.size < 3) return null
            return ParsedFindingKey(
                typeName = parts[0].trim(),
                value = parts[1].trim(),
                sourceUrl = parts[2].trim()
            )
        }

        private fun findingKeyMatchesEvidence(parsed: ParsedFindingKey, evidence: Evidence): Boolean {
            val expectedKind = findingTypeNameToEvidenceKind(parsed.typeName) ?: return false
            return evidence.kind == expectedKind &&
                comparable(evidence.value) == comparable(parsed.value) &&
                comparable(evidence.sourceUrl.orEmpty()) == comparable(parsed.sourceUrl)
        }

        private fun exactFindingKeyMatchesEvidence(findingKey: String, sourceUrl: String?, evidence: Evidence): Boolean {
            val target = comparable(findingKey)
            val matchesVal = comparable(evidence.value) == target || (evidence.kind.name + ":" + evidence.value).equals(findingKey, ignoreCase = true)
            val matchesSource = sourceUrl == null || comparable(evidence.sourceUrl.orEmpty()) == comparable(sourceUrl)
            return matchesVal && matchesSource
        }

        private fun findingTypeNameToEvidenceKind(typeName: String): EvidenceKind? {
            val findingType = FindingType.entries.firstOrNull { it.name.equals(typeName, ignoreCase = true) }
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
                null -> EvidenceKind.entries.firstOrNull { it.name.equals(typeName, ignoreCase = true) }
            }
        }

        private fun normalizeEvidenceId(id: String): String = when {
            id.startsWith("ev2:") && id.removePrefix("ev2:").matches(Regex("[a-fA-F0-9]{32}")) -> id
            id.startsWith("profile:") && id.removePrefix("profile:").matches(Regex("[a-fA-F0-9]{16}")) -> id
            else -> "ev2:" + MessageDigest.getInstance("SHA-256")
                .digest("ai-evidence:$id".toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }
                .take(32)
        }
    }
}
