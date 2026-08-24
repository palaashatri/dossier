package io.dossier.app.domain.case

import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.EvidenceReliability
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.discovery.sanitizeTerminalFailureCode
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Serializable
enum class TimelineEventKind {
    ObservedEvidence,
    HistoricalEvidence,
    Retrieval,
    ScanStarted,
    ScanCompleted,
    ScanFailed,
    ScanCancelled,
    MediaCandidate,
    Remediation,
    Breach
}

/** User-facing timeline scopes. Each scope is a semantic filter, not a color cue. */
@Serializable
enum class TimelineFilter(val label: String) {
    All("All"),
    Live("Live"),
    Archives("Archives"),
    Breaches("Breaches"),
    Media("Media"),
    ScanActivity("Scan activity"),
    Remediation("Remediation"),
    Corrections("Corrections / Rejected");

    fun matches(event: TimelineEvent): Boolean = when (this) {
        All -> true
        Live -> !event.rawAuditOnly && (
            event.kind == TimelineEventKind.ObservedEvidence ||
                (event.kind == TimelineEventKind.Retrieval && !event.historical)
            )
        Archives -> !event.rawAuditOnly && (
            event.kind == TimelineEventKind.HistoricalEvidence ||
                (event.kind == TimelineEventKind.Retrieval && event.historical)
            )
        Breaches -> event.kind == TimelineEventKind.Breach
        Media -> event.kind == TimelineEventKind.MediaCandidate
        ScanActivity -> event.kind in setOf(
            TimelineEventKind.ScanStarted,
            TimelineEventKind.ScanCompleted,
            TimelineEventKind.ScanFailed,
            TimelineEventKind.ScanCancelled
        )
        Remediation -> event.kind == TimelineEventKind.Remediation
        Corrections -> event.rawAuditOnly
    }
}

@Serializable
data class TimelineEvent(
    val timestampEpochMillis: Long,
    val kind: TimelineEventKind,
    val title: String,
    val detail: String,
    val sourceUrl: String? = null,
    val evidenceId: String? = null,
    val confidence: Float? = null,
    val historical: Boolean = false,
    /** Safe display value copied from the evidence record; never inferred. */
    val value: String? = null,
    /** Normalized value used only for corroborated change comparison; display keeps [value]. */
    val comparisonValue: String? = null,
    val providerId: String? = null,
    val evidenceReliability: EvidenceReliability? = null,
    val evidenceState: EvidenceState? = null,
    val observedAtEpochMillis: Long? = null,
    val retrievedAtEpochMillis: Long? = null,
    /** True when the row is retained solely as rejected raw audit context. */
    val rawAuditOnly: Boolean = false,
    val correctionReason: String? = null,
    /** Stable semantic group used to show current/historical changes together. */
    val changeGroupKey: String? = null
) {
    /** Only verified evidence from a direct source is counted as verified current. */
    val isVerifiedCurrent: Boolean
        get() = !rawAuditOnly &&
            !historical &&
            kind == TimelineEventKind.ObservedEvidence &&
            evidenceState == EvidenceState.Verified &&
            evidenceReliability in setOf(
                EvidenceReliability.AuthoritativeApi,
                EvidenceReliability.DirectPublicProfile,
                EvidenceReliability.DirectPersonalWebsite
            )
}

data class TimelineChangeGroup(
    val key: String,
    val label: String,
    val events: List<TimelineEvent>,
    val distinctValueCount: Int,
    val hasCurrentEvidence: Boolean,
    val hasHistoricalEvidence: Boolean,
    val canCompareCurrentHistorical: Boolean = false
) {
    val changed: Boolean
        get() = canCompareCurrentHistorical && distinctValueCount > 1
}

data class TimelineAvailability(
    val timestampedEvidenceCount: Int,
    val undatedEvidenceCount: Int,
    val currentObservationCount: Int,
    val historicalObservationCount: Int,
    val archiveUnavailableCount: Int,
    val otherObservationCount: Int = 0,
    val retrievalCount: Int = 0,
    val undatedBreachCount: Int = 0,
    val undatedMediaCount: Int = 0,
    val undatedScanActivityCount: Int = 0,
    val undatedRemediationCount: Int = 0,
    val rawAuditEvidenceCount: Int = 0,
    val rawAuditTimestampedEvidenceCount: Int = 0,
    val rawAuditUndatedEvidenceCount: Int = 0,
    val totalEventCount: Int = 0,
    val visibleEventCount: Int = 0,
    val truncatedEventCount: Int = 0
) {
    val hasEvidence: Boolean get() = timestampedEvidenceCount + undatedEvidenceCount > 0
    val hasRawAuditEvidence: Boolean get() = rawAuditEvidenceCount > 0
    val hasTimestampedEvidence: Boolean get() = timestampedEvidenceCount > 0
    val hasHistoricalEvidence: Boolean get() = historicalObservationCount > 0
    val isTruncated: Boolean get() = truncatedEventCount > 0
}

data class TimelinePresentation(
    val events: List<TimelineEvent>,
    val groups: List<TimelineChangeGroup>,
    val availability: TimelineAvailability,
    val filter: TimelineFilter = TimelineFilter.All
)

/**
 * Builds a chronological timeline from timestamps Dossier can actually prove.
 * Missing timestamps remain missing; no dates are inferred from ordering, provider
 * names, URLs, or breach counts.
 */
object CaseTimelineBuilder {
    fun build(case: DossierCase, limit: Int = DEFAULT_LIMIT): List<TimelineEvent> {
        return buildAll(case).take(limit.coerceIn(1, MAX_LIMIT))
    }

    fun presentation(
        case: DossierCase,
        limit: Int = DEFAULT_LIMIT,
        filter: TimelineFilter = TimelineFilter.All
    ): TimelinePresentation {
        val allEvents = buildAll(case)
        val filteredEvents = allEvents.filter(filter::matches)
        val visibleEvents = filteredEvents.take(limit.coerceIn(1, MAX_LIMIT))
        val evidenceEvents = allEvents.filter {
            it.kind == TimelineEventKind.ObservedEvidence ||
                it.kind == TimelineEventKind.HistoricalEvidence ||
                it.kind == TimelineEventKind.Retrieval
        }
        val correctionAudit = EffectiveCaseProjection.correctionAudit(case)
        val activeEvidenceRecords = case.evidenceRecords.filterNot {
            it.id in correctionAudit
        }
        val activeEvidenceEvents = evidenceEvents.filterNot(TimelineEvent::rawAuditOnly)
        val groups = buildGroups(visibleEvents)
        val availability = TimelineAvailability(
            timestampedEvidenceCount = activeEvidenceRecords.count {
                it.observedAtEpochMillis != null || it.retrievedAtEpochMillis != null
            },
            undatedEvidenceCount = activeEvidenceRecords.count {
                it.observedAtEpochMillis == null && it.retrievedAtEpochMillis == null
            },
            currentObservationCount = activeEvidenceEvents.count {
                it.isVerifiedCurrent
            },
            otherObservationCount = activeEvidenceEvents.count {
                !it.historical &&
                    it.kind == TimelineEventKind.ObservedEvidence &&
                    !it.isVerifiedCurrent
            },
            historicalObservationCount = activeEvidenceEvents.count {
                it.historical && it.kind == TimelineEventKind.HistoricalEvidence
            },
            retrievalCount = activeEvidenceEvents.count { it.kind == TimelineEventKind.Retrieval },
            archiveUnavailableCount = activeEvidenceRecords.count {
                it.reliability == EvidenceReliability.ArchiveSnapshot &&
                    it.state == EvidenceState.Unavailable
            },
            // BreachDigest currently stores no timestamp. Do not substitute scan
            // time, case creation time, or a provider date that was not persisted.
            undatedBreachCount = case.breachDigests.size,
            undatedMediaCount = undatedMediaCount(case),
            undatedScanActivityCount = case.scanHistory.count { scan ->
                parseTimestamp(scan.startedAtUtc) == null &&
                    (scan.completedAtUtc == null || parseTimestamp(scan.completedAtUtc) == null)
            },
            undatedRemediationCount = case.remediationRecords.count { record ->
                parseTimestamp(record.createdAtUtc) == null &&
                    parseTimestamp(record.updatedAtUtc) == null
            },
            rawAuditEvidenceCount = correctionAudit.size,
            rawAuditTimestampedEvidenceCount = case.evidenceRecords.count {
                it.id in correctionAudit &&
                    (it.observedAtEpochMillis != null || it.retrievedAtEpochMillis != null)
            },
            rawAuditUndatedEvidenceCount = case.evidenceRecords.count {
                it.id in correctionAudit &&
                    it.observedAtEpochMillis == null && it.retrievedAtEpochMillis == null
            },
            totalEventCount = filteredEvents.size,
            visibleEventCount = visibleEvents.size,
            truncatedEventCount = (filteredEvents.size - visibleEvents.size).coerceAtLeast(0)
        )
        return TimelinePresentation(
            events = visibleEvents,
            groups = groups,
            availability = availability,
            filter = filter
        )
    }

    private fun buildAll(case: DossierCase): List<TimelineEvent> {
        val events = mutableListOf<TimelineEvent>()
        val correctionAudit = EffectiveCaseProjection.correctionAudit(case)

        case.evidenceRecords.forEach { evidence ->
            evidence.observedAtEpochMillis?.let { observed ->
                events += evidenceEvent(
                    evidence,
                    observed,
                    observed = true,
                    correctionReason = correctionAudit[evidence.id]
                )
            }
            val retrieved = evidence.retrievedAtEpochMillis
            if (retrieved != null && retrieved != evidence.observedAtEpochMillis) {
                val auditReason = correctionAudit[evidence.id]
                events += TimelineEvent(
                    timestampEpochMillis = retrieved,
                    kind = TimelineEventKind.Retrieval,
                    title = if (auditReason == null) {
                        "Evidence retrieved · ${evidence.kind.name}"
                    } else {
                        "REJECTED · RAW AUDIT · ${evidence.kind.name} retrieval"
                    },
                    detail = buildString {
                        if (auditReason != null) {
                            append("REJECTED · RAW AUDIT · ")
                            append(auditReason.label)
                            append(" · ")
                        }
                        append(
                            evidence.providerId?.let { "Retrieved through $it" }
                                ?: "Evidence retrieval recorded by Dossier"
                        )
                    }.take(MAX_DETAIL_CHARS),
                    sourceUrl = evidence.sourceUrl,
                    evidenceId = evidence.id,
                    confidence = evidence.confidence,
                    historical = evidence.historical,
                    value = evidence.value,
                    providerId = evidence.providerId,
                    evidenceReliability = evidence.reliability,
                    evidenceState = if (auditReason == null) evidence.state else EvidenceState.Rejected,
                    observedAtEpochMillis = evidence.observedAtEpochMillis,
                    retrievedAtEpochMillis = evidence.retrievedAtEpochMillis,
                    comparisonValue = evidenceComparisonValue(evidence),
                    rawAuditOnly = auditReason != null,
                    correctionReason = auditReason?.label,
                    changeGroupKey = evidenceGroupKey(evidence)
                )
            }
        }

        case.scanHistory.forEach { scan ->
            val safeFailureCode = if (scan.failed) {
                sanitizeTerminalFailureCode(scan.failureCode) ?: "SCAN_FAILED"
            } else {
                null
            }
            parseTimestamp(scan.startedAtUtc)?.let { timestamp ->
                events += TimelineEvent(
                    timestampEpochMillis = timestamp,
                    kind = TimelineEventKind.ScanStarted,
                    title = "Assessment started",
                    detail = "${scan.mode.name} · ${scan.directProfileProviderCount.coerceAtLeast(0)} direct providers",
                    evidenceId = "scan:${scan.scanId}:start"
                )
            }
            scan.completedAtUtc?.let(::parseTimestamp)?.let { timestamp ->
                events += TimelineEvent(
                    timestampEpochMillis = timestamp,
                    kind = when {
                        scan.failed -> TimelineEventKind.ScanFailed
                        scan.cancelled -> TimelineEventKind.ScanCancelled
                        else -> TimelineEventKind.ScanCompleted
                    },
                    title = when {
                        scan.failed -> "Assessment failed"
                        scan.cancelled -> "Assessment cancelled"
                        else -> "Assessment completed"
                    },
                    detail = buildString {
                        append("${scan.profileResultCount.coerceAtLeast(0)} profiles · ")
                        append("${scan.findingCount.coerceAtLeast(0)} findings · ")
                        append("${scan.graphEntityCount.coerceAtLeast(0)} entities")
                        safeFailureCode?.let { append(" · $it") }
                    },
                    evidenceId = "scan:${scan.scanId}:complete"
                )
            }
        }

        case.mediaIntelligence.imageResults.forEachIndexed { resultIndex, result ->
            result.visualCandidates.forEach { candidate ->
                val timestamp = candidate.retrievedAtEpochMillis ?: return@forEach
                events += TimelineEvent(
                    timestampEpochMillis = timestamp,
                    kind = TimelineEventKind.MediaCandidate,
                    title = "Image candidate · ${candidate.source}",
                    detail = buildString {
                        append(candidate.state.name)
                        candidate.comparisonScore?.let { append(" · similarity ${(it * 100).toInt()}%") }
                        candidate.clusterId?.let { append(" · cluster $it") }
                    },
                    sourceUrl = candidate.sourcePageUrl,
                    evidenceId = "media:$resultIndex:${candidate.id}",
                    confidence = candidate.comparisonScore,
                    historical = false,
                    changeGroupKey = MEDIA_GROUP_KEY
                )
            }
        }

        case.remediationRecords.forEach { record ->
            parseTimestamp(record.createdAtUtc)?.let { timestamp ->
                events += remediationEvent(record, timestamp, updated = false)
            }
            parseTimestamp(record.updatedAtUtc)?.let { timestamp ->
                if (timestamp != parseTimestamp(record.createdAtUtc)) {
                    events += remediationEvent(record, timestamp, updated = true)
                }
            }
        }

        return events
            .distinctBy { "${it.timestampEpochMillis}|${it.kind}|${it.evidenceId}|${it.sourceUrl}" }
            .sortedByDescending(TimelineEvent::timestampEpochMillis)
    }

    private fun buildGroups(events: List<TimelineEvent>): List<TimelineChangeGroup> = events
        .groupBy { it.changeGroupKey?.takeIf(String::isNotBlank) ?: ACTIVITY_GROUP_KEY }
        .flatMap { (kindKey, kindEvents) ->
            val contexts = if (kindKey in EVIDENCE_GROUP_KEYS) {
                kindEvents.groupBy(::eventContextKey)
            } else {
                mapOf(ACTIVITY_GROUP_KEY to kindEvents)
            }
            val includeContextInKey = contexts.size > 1
            contexts.map { (contextKey, grouped) ->
                val key = if (includeContextInKey) "$kindKey|$contextKey" else kindKey
                val labelKey = kindKey.substringBefore('|')
                val ordered = grouped.sortedByDescending(TimelineEvent::timestampEpochMillis)
                val comparableEvents = ordered.filterNot(TimelineEvent::rawAuditOnly)
                TimelineChangeGroup(
                    key = key,
                    label = if (labelKey == ACTIVITY_GROUP_KEY) {
                        "Assessment activity"
                    } else {
                        labelKey.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
                    },
                    events = ordered,
                    distinctValueCount = comparableEvents.mapNotNull {
                        (it.comparisonValue ?: it.value)?.trim()?.takeIf(String::isNotBlank)
                    }.distinct().size,
                    hasCurrentEvidence = comparableEvents.any {
                        it.isVerifiedCurrent
                    },
                    hasHistoricalEvidence = comparableEvents.any {
                        it.historical && it.kind == TimelineEventKind.HistoricalEvidence
                    },
                    canCompareCurrentHistorical = kindKey in EVIDENCE_GROUP_KEYS &&
                        comparableEvents.any(TimelineEvent::isVerifiedCurrent) &&
                        comparableEvents.any {
                            it.historical &&
                                it.kind == TimelineEventKind.HistoricalEvidence &&
                                !it.comparisonValue.isNullOrBlank()
                        }
                )
            }
        }
        .sortedWith(
                compareByDescending<TimelineChangeGroup> {
                    it.events.maxOfOrNull(TimelineEvent::timestampEpochMillis) ?: Long.MIN_VALUE
                }.thenBy(TimelineChangeGroup::label)
            )

    private fun eventContextKey(event: TimelineEvent): String {
        val raw = event.sourceUrl?.takeIf(String::isNotBlank)
            ?: event.value?.takeIf(String::isNotBlank)
            ?: event.providerId?.takeIf(String::isNotBlank)
            ?: event.evidenceId.orEmpty()
        return canonicalArchiveTarget(raw).ifBlank { "unscoped" }
    }

    private fun canonicalArchiveTarget(raw: String): String {
        val value = raw.trim()
        val marker = value.indexOf("web.archive.org/web/", ignoreCase = true)
        if (marker >= 0) {
            val suffix = value.substring(marker + "web.archive.org/web/".length)
            val target = suffix.substringAfter('/', "")
            return target.substringAfter("_/", target).trimEnd('/').lowercase()
        }
        return value.trimEnd('/').lowercase()
    }

    private fun remediationEvent(
        record: RemediationRecord,
        timestamp: Long,
        updated: Boolean
    ): TimelineEvent = TimelineEvent(
        timestampEpochMillis = timestamp,
        kind = TimelineEventKind.Remediation,
        title = if (updated) {
            "Remediation updated · ${record.status.name}"
        } else {
            "Remediation recorded · ${record.status.name}"
        },
        detail = buildString {
            append(record.action)
            record.providerId?.let { append(" · provider $it") }
            record.verificationNote?.takeIf(String::isNotBlank)?.let { append(" · $it") }
        }.take(MAX_DETAIL_CHARS),
        sourceUrl = record.sourceUrl,
        evidenceId = "remediation:${record.remediationId}:${if (updated) "updated" else "created"}",
        providerId = record.providerId,
        changeGroupKey = REMEDIATION_GROUP_KEY
    )

    private fun undatedMediaCount(case: DossierCase): Int {
        val undatedImageResults = case.mediaIntelligence.imageResults.count { result ->
            result.visualCandidates.none { it.retrievedAtEpochMillis != null }
        }
        return undatedImageResults + case.mediaIntelligence.videoResults.size
    }

    private fun evidenceEvent(
        evidence: Evidence,
        timestamp: Long,
        observed: Boolean,
        correctionReason: CorrectionAuditReason?
    ): TimelineEvent {
        val historical = evidence.historical
        val kind = if (historical) TimelineEventKind.HistoricalEvidence else TimelineEventKind.ObservedEvidence
        val rawAuditOnly = correctionReason != null
        return TimelineEvent(
            timestampEpochMillis = timestamp,
            kind = kind,
            title = when {
                rawAuditOnly -> "REJECTED · RAW AUDIT · ${evidence.kind.name}"
                historical -> "Historical observation · ${evidence.kind.name}"
                observed -> "Observed · ${evidence.kind.name}"
                else -> "Evidence · ${evidence.kind.name}"
            },
            detail = buildString {
                if (rawAuditOnly) {
                    append("REJECTED · RAW AUDIT · ")
                    append(correctionReason.label)
                    append(" · ")
                }
                evidence.providerId?.let { append(it) }
                if (isNotEmpty()) append(" · ")
                append(if (rawAuditOnly) EvidenceState.Rejected.name else evidence.state.name)
                if (evidence.snippet?.isNotBlank() == true) {
                    append(" · ").append(evidence.snippet.take(MAX_DETAIL_CHARS))
                }
            }.take(MAX_DETAIL_CHARS),
            sourceUrl = evidence.sourceUrl,
            evidenceId = evidence.id,
            confidence = evidence.confidence,
            historical = historical,
            value = evidence.value,
            comparisonValue = evidenceComparisonValue(evidence),
            providerId = evidence.providerId,
            evidenceReliability = evidence.reliability,
            evidenceState = if (rawAuditOnly) EvidenceState.Rejected else evidence.state,
            observedAtEpochMillis = evidence.observedAtEpochMillis,
            retrievedAtEpochMillis = evidence.retrievedAtEpochMillis,
            rawAuditOnly = rawAuditOnly,
            correctionReason = correctionReason?.label,
            changeGroupKey = evidenceGroupKey(evidence)
        )
    }

    /**
     * Keeps capture URLs comparable without pretending that a Wayback capture
     * contains a historical profile attribute. The raw snapshot URL remains in
     * [TimelineEvent.value] and [TimelineEvent.sourceUrl] for provenance.
     */
    private fun evidenceComparisonValue(evidence: Evidence): String? {
        val raw = evidence.value?.trim()?.takeIf(String::isNotBlank) ?: return null
        if (evidence.kind == EvidenceKind.Profile && raw.startsWith("http", ignoreCase = true)) {
            return canonicalArchiveTarget(raw)
        }
        if (
            evidence.historical &&
            evidence.reliability == EvidenceReliability.ArchiveSnapshot
        ) {
            val target = canonicalArchiveTarget(evidence.sourceUrl ?: raw)
            if (target.startsWith("http")) return target
        }
        return raw
    }

    /**
     * Wayback stores the original profile URL inside an archive URL while its
     * evidence kind is PublicSearchEvidence. When that URL is present, it can
     * safely share the Profile comparison group with the live observation; the
     * source context still has to match before a change candidate is shown.
     */
    private fun evidenceGroupKey(evidence: Evidence): String = if (
        evidence.historical &&
        evidence.reliability == EvidenceReliability.ArchiveSnapshot &&
        canonicalArchiveTarget(evidence.sourceUrl ?: evidence.value).startsWith("http")
    ) {
        EvidenceKind.Profile.name
    } else {
        evidence.kind.name
    }

    internal fun parseTimestamp(raw: String): Long? {
        val value = raw.trim()
        if (value.isBlank()) return null
        runCatching { return Instant.parse(value).toEpochMilli() }
        runCatching { return java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli() }
        val formats = listOf(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME
        )
        formats.forEach { formatter ->
            runCatching {
                return LocalDateTime.parse(value, formatter).toInstant(ZoneOffset.UTC).toEpochMilli()
            }
        }
        return null
    }

    private const val DEFAULT_LIMIT = 500
    private const val MAX_LIMIT = 2_000
    private const val MAX_DETAIL_CHARS = 320
    private const val ACTIVITY_GROUP_KEY = "activity"
    private const val MEDIA_GROUP_KEY = "media"
    private const val REMEDIATION_GROUP_KEY = "remediation"
    private val EVIDENCE_GROUP_KEYS = setOf(
        EvidenceKind.Email.name,
        EvidenceKind.Phone.name,
        EvidenceKind.Address.name,
        EvidenceKind.Location.name,
        EvidenceKind.Username.name,
        EvidenceKind.Profile.name,
        EvidenceKind.Organization.name,
        EvidenceKind.UsernameReuse.name,
        EvidenceKind.PlausibleProfileMatch.name,
        EvidenceKind.PublicSearchEvidence.name,
        EvidenceKind.PublicImageEvidence.name,
        EvidenceKind.ImageConsistency.name,
        EvidenceKind.SensitiveSnippet.name
    )
}
