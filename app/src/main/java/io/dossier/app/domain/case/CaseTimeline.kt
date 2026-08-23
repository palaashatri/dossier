package io.dossier.app.domain.case

import io.dossier.app.domain.evidence.Evidence
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
    MediaCandidate
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
    val historical: Boolean = false
)

/**
 * Builds a chronological timeline from timestamps Dossier can actually prove.
 * Missing timestamps remain missing; no dates are inferred from ordering, provider
 * names, URLs, or breach counts.
 */
object CaseTimelineBuilder {
    fun build(case: DossierCase, limit: Int = DEFAULT_LIMIT): List<TimelineEvent> {
        val events = mutableListOf<TimelineEvent>()

        case.evidenceRecords.forEach { evidence ->
            evidence.observedAtEpochMillis?.let { observed ->
                events += evidenceEvent(evidence, observed, observed = true)
            }
            val retrieved = evidence.retrievedAtEpochMillis
            if (retrieved != null && retrieved != evidence.observedAtEpochMillis) {
                events += TimelineEvent(
                    timestampEpochMillis = retrieved,
                    kind = TimelineEventKind.Retrieval,
                    title = "Evidence retrieved · ${evidence.kind.name}",
                    detail = evidence.providerId?.let { "Retrieved through $it" }
                        ?: "Evidence retrieval recorded by Dossier",
                    sourceUrl = evidence.sourceUrl,
                    evidenceId = evidence.id,
                    confidence = evidence.confidence,
                    historical = evidence.historical
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
                    historical = false
                )
            }
        }

        return events
            .distinctBy { "${it.timestampEpochMillis}|${it.kind}|${it.evidenceId}|${it.sourceUrl}" }
            .sortedByDescending(TimelineEvent::timestampEpochMillis)
            .take(limit.coerceIn(1, MAX_LIMIT))
    }

    private fun evidenceEvent(evidence: Evidence, timestamp: Long, observed: Boolean): TimelineEvent {
        val historical = evidence.historical
        val kind = if (historical) TimelineEventKind.HistoricalEvidence else TimelineEventKind.ObservedEvidence
        return TimelineEvent(
            timestampEpochMillis = timestamp,
            kind = kind,
            title = when {
                historical -> "Historical observation · ${evidence.kind.name}"
                observed -> "Observed · ${evidence.kind.name}"
                else -> "Evidence · ${evidence.kind.name}"
            },
            detail = buildString {
                evidence.providerId?.let { append(it) }
                if (isNotEmpty()) append(" · ")
                append(evidence.state.name)
                if (evidence.snippet?.isNotBlank() == true) {
                    append(" · ").append(evidence.snippet.take(MAX_DETAIL_CHARS))
                }
            }.take(MAX_DETAIL_CHARS),
            sourceUrl = evidence.sourceUrl,
            evidenceId = evidence.id,
            confidence = evidence.confidence,
            historical = historical
        )
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
}
