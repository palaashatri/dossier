package io.dossier.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.dossier.app.domain.case.CaseTimelineBuilder
import io.dossier.app.domain.case.DossierCase
import io.dossier.app.domain.case.TimelineChangeGroup
import io.dossier.app.domain.case.TimelineEvent
import io.dossier.app.domain.case.TimelineEventKind
import io.dossier.app.domain.case.TimelineFilter
import io.dossier.app.domain.evidence.EvidenceReliability
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.model.BreachDigest
import io.dossier.app.ui.theme.NeuralTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Evidence-first timeline for both the active report and saved-case review.
 * Current versus historical state is written into every row; color is only a
 * secondary cue and never the sole status signal.
 */
@Composable
fun HistoricalTimelinePanel(
    case: DossierCase,
    modifier: Modifier = Modifier,
    onNavigateToBrowser: (String) -> Unit = {}
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            HistoricalTimelinePanelContent(
                case = case,
                modifier = Modifier.fillMaxWidth(),
                onNavigateToBrowser = onNavigateToBrowser
            )
        }
    }
}

/**
 * Non-scrolling timeline body for screens that already own a LazyColumn. Keeping
 * one body implementation avoids nested same-direction scrolling in saved-case
 * review while the report screen can still use the scrolling wrapper above.
 */
@Composable
fun HistoricalTimelinePanelContent(
    case: DossierCase,
    modifier: Modifier = Modifier,
    onNavigateToBrowser: (String) -> Unit = {}
) {
    var selectedFilter by rememberSaveable(case.caseId) { mutableStateOf(TimelineFilter.All) }
    val presentation = remember(case, selectedFilter) {
        CaseTimelineBuilder.presentation(case, limit = 240, filter = selectedFilter)
    }
    val availability = presentation.availability

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TimelineHeader(availability)
        TimelineFilterBar(
            selected = selectedFilter,
            onSelected = { selectedFilter = it }
        )

        if (availability.isTruncated) {
            TimelineNotice(
                "Timeline rows truncated",
                "Showing ${availability.visibleEventCount} of ${availability.totalEventCount} ${selectedFilter.label.lowercase()} timeline rows. ${availability.truncatedEventCount} older row(s) are omitted by the bounded view."
            )
        }

        undatedNotice(selectedFilter, availability)?.let { (title, detail) ->
            TimelineNotice(title, detail)
        }

        if (selectedFilter == TimelineFilter.All && availability.hasRawAuditEvidence) {
            TimelineNotice(
                "Rejected evidence retained as raw audit",
                "${availability.rawAuditEvidenceCount} corrected evidence row(s) are excluded from active current/history metrics and change claims. Use Corrections / Rejected to review them explicitly."
            )
        }

        if (availability.archiveUnavailableCount > 0) {
            if (selectedFilter in setOf(TimelineFilter.All, TimelineFilter.Archives)) {
                TimelineNotice(
                    "Historical lookup unavailable",
                    "${availability.archiveUnavailableCount} archive record(s) were unavailable. No historical observation is asserted for those sources."
                )
            }
        }

        if (selectedFilter in setOf(TimelineFilter.All, TimelineFilter.Live, TimelineFilter.Archives) &&
            !availability.hasTimestampedEvidence &&
            undatedNotice(selectedFilter, availability) == null
        ) {
            TimelineNotice(
                "No timestamped evidence",
                if (availability.undatedEvidenceCount > 0) {
                    "${availability.undatedEvidenceCount} evidence item(s) have no recorded observation or retrieval time. Dossier does not invent dates."
                } else {
                    "This case contains no evidence records with a recorded observation or retrieval time."
                }
            )
        }

        val hasUndatedBreachRows = selectedFilter == TimelineFilter.Breaches && case.breachDigests.isNotEmpty()
        if (hasUndatedBreachRows) {
            UndatedBreachRows(case.breachDigests)
        }

        val noAttachedMedia = selectedFilter == TimelineFilter.Media &&
            case.mediaIntelligence.imageResults.isEmpty() &&
            case.mediaIntelligence.videoResults.isEmpty()
        if (noAttachedMedia) {
            TimelineNotice(
                "No media attached to this case",
                "Media analysis is not attached to this case snapshot, so no media timeline rows are asserted."
            )
        }

        if (presentation.groups.isEmpty() && !hasUndatedBreachRows && !noAttachedMedia) {
            TimelineNotice(
                "${selectedFilter.label} timeline is empty",
                "No timestamped ${selectedFilter.label.lowercase()} events are available in this case. Undated records remain explicitly identified above."
            )
        } else {
            presentation.groups.forEach { group ->
                TimelineGroupCard(group, onNavigateToBrowser)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimelineFilterBar(
    selected: TimelineFilter,
    onSelected: (TimelineFilter) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TimelineFilter.entries.forEach { filter ->
            val isSelected = filter == selected
            FilterChip(
                selected = isSelected,
                onClick = { onSelected(filter) },
                label = {
                    Text(if (isSelected) "${filter.label} · selected" else filter.label)
                },
                modifier = Modifier.semantics {
                    this.selected = isSelected
                    stateDescription = if (isSelected) "Selected" else "Not selected"
                    contentDescription = "${filter.label} timeline filter, ${if (isSelected) "selected" else "not selected"}"
                }
            )
        }
    }
}

private fun undatedNotice(
    filter: TimelineFilter,
    availability: io.dossier.app.domain.case.TimelineAvailability
): Pair<String, String>? {
    val categories = when (filter) {
        TimelineFilter.All -> listOf(
            "evidence" to availability.undatedEvidenceCount,
            "breach" to availability.undatedBreachCount,
            "media" to availability.undatedMediaCount,
            "scan activity" to availability.undatedScanActivityCount,
            "remediation" to availability.undatedRemediationCount
        )
        TimelineFilter.Live, TimelineFilter.Archives -> listOf(
            "evidence" to availability.undatedEvidenceCount
        )
        TimelineFilter.Breaches -> listOf("breach" to availability.undatedBreachCount)
        TimelineFilter.Media -> listOf("media" to availability.undatedMediaCount)
        TimelineFilter.ScanActivity -> listOf(
            "scan activity" to availability.undatedScanActivityCount
        )
        TimelineFilter.Remediation -> listOf(
            "remediation" to availability.undatedRemediationCount
        )
        TimelineFilter.Corrections -> listOf(
            "rejected raw audit evidence" to availability.rawAuditUndatedEvidenceCount
        )
    }.filter { (_, count) -> count > 0 }
    if (categories.isEmpty()) return null

    val selectedCount = when (filter) {
        TimelineFilter.Live, TimelineFilter.Archives -> availability.undatedEvidenceCount
        TimelineFilter.Breaches -> availability.undatedBreachCount
        TimelineFilter.Media -> availability.undatedMediaCount
        TimelineFilter.ScanActivity -> availability.undatedScanActivityCount
        TimelineFilter.Remediation -> availability.undatedRemediationCount
        TimelineFilter.Corrections -> availability.rawAuditUndatedEvidenceCount
        else -> 0
    }
    val selectedLabel = when (filter) {
        TimelineFilter.Live, TimelineFilter.Archives -> "Evidence records"
        TimelineFilter.Breaches -> "Breach records"
        TimelineFilter.Media -> "Media records"
        TimelineFilter.ScanActivity -> "Scan activity records"
        TimelineFilter.Remediation -> "Remediation records"
        TimelineFilter.Corrections -> "Rejected raw audit evidence"
        else -> "Timeline records"
    }
    val title = if (filter != TimelineFilter.All && selectedCount > 0) {
        "$selectedLabel are undated"
    } else {
        "Some records are undated"
    }
    return title to
        "${categories.joinToString { (label, count) -> "$count $label record(s)" }} have no parseable timestamp stored. Dossier keeps them undated and excludes them from chronological rows; no date is inferred."
}

@Composable
private fun UndatedBreachRows(records: List<BreachDigest>) {
    val visible = records.take(MAX_UNDATED_BREACH_ROWS)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "Undated breach metadata. ${records.size} breach record(s) have no stored timestamp and are not placed on the chronological axis."
            },
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "Undated breach details",
            color = NeuralTheme.TextPrimary,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold
        )
        visible.forEach { digest ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(NeuralTheme.CardBackground.copy(alpha = 0.72f), RoundedCornerShape(8.dp))
                    .border(1.dp, NeuralTheme.BorderColor, RoundedCornerShape(8.dp))
                    .padding(10.dp)
                    .semantics {
                        contentDescription = "Undated breach record for ${digest.email}. ${digest.breachCount.coerceAtLeast(0)} breach record(s). No stored timestamp."
                    },
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    "BREACH · UNDATED",
                    color = NeuralTheme.Amber,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "${digest.email} · ${digest.breachCount.coerceAtLeast(0)} breach record(s)",
                    color = NeuralTheme.TextPrimary,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
                digest.sources.take(MAX_BREACH_SOURCE_PREVIEW).takeIf(List<String>::isNotEmpty)?.let { sources ->
                    Text(
                        "Sources: ${sources.joinToString(", ")}",
                        color = NeuralTheme.TextSecondary,
                        fontSize = 10.5.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                digest.note?.takeIf(String::isNotBlank)?.let { note ->
                    Text(note, color = NeuralTheme.TextSecondary, fontSize = 10.5.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    "No stored timestamp; excluded from chronological ordering.",
                    color = NeuralTheme.TextMuted,
                    fontSize = 10.sp
                )
            }
        }
        if (records.size > visible.size) {
            Text(
                "${records.size - visible.size} additional undated breach record(s) omitted by the bounded detail view.",
                color = NeuralTheme.TextMuted,
                fontSize = 10.5.sp
            )
        }
    }
}

@Composable
fun TimelineUnavailablePanel(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TimelineNotice(
            "Timeline unavailable",
            "No active case snapshot is available for this report. Complete or restore an investigation before reviewing timestamped evidence."
        )
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun TimelineHeader(availability: io.dossier.app.domain.case.TimelineAvailability) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            "Identity timeline",
            color = NeuralTheme.TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            "Recorded observations and retrievals only. Historical captures remain historical and are not proof of a current account.",
            color = NeuralTheme.TextSecondary,
            fontSize = 11.5.sp,
            lineHeight = 17.sp
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TimelineMetric("Verified current", availability.currentObservationCount)
            TimelineMetric("Other observations", availability.otherObservationCount)
            TimelineMetric("Historical", availability.historicalObservationCount)
            TimelineMetric("Timestamped", availability.timestampedEvidenceCount)
            TimelineMetric("Retrieved", availability.retrievalCount)
            TimelineMetric("Rejected raw audit", availability.rawAuditEvidenceCount)
        }
        Text(
            "VERIFIED CURRENT = verified evidence from a direct source · OBSERVED = unverified or non-direct evidence · CANDIDATE = search candidate · HISTORICAL = archived observation · RETRIEVAL RECORDED = a stored retrieval or batch timestamp, not necessarily the provider fetch instant · REJECTED · RAW AUDIT = retained evidence excluded from active metrics after a user correction",
            color = NeuralTheme.TextMuted,
            fontSize = 10.sp,
            lineHeight = 15.sp,
            modifier = Modifier.semantics {
                contentDescription = "Timeline legend. Verified current means verified evidence from a direct source. Observed means unverified or non-direct evidence. Candidate means a search candidate. Historical means archived observation. Retrieval recorded means a stored retrieval or batch timestamp, not necessarily the provider fetch instant. Rejected raw audit means retained evidence excluded from active metrics after a user correction."
            }
        )
    }
}

@Composable
private fun TimelineMetric(label: String, value: Int) {
    Column {
        Text(value.toString(), color = NeuralTheme.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(label, color = NeuralTheme.TextSecondary, fontSize = 9.5.sp)
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun TimelineGroupCard(
    group: TimelineChangeGroup,
    onNavigateToBrowser: (String) -> Unit
) {
    val border = when {
        group.hasHistoricalEvidence && group.hasCurrentEvidence -> NeuralTheme.Cobalt
        group.hasHistoricalEvidence -> NeuralTheme.Amber
        else -> NeuralTheme.BorderColor
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(NeuralTheme.CardBackground.copy(alpha = 0.94f), RoundedCornerShape(12.dp))
            .border(BorderStroke(1.dp, border.copy(alpha = 0.65f)), RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(group.label, color = NeuralTheme.Cobalt, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            if (group.changed) {
                Text(
                    "CHANGE CANDIDATE",
                    color = NeuralTheme.TextPrimary,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics {
                        contentDescription = "This group contains ${group.distinctValueCount} distinct recorded values; review the evidence before treating it as an identity change."
                    }
                )
            }
        }
        if (group.hasCurrentEvidence && group.hasHistoricalEvidence) {
            Text(
                "Current and historical observations are shown together for comparison.",
                color = NeuralTheme.TextSecondary,
                fontSize = 10.5.sp,
                lineHeight = 15.sp
            )
        }
        group.events.forEach { event ->
            TimelineEventRow(event, onNavigateToBrowser)
        }
    }
}

@Composable
private fun TimelineEventRow(event: TimelineEvent, onNavigateToBrowser: (String) -> Unit) {
    val historicalLabel = when {
        event.rawAuditOnly -> "REJECTED · RAW AUDIT"
        event.kind == TimelineEventKind.Retrieval ->
            if (event.historical) "HISTORICAL · RETRIEVAL RECORDED" else "RETRIEVAL RECORDED"
        event.historical -> "HISTORICAL"
        event.kind == TimelineEventKind.ObservedEvidence -> when {
            event.isVerifiedCurrent -> "VERIFIED CURRENT"
            event.evidenceState == EvidenceState.Candidate ||
                event.evidenceReliability == EvidenceReliability.SearchEngineCandidate -> "CANDIDATE"
            else -> "OBSERVED"
        }
        event.kind == TimelineEventKind.MediaCandidate -> "MEDIA"
        event.kind == TimelineEventKind.Breach -> "BREACH"
        event.kind == TimelineEventKind.Remediation -> "REMEDIATION"
        else -> "ACTIVITY"
    }
    val timestamp = Instant.ofEpochMilli(event.timestampEpochMillis)
        .atZone(ZoneId.systemDefault())
        .format(TIMELINE_FORMAT)
    Column(
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                contentDescription = buildString {
                    append("$historicalLabel timeline row, $timestamp, ${event.title}. ${event.detail}")
                    event.correctionReason?.let { append(" Correction reason: $it.") }
                }
            }
            .padding(vertical = 3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(
                historicalLabel,
                color = when (historicalLabel) {
                    "HISTORICAL", "HISTORICAL · RETRIEVAL RECORDED" -> NeuralTheme.Amber
                    "VERIFIED CURRENT" -> NeuralTheme.Emerald
                    "CANDIDATE" -> NeuralTheme.Amber
                    else -> NeuralTheme.TextSecondary
                },
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold
            )
            Text(timestamp, color = NeuralTheme.TextSecondary, fontSize = 10.5.sp)
        }
        Text(event.title, color = NeuralTheme.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        event.value?.takeIf(String::isNotBlank)?.let { value ->
            Text(
                value,
                color = NeuralTheme.TextPrimary,
                fontSize = 11.5.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            buildString {
                append(event.detail)
                event.providerId?.let { append(" · provider $it") }
                event.evidenceState?.let { append(" · state ${it.name}") }
                event.confidence?.let { append(" · support ${(it.coerceIn(0f, 1f) * 100).toInt()}%") }
            },
            color = NeuralTheme.TextSecondary,
            fontSize = 10.5.sp,
            lineHeight = 15.sp
        )
        event.sourceUrl?.takeIf(::isHttpUrl)?.let { source ->
            Text(
                source,
                color = NeuralTheme.Cobalt,
                fontSize = 10.5.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToBrowser(source) }
                    .semantics { contentDescription = "Open evidence source $source" }
            )
        }
        HorizontalDivider(color = NeuralTheme.BorderColor.copy(alpha = 0.7f))
    }
}

@Composable
private fun TimelineNotice(title: String, detail: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(NeuralTheme.AccentSurface, RoundedCornerShape(10.dp))
            .border(1.dp, NeuralTheme.BorderColor, RoundedCornerShape(10.dp))
            .padding(13.dp)
            .semantics { contentDescription = "$title. $detail" },
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(title, color = NeuralTheme.TextPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        Text(detail, color = NeuralTheme.TextSecondary, fontSize = 11.sp, lineHeight = 16.sp)
    }
}

private val TIMELINE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun isHttpUrl(value: String): Boolean =
    value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true)

private const val MAX_UNDATED_BREACH_ROWS = 40
private const val MAX_BREACH_SOURCE_PREVIEW = 4
