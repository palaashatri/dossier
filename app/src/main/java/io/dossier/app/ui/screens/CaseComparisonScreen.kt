package io.dossier.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.dossier.app.data.ai.AiInsightService
import io.dossier.app.data.ai.AiProviderConfigStore
import io.dossier.app.data.ai.AiRemotePermission
import io.dossier.app.domain.case.CaseComparison
import io.dossier.app.domain.case.CaseAnalysisUpdateResult
import io.dossier.app.domain.case.CaseStore
import io.dossier.app.domain.case.CaseTimelineBuilder
import io.dossier.app.domain.case.DossierCase
import io.dossier.app.domain.case.RemediationRecord
import io.dossier.app.domain.case.RemediationStatus
import io.dossier.app.domain.discovery.sanitizeTerminalFailureCode
import io.dossier.app.domain.case.UserCorrection
import io.dossier.app.domain.case.UserCorrectionDecision
import io.dossier.app.domain.evidence.toEvidence
import io.dossier.app.domain.model.EntityType
import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.ReverseImageLookupResult
import io.dossier.app.domain.model.RiskLevel
import io.dossier.app.export.ExportRedactionMode
import io.dossier.app.export.ReportExporter
import io.dossier.app.ui.components.AnimatedObsidianBackground
import io.dossier.app.ui.screens.HistoricalTimelinePanelContent
import io.dossier.app.ui.theme.DossierButtonShape
import io.dossier.app.ui.theme.NeuralTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

/** Explicit local saved-case selection, comparison, correction and remediation. */
@Composable
fun CaseComparisonScreen(onNavigateToBrowser: (String) -> Unit = {}) {
    val context = LocalContext.current
    val store = remember { CaseStore(context) }
    val caseScope = rememberCoroutineScope()
    val aiInsightService = remember { AiInsightService(context) }
    val exporter = remember { ReportExporter(context) }
    var cases by remember { mutableStateOf(emptyList<DossierCase>()) }
    var selectedBefore by remember { mutableStateOf<String?>(null) }
    var selectedAfter by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<DossierCase?>(null) }
    var actionNotice by remember { mutableStateOf<String?>(null) }

    suspend fun refreshCases() {
        cases = store.listEffectiveAsync()
        selectedBefore = selectedBefore?.takeIf { id -> cases.any { it.caseId == id } }
        selectedAfter = selectedAfter?.takeIf { id -> cases.any { it.caseId == id } }
        if (selectedAfter == null) selectedAfter = cases.firstOrNull()?.caseId
        if (selectedBefore == null) {
            selectedBefore = cases.firstOrNull { it.caseId != selectedAfter }?.caseId
        }
    }

    LaunchedEffect(Unit) { refreshCases() }

    val beforeCase = cases.firstOrNull { it.caseId == selectedBefore }
    val afterCase = cases.firstOrNull { it.caseId == selectedAfter }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete saved case?") },
            text = {
                Text(
                    "${target.label} will be permanently removed from this device. This does not delete the source pages or any exported copies."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        caseScope.launch {
                            val deleted = store.deleteAsync(target.caseId)
                            actionNotice = if (deleted) {
                                "Saved case deleted from this device."
                            } else {
                                "Saved case could not be deleted; the encrypted case was retained."
                            }
                            if (deleted) refreshCases()
                        }
                    }
                ) {
                    Text("Delete", color = NeuralTheme.Crimson, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
            containerColor = NeuralTheme.CardBackground
        )
    }

    AnimatedObsidianBackground(showGrid = false)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Text(
            text = "Saved cases",
            color = NeuralTheme.TextPrimary,
            fontSize = 25.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "Encrypted on this device. Compare scans, correlate reused media, correct attribution, and track cleanup work without altering raw evidence.",
            color = NeuralTheme.TextSecondary,
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
        )
        actionNotice?.let { notice ->
            Text(
                text = notice,
                color = NeuralTheme.Emerald,
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        if (cases.isEmpty()) {
            EmptyCasesState()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text(
                        text = "Select comparison points",
                        color = NeuralTheme.TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }

                items(cases, key = { it.caseId }) { case ->
                    CaseSelectionCard(
                        case = case,
                        isBefore = selectedBefore == case.caseId,
                        isAfter = selectedAfter == case.caseId,
                        onSelectBefore = {
                            selectedBefore = if (selectedBefore == case.caseId) null else case.caseId
                            if (selectedAfter == case.caseId) selectedAfter = null
                        },
                        onSelectAfter = {
                            selectedAfter = if (selectedAfter == case.caseId) null else case.caseId
                            if (selectedBefore == case.caseId) selectedBefore = null
                        },
                        onDelete = { pendingDelete = case }
                    )
                }

                if (beforeCase == null) {
                    afterCase?.let { reviewCase ->
                        item(key = "review-${reviewCase.caseId}") {
                            RenderCaseReviewItem(
                                case = reviewCase,
                                store = store,
                                aiInsightService = aiInsightService,
                                exporter = exporter,
                                onChanged = { notice ->
                                    caseScope.launch {
                                        actionNotice = notice
                                        refreshCases()
                                    }
                                },
                                onShareNotice = {
                                    actionNotice = "Share-safe export prepared. Review the generated files before sharing."
                                }
                            )
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    when {
                        beforeCase != null && afterCase != null -> {
                            val diff = remember(beforeCase.caseId, afterCase.caseId) {
                                CaseComparison().compare(beforeCase, afterCase)
                            }
                            RenderDiff(beforeCase.label, afterCase.label, diff)
                            val mediaHistory = remember(beforeCase.caseId, afterCase.caseId) {
                                CaseComparison().mediaClusterHistory(listOf(beforeCase, afterCase))
                            }
                            RenderMediaClusterHistory(mediaHistory)
                        }
                        afterCase != null -> RenderSingleCase(afterCase, onNavigateToBrowser)
                        beforeCase != null -> RenderSingleCase(beforeCase, onNavigateToBrowser)
                        else -> Text(
                            text = "Choose at least one saved case to view its snapshot.",
                            color = NeuralTheme.TextSecondary,
                            fontSize = 13.sp
                        )
                    }
                }

                if (beforeCase != null) {
                    afterCase?.let { reviewCase ->
                        item(key = "review-${reviewCase.caseId}") {
                            RenderCaseReviewItem(
                                case = reviewCase,
                                store = store,
                                aiInsightService = aiInsightService,
                                exporter = exporter,
                                onChanged = { notice ->
                                    caseScope.launch {
                                        actionNotice = notice
                                        refreshCases()
                                    }
                                },
                                onShareNotice = {
                                    actionNotice = "Share-safe export prepared. Review the generated files before sharing."
                                }
                            )
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(32.dp)) }
            }
        }
    }
}

@Composable
private fun RenderCaseReviewItem(
    case: DossierCase,
    store: CaseStore,
    aiInsightService: AiInsightService,
    exporter: ReportExporter,
    onChanged: (String) -> Unit,
    onShareNotice: () -> Unit
) {
    CaseReviewPanel(
        case = case,
        store = store,
        aiInsightService = aiInsightService,
        onShareRedacted = {
            exporter.shareReport(
                findings = case.findings,
                subjectName = case.subjectName,
                profileSummaries = case.profileResults.map { result ->
                    "${result.candidate.platform.name}: ${result.candidate.url} · exists=${result.exists} · verified=${result.verified}"
                },
                aiSummary = case.aiSummary,
                faceMatches = case.faceMatches,
                entityGraphSummary = case.entityGraph.entities.joinToString("\n") { entity ->
                    "${entity.type}: ${entity.label}"
                },
                breachDigests = case.breachDigests.map { digest ->
                    "${digest.email}: ${digest.breachCount} breach record(s)"
                },
                riskLevel = case.riskLevel.name,
                redactionMode = ExportRedactionMode.ShareSafe
            )
            onShareNotice()
        },
        onChanged = onChanged
    )
}

@Composable
private fun EmptyCasesState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(NeuralTheme.CardBackground, DossierButtonShape)
            .border(1.dp, NeuralTheme.BorderColor, DossierButtonShape)
            .padding(18.dp)
    ) {
        Text(
            text = "No saved cases",
            color = NeuralTheme.TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "Run a scan and choose Save encrypted case on the report. Saving is always explicit.",
            color = NeuralTheme.TextSecondary,
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun CaseSelectionCard(
    case: DossierCase,
    isBefore: Boolean,
    isAfter: Boolean,
    onSelectBefore: () -> Unit,
    onSelectAfter: () -> Unit,
    onDelete: () -> Unit
) {
    val borderColor = when {
        isAfter -> NeuralTheme.Cobalt
        isBefore -> NeuralTheme.Amber
        else -> NeuralTheme.BorderColor
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DossierButtonShape)
            .background(NeuralTheme.CardBackground)
            .border(1.dp, borderColor, DossierButtonShape)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = case.label,
                    color = NeuralTheme.TextPrimary,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${case.findings.size} findings · ${case.riskLevel.name.lowercase()} risk" +
                        (case.exposure?.let { " · exposure ${it.overall}" } ?: ""),
                    color = NeuralTheme.TextSecondary,
                    fontSize = 11.5.sp,
                    modifier = Modifier.padding(top = 3.dp)
                )
                val imageCandidateCount = case.mediaIntelligence.imageResults.sumOf { it.visualCandidates.size }
                if (case.evidenceRecords.isNotEmpty() || imageCandidateCount > 0) {
                    Text(
                        text = "${case.evidenceRecords.size} provenance records · $imageCandidateCount image candidates",
                        color = NeuralTheme.TextMuted,
                        fontSize = 10.5.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                if (case.userCorrections.isNotEmpty() || case.remediationRecords.isNotEmpty()) {
                    Text(
                        text = "${case.userCorrections.size} correction(s) · ${case.remediationRecords.size} tracked action(s)",
                        color = NeuralTheme.TextMuted,
                        fontSize = 10.5.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                case.scanHistory.lastOrNull()?.let { scan ->
                    Text(
                        text = "${scan.mode.displayName} scan · ${scan.profileResultCount} profiles · ${scan.findingCount} findings" +
                            when {
                                scan.failed -> " · failed · ${sanitizeTerminalFailureCode(scan.failureCode) ?: "SCAN_FAILED"}"
                                scan.cancelled -> " · cancelled"
                                else -> ""
                            },
                        color = NeuralTheme.TextMuted,
                        fontSize = 10.5.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "Delete ${case.label}",
                    tint = NeuralTheme.Crimson
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onSelectBefore,
                modifier = Modifier.weight(1f).height(48.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (isBefore) NeuralTheme.Amber else NeuralTheme.TextSecondary
                )
            ) {
                Text(if (isBefore) "Older ✓" else "Set older", fontSize = 11.5.sp)
            }
            OutlinedButton(
                onClick = onSelectAfter,
                modifier = Modifier.weight(1f).height(48.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (isAfter) NeuralTheme.Cobalt else NeuralTheme.TextSecondary
                )
            ) {
                Text(if (isAfter) "Newer ✓" else "Set newer", fontSize = 11.5.sp)
            }
        }
    }
}

@Composable
private fun RenderDiff(
    beforeLabel: String,
    afterLabel: String,
    diff: CaseComparison.CaseDiff
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DossierButtonShape)
            .background(NeuralTheme.CardBackground)
            .border(1.dp, NeuralTheme.BorderColor, DossierButtonShape)
            .padding(18.dp)
    ) {
        Text("Changes", color = NeuralTheme.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(
            text = "$beforeLabel  →  $afterLabel",
            color = NeuralTheme.TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
        )
        DeltaRow("Risk score", diff.riskDelta, positiveIsGood = false)
        DeltaRow("Exposure score", diff.exposureDelta, positiveIsGood = false)
        DeltaRow("Discovered profiles", diff.profilesAdded - diff.profilesRemoved, positiveIsGood = null)
        DeltaRow("Known breaches", diff.breachesAdded - diff.breachesRemoved, positiveIsGood = false)

        val media = diff.media
        if (
            media.exactContentReused > 0 || media.perceptualFingerprintsReused > 0 ||
            media.clustersAdded != 0 || media.clustersRemoved != 0 ||
            media.sourcePagesAdded != 0 || media.sourcePagesRemoved != 0
        ) {
            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = NeuralTheme.BorderColor, thickness = 0.7.dp)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Cross-scan media correlation",
                color = NeuralTheme.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Hash reuse tracks copied/reposted image content between these saved cases; it does not by itself establish identity.",
                color = NeuralTheme.TextSecondary,
                fontSize = 10.5.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
            )
            MediaMetricRow("Exact SHA-256 reused", media.exactContentReused)
            MediaMetricRow("Perceptual hashes reused", media.perceptualFingerprintsReused)
            DeltaRow("Image clusters", media.clustersAdded - media.clustersRemoved, positiveIsGood = null)
            DeltaRow("Image source pages", media.sourcePagesAdded - media.sourcePagesRemoved, positiveIsGood = null)
        }

        Spacer(modifier = Modifier.height(10.dp))
        HorizontalDivider(color = NeuralTheme.BorderColor, thickness = 0.7.dp)
        Spacer(modifier = Modifier.height(8.dp))
        DiffList("Added evidence", diff.added.map { it.value to it.risk }, NeuralTheme.Crimson)
        DiffList("Removed evidence", diff.removed.map { it.value to it.risk }, NeuralTheme.Emerald)
        if (diff.changed.isNotEmpty()) {
            Text(
                text = "Changed evidence (${diff.changed.size})",
                color = NeuralTheme.Amber,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
            )
            diff.changed.forEach {
                Text(
                    text = "• ${it.finding.value} (${it.finding.risk.name.lowercase()})",
                    color = NeuralTheme.TextPrimary,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp
                )
            }
        }

        val sourceEvidenceChanges = diff.evidenceChanges.filter {
            it.change != CaseComparison.EvidenceChangeKind.UNCHANGED
        }
        if (sourceEvidenceChanges.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = NeuralTheme.BorderColor, thickness = 0.7.dp)
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Historical/provider evidence changes (${sourceEvidenceChanges.size})",
                color = NeuralTheme.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Only records with the same canonical source target are compared. Not observed is not proof of deletion, and unavailable archives remain explicitly unavailable.",
                color = NeuralTheme.TextSecondary,
                fontSize = 10.5.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(top = 3.dp, bottom = 5.dp)
            )
            sourceEvidenceChanges.take(MAX_EVIDENCE_CHANGE_ROWS).forEach { change ->
                EvidenceChangeRow(change)
            }
            if (sourceEvidenceChanges.size > MAX_EVIDENCE_CHANGE_ROWS) {
                Text(
                    text = "${sourceEvidenceChanges.size - MAX_EVIDENCE_CHANGE_ROWS} additional source-scoped changes omitted by the bounded comparison view.",
                    color = NeuralTheme.TextMuted,
                    fontSize = 10.5.sp,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }

        if (diff.remediationVerification.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = NeuralTheme.BorderColor, thickness = 0.7.dp)
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Remediation recheck",
                color = NeuralTheme.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Verification compares only these two assessment snapshots. A result disappearing from the newer scan is not proof that search indexes, caches, archives, or every live copy are gone.",
                color = NeuralTheme.TextSecondary,
                fontSize = 10.5.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(top = 3.dp, bottom = 5.dp)
            )
            diff.remediationVerification.forEach { verification ->
                RemediationVerificationRow(verification)
            }
        }
    }
}

@Composable
private fun EvidenceChangeRow(change: CaseComparison.EvidenceChange) {
    val evidence = change.after ?: change.before ?: return
    val label = when (change.change) {
        CaseComparison.EvidenceChangeKind.ADDED -> "ADDED"
        CaseComparison.EvidenceChangeKind.NOT_OBSERVED_IN_LATEST_CASE -> "NOT OBSERVED IN LATEST CASE"
        CaseComparison.EvidenceChangeKind.CHANGED -> "CHANGED"
        CaseComparison.EvidenceChangeKind.UNCHANGED -> "UNCHANGED"
        CaseComparison.EvidenceChangeKind.UNAVAILABLE -> "UNAVAILABLE"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .semantics {
                contentDescription = buildString {
                    append("$label ${change.semanticKind} evidence")
                    if (change.historical) append("; historical")
                    append(". ${change.explanation}")
                }
            },
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(
                label,
                color = when (change.change) {
                    CaseComparison.EvidenceChangeKind.UNAVAILABLE -> NeuralTheme.Amber
                    CaseComparison.EvidenceChangeKind.NOT_OBSERVED_IN_LATEST_CASE -> NeuralTheme.TextSecondary
                    else -> NeuralTheme.Cobalt
                },
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                buildString {
                    append(change.semanticKind)
                    if (change.historical) append(" · HISTORICAL")
                },
                color = NeuralTheme.TextSecondary,
                fontSize = 10.5.sp
            )
        }
        Text(
            evidence.value,
            color = NeuralTheme.TextPrimary,
            fontSize = 11.5.sp,
            maxLines = 2
        )
        Text(
            change.explanation,
            color = NeuralTheme.TextSecondary,
            fontSize = 10.5.sp,
            lineHeight = 15.sp
        )
        evidence.sourceUrl?.takeIf {
            it.startsWith("https://", ignoreCase = true) ||
                it.startsWith("http://", ignoreCase = true)
        }?.let { source ->
            Text(
                source,
                color = NeuralTheme.Cobalt,
                fontSize = 10.5.sp,
                maxLines = 2
            )
        }
        HorizontalDivider(color = NeuralTheme.BorderColor.copy(alpha = 0.7f))
    }
}

@Composable
private fun MediaMetricRow(label: String, value: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = NeuralTheme.TextSecondary, fontSize = 11.5.sp)
        Text(value.toString(), color = NeuralTheme.Cobalt, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun RenderMediaClusterHistory(
    history: List<CaseComparison.MediaClusterHistoryEntry>
) {
    if (history.isEmpty()) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(DossierButtonShape)
            .background(NeuralTheme.CardBackground)
            .border(1.dp, NeuralTheme.BorderColor, DossierButtonShape)
            .padding(14.dp)
    ) {
        Text(
            text = "Saved-case image cluster review",
            color = NeuralTheme.TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "Repeated whole-image fingerprints are grouped across the selected cases. This is public-image provenance only and does not establish that accounts or people are the same.",
            color = NeuralTheme.TextSecondary,
            fontSize = 10.5.sp,
            lineHeight = 15.sp,
            modifier = Modifier.padding(top = 3.dp, bottom = 7.dp)
        )

        history.take(MAX_VISIBLE_MEDIA_HISTORY).forEachIndexed { index, entry ->
            if (index > 0) HorizontalDivider(color = NeuralTheme.BorderColor, thickness = 0.7.dp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 7.dp)
                    .semantics {
                        contentDescription = buildString {
                            append(entry.type.displayLabel())
                            append(" image cluster; observed in ")
                            append(entry.caseCount)
                            append(" saved case(s)")
                        }
                        stateDescription = if (entry.caseCount > 1) {
                            "Repeated whole-image evidence"
                        } else {
                            "Single-case whole-image evidence"
                        }
                    }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = entry.type.displayLabel(),
                        color = NeuralTheme.TextPrimary,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = if (entry.caseCount > 1) {
                            "${entry.caseCount} saved cases"
                        } else {
                            "1 saved case"
                        },
                        color = NeuralTheme.Cobalt,
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                entry.fingerprint?.let { fingerprint ->
                    Text(
                        text = "Fingerprint: ${fingerprint.take(MAX_VISIBLE_FINGERPRINT_CHARS)}" +
                            if (fingerprint.length > MAX_VISIBLE_FINGERPRINT_CHARS) "…" else "",
                        color = NeuralTheme.TextMuted,
                        fontSize = 9.5.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                } ?: Text(
                    text = "No reusable fingerprint; retained as a case-local cluster.",
                    color = NeuralTheme.TextMuted,
                    fontSize = 9.5.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )

                entry.observations.take(MAX_VISIBLE_MEDIA_OBSERVATIONS).forEach { observation ->
                    Text(
                        text = "${observation.caseLabel} · ${observation.members.size} public images · cluster ${observation.clusterId.takeLast(12)}",
                        color = NeuralTheme.TextSecondary,
                        fontSize = 10.5.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(top = 5.dp)
                    )
                    observation.members.take(MAX_VISIBLE_MEDIA_MEMBERS).forEach { member ->
                        Text(
                            text = "• ${member.title.ifBlank { "Public image candidate" }} · ${member.state.name}",
                            color = NeuralTheme.TextPrimary,
                            fontSize = 10.5.sp,
                            lineHeight = 15.sp,
                            modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                        )
                        Text(
                            text = "  ${member.source} · ${member.sourcePageUrl}",
                            color = NeuralTheme.TextMuted,
                            fontSize = 9.5.sp,
                            lineHeight = 14.sp,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                        member.retrievedAtEpochMillis?.let { retrievedAt ->
                            Text(
                                text = "  retrieved ${Instant.ofEpochMilli(retrievedAt)}" +
                                    (member.contentSha256?.let { " · SHA-256 ${it.take(12)}…" } ?: ""),
                                color = NeuralTheme.TextMuted,
                                fontSize = 9.sp,
                                lineHeight = 13.sp,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                    }
                    if (observation.members.size > MAX_VISIBLE_MEDIA_MEMBERS) {
                        Text(
                            text = "… ${observation.members.size - MAX_VISIBLE_MEDIA_MEMBERS} more member(s) retained in the encrypted case",
                            color = NeuralTheme.TextMuted,
                            fontSize = 9.5.sp,
                            modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                        )
                    }
                }
                if (entry.observations.size > MAX_VISIBLE_MEDIA_OBSERVATIONS) {
                    Text(
                        text = "… ${entry.observations.size - MAX_VISIBLE_MEDIA_OBSERVATIONS} more saved-case observation(s)",
                        color = NeuralTheme.TextMuted,
                        fontSize = 9.5.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
        if (history.size > MAX_VISIBLE_MEDIA_HISTORY) {
            Text(
                text = "… ${history.size - MAX_VISIBLE_MEDIA_HISTORY} more cluster group(s) retained in the encrypted case",
                color = NeuralTheme.TextMuted,
                fontSize = 9.5.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

private fun ReverseImageLookupResult.ImageClusterType.displayLabel(): String = when (this) {
    ReverseImageLookupResult.ImageClusterType.ExactContent -> "Exact-content image cluster"
    ReverseImageLookupResult.ImageClusterType.PerceptualNearDuplicate -> "Perceptual near-duplicate cluster"
}

@Composable
private fun DeltaRow(label: String, value: Int, positiveIsGood: Boolean?) {
    val color = when {
        value == 0 -> NeuralTheme.TextSecondary
        positiveIsGood == null -> NeuralTheme.Cobalt
        value > 0 && positiveIsGood -> NeuralTheme.Emerald
        value < 0 && !positiveIsGood -> NeuralTheme.Emerald
        else -> NeuralTheme.Crimson
    }
    val sign = if (value > 0) "+" else ""
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = NeuralTheme.TextSecondary, fontSize = 12.5.sp)
        Text(
            text = "$sign$value",
            color = color,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun DiffList(title: String, items: List<Pair<String, RiskLevel>>, color: Color) {
    if (items.isEmpty()) return
    Text(
        text = "$title (${items.size})",
        color = color,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 9.dp, bottom = 4.dp)
    )
    items.forEach { (value, risk) ->
        Text(
            text = "• [${risk.name}] $value",
            color = NeuralTheme.TextPrimary,
            fontSize = 11.5.sp,
            lineHeight = 16.sp
        )
    }
}

@Composable
private fun RemediationVerificationRow(
    verification: CaseComparison.RemediationVerification
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = verification.state.displayLabel(),
                color = remediationVerificationColor(verification.state),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = buildString {
                    append(verification.beforeStatus.displayLabel())
                    verification.afterStatus?.let { append(" → ${it.displayLabel()}") }
                },
                color = NeuralTheme.TextMuted,
                fontSize = 9.5.sp
            )
        }
        Text(
            text = verification.explanation,
            color = NeuralTheme.TextSecondary,
            fontSize = 10.5.sp,
            lineHeight = 15.sp,
            modifier = Modifier.padding(top = 2.dp)
        )
        verification.observedEvidenceId?.let { evidenceId ->
            Text(
                text = "Observed evidence: $evidenceId",
                color = NeuralTheme.TextMuted,
                fontSize = 9.5.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        verification.verificationScanId?.let { scanId ->
            Text(
                text = "Verification scan: $scanId",
                color = NeuralTheme.TextMuted,
                fontSize = 9.5.sp,
                modifier = Modifier.padding(top = 1.dp)
            )
        }
    }
}

@Composable
private fun RenderSingleCase(case: DossierCase, onNavigateToBrowser: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DossierButtonShape)
            .background(NeuralTheme.CardBackground)
            .border(1.dp, NeuralTheme.BorderColor, DossierButtonShape)
            .padding(18.dp)
    ) {
        Text("Snapshot", color = NeuralTheme.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(
            text = case.label,
            color = NeuralTheme.TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 2.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "${case.findings.size} findings · ${case.riskLevel.name.lowercase()} risk",
            color = NeuralTheme.TextPrimary,
            fontSize = 12.5.sp
        )
        val imageCandidateCount = case.mediaIntelligence.imageResults.sumOf { it.visualCandidates.size }
        if (case.evidenceRecords.isNotEmpty() || imageCandidateCount > 0) {
            Text(
                text = "${case.evidenceRecords.size} provenance records · $imageCandidateCount image candidates",
                color = NeuralTheme.TextSecondary,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        val timeline = remember(case) { CaseTimelineBuilder.presentation(case, limit = 240) }
        Text(
            text = "Timeline: ${timeline.availability.currentObservationCount} verified current · " +
                "${timeline.availability.otherObservationCount} other observations · " +
                "${timeline.availability.historicalObservationCount} historical · " +
                "${timeline.availability.timestampedEvidenceCount} timestamped",
            color = NeuralTheme.TextSecondary,
            fontSize = 11.5.sp,
            modifier = Modifier.padding(top = 5.dp)
        )
        if (timeline.availability.archiveUnavailableCount > 0) {
            Text(
                text = "Historical lookup unavailable for ${timeline.availability.archiveUnavailableCount} archived record(s).",
                color = NeuralTheme.Amber,
                fontSize = 10.5.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        case.exposure?.let { exposure ->
            Text(
                text = "Exposure: ${exposure.dimensions.joinToString(", ") { dimension -> "${dimension.dimension.name.lowercase()} ${dimension.score}" }}",
                color = NeuralTheme.TextSecondary,
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        case.scanHistory.lastOrNull()?.let { scan ->
            Text(
                text = "Last scan: ${scan.mode.displayName} · ${scan.profileResultCount} profiles · ${scan.findingCount} findings" +
                    when {
                        scan.failed -> " · failed · ${sanitizeTerminalFailureCode(scan.failureCode) ?: "SCAN_FAILED"}"
                        scan.cancelled -> " · cancelled"
                        else -> ""
                    },
                color = NeuralTheme.TextSecondary,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(top = 5.dp)
            )
        }
        HistoricalTimelinePanelContent(
            case = case,
            onNavigateToBrowser = onNavigateToBrowser
        )
        RenderMediaClusterHistory(
            CaseComparison().mediaClusterHistory(listOf(case))
        )
        Text(
            text = "Choose another case as the other comparison point to calculate a delta.",
            color = NeuralTheme.TextMuted,
            fontSize = 11.5.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun CaseReviewPanel(
    case: DossierCase,
    store: CaseStore,
    aiInsightService: AiInsightService,
    onShareRedacted: () -> Unit,
    onChanged: (String) -> Unit
) {
    var showAllEvidence by remember(case.caseId) { mutableStateOf(false) }
    var showAllAccounts by remember(case.caseId) { mutableStateOf(false) }
    var showAllActions by remember(case.caseId) { mutableStateOf(false) }
    var isReanalyzing by remember(case.caseId) { mutableStateOf(false) }
    var reanalysisError by remember(case.caseId) { mutableStateOf<String?>(null) }
    val reanalysisScope = rememberCoroutineScope()
    val context = LocalContext.current

    val latestEvidenceCorrections = case.userCorrections.filter { it.evidenceId != null }.associateBy { it.evidenceId!! }
    val latestEntityCorrections = case.userCorrections.filter { it.entityId != null }.associateBy { it.entityId!! }
    val distinctFindings = case.findings.distinctBy(case::findingKey)
    val visibleFindings = if (showAllEvidence) distinctFindings else distinctFindings.take(REVIEW_PREVIEW_LIMIT)
    val accounts = case.entityGraph.entities.filter { it.type == EntityType.Profile }.distinctBy { it.id }
    val visibleAccounts = if (showAllAccounts) accounts else accounts.take(REVIEW_PREVIEW_LIMIT)
    val actionableFindings = distinctFindings.filter { it.remediation.isNotBlank() }
    val visibleActions = if (showAllActions) actionableFindings else actionableFindings.take(REVIEW_PREVIEW_LIMIT)
    val remediationByKey = case.remediationRecords.associateBy(RemediationRecord::findingKey)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DossierButtonShape)
            .background(NeuralTheme.CardBackground)
            .border(1.dp, NeuralTheme.Cobalt.copy(alpha = 0.45f), DossierButtonShape)
            .padding(18.dp)
    ) {
        Text("Review newer case", color = NeuralTheme.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(
            text = "Corrections affect later analysis and graph linkage. Raw evidence remains encrypted in the case unless you delete the whole case.",
            color = NeuralTheme.TextSecondary,
            fontSize = 11.5.sp,
            lineHeight = 16.sp,
            modifier = Modifier.padding(top = 3.dp, bottom = 12.dp)
        )

        ReviewSectionTitle(
            "Saved-case AI analysis",
            "Analysis is generated from this encrypted snapshot and is never evidence by itself. Re-run after corrections or remediation changes."
        )
        case.aiSummary?.takeIf { it.isNotBlank() }?.let { summary ->
            Text(
                text = aiSummaryPrivacyStatus(summary),
                color = NeuralTheme.TextMuted,
                fontSize = 10.5.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(bottom = 5.dp)
            )
            Text(
                text = summary,
                color = NeuralTheme.TextPrimary,
                fontSize = 12.5.sp,
                lineHeight = 18.sp
            )
        }
        if (case.aiSummaryNeedsRefresh) {
            Text(
                text = "Stored analysis is stale after a saved-case change and must not be treated as the current corrected view.",
                color = NeuralTheme.Amber,
                fontSize = 10.5.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(top = 5.dp)
            )
        }
        Text(
            text = savedCaseAiPrivacyStatus(
                configuredSavedCaseAiRemotePermission(context) == AiRemotePermission.AllowRedactedEvidence
            ),
            color = NeuralTheme.TextSecondary,
            fontSize = 10.5.sp,
            lineHeight = 15.sp,
            modifier = Modifier.padding(top = 5.dp)
        )
        if (savedCaseAiActionVisible(case)) {
            OutlinedButton(
                onClick = {
                    if (!isReanalyzing) {
                        isReanalyzing = true
                        reanalysisError = null
                        reanalysisScope.launch {
                            val outcome = try {
                                withContext(Dispatchers.IO) {
                                    val loaded = store.loadAsync(case.caseId)
                                    if (loaded == null) {
                                        SavedCaseAiReanalysisOutcome.Failure(
                                            "The saved case could not be loaded. Existing analysis and refresh state were preserved."
                                        )
                                    } else {
                                        reanalyzeSavedCaseSnapshot(
                                            loadedCase = loaded,
                                            store = store,
                                            aiInsightService = aiInsightService,
                                            remotePermission = configuredSavedCaseAiRemotePermission(context)
                                        )
                                    }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                SavedCaseAiReanalysisOutcome.Failure(
                                    "Saved-case analysis failed. Existing analysis and refresh state were preserved."
                                )
                            }
                            isReanalyzing = false
                            when (outcome) {
                                is SavedCaseAiReanalysisOutcome.Success -> {
                                    reanalysisError = null
                                    onChanged(
                                        "Saved-case analysis refreshed from the stored snapshot. " +
                                            savedCaseAiPrivacyStatus(
                                                outcome.remotePermission == AiRemotePermission.AllowRedactedEvidence
                                            )
                                    )
                                }
                                is SavedCaseAiReanalysisOutcome.Failure -> {
                                    reanalysisError = outcome.message
                                }
                            }
                        }
                    }
                },
                enabled = !isReanalyzing,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .semantics {
                        contentDescription = "Re-run saved-case analysis"
                        stateDescription = if (isReanalyzing) "Analysis running" else "Analysis ready"
                    },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = NeuralTheme.Cobalt)
            ) {
                Text(
                    if (isReanalyzing) "Re-running analysis…" else "Re-run analysis",
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        if (isReanalyzing) {
            Text(
                text = "Analyzing the exact loaded case snapshot…",
                color = NeuralTheme.Cobalt,
                fontSize = 10.5.sp,
                modifier = Modifier.padding(top = 5.dp)
            )
        }
        reanalysisError?.let { message ->
            Text(
                text = message,
                color = NeuralTheme.Crimson,
                fontSize = 10.5.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(top = 5.dp)
            )
        }

        ReviewSectionTitle("Evidence decisions", "Mark attribution, uncertainty, or exclude an item from analysis.")
        if (distinctFindings.isEmpty()) {
            Text("No finding evidence to review.", color = NeuralTheme.TextMuted, fontSize = 11.5.sp)
        } else {
            visibleFindings.forEach { finding ->
                val evidence = finding.toEvidence()
                val current = latestEvidenceCorrections[evidence.id]?.decision
                EvidenceCorrectionRow(
                    finding = finding,
                    current = current,
                    onDecision = { decision ->
                        reanalysisScope.launch {
                            val ok = store.recordCorrectionAsync(
                                case.caseId,
                                UserCorrection(
                                    evidenceId = evidence.id,
                                    decision = decision,
                                    createdAtUtc = Instant.now().toString()
                                )
                            )
                            onChanged(
                                if (ok) {
                                    "Evidence decision saved to the encrypted case."
                                } else {
                                    "Evidence decision could not be saved."
                                }
                            )
                        }
                    }
                )
            }
            if (distinctFindings.size > REVIEW_PREVIEW_LIMIT) {
                TextButton(onClick = { showAllEvidence = !showAllEvidence }) {
                    Text(if (showAllEvidence) "Show fewer evidence items" else "Show all ${distinctFindings.size} evidence items")
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
        HorizontalDivider(color = NeuralTheme.BorderColor)
        Spacer(modifier = Modifier.height(14.dp))
        ReviewSectionTitle("Account attribution", "Account decisions directly update effective graph membership.")
        if (accounts.isEmpty()) {
            Text("No account nodes to review.", color = NeuralTheme.TextMuted, fontSize = 11.5.sp)
        } else {
            visibleAccounts.forEach { entity ->
                val current = latestEntityCorrections[entity.id]?.decision
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                    Text(entity.label, color = NeuralTheme.TextPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                    entity.sourceUrls.firstOrNull()?.let { url ->
                        Text(url, color = NeuralTheme.TextMuted, fontSize = 10.5.sp, maxLines = 1)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CorrectionButton("This is me", current == UserCorrectionDecision.ThisIsMe, Modifier.weight(1f)) {
                            saveEntityCorrection(
                                scope = reanalysisScope,
                                store = store,
                                case = case,
                                entityId = entity.id,
                                decision = UserCorrectionDecision.ThisIsMe,
                                onChanged = onChanged
                            )
                        }
                        CorrectionButton("Not me", current == UserCorrectionDecision.ThisIsNotMe, Modifier.weight(1f)) {
                            saveEntityCorrection(
                                scope = reanalysisScope,
                                store = store,
                                case = case,
                                entityId = entity.id,
                                decision = UserCorrectionDecision.ThisIsNotMe,
                                onChanged = onChanged
                            )
                        }
                        CorrectionButton("Unsure", current == UserCorrectionDecision.Unsure, Modifier.weight(1f)) {
                            saveEntityCorrection(
                                scope = reanalysisScope,
                                store = store,
                                case = case,
                                entityId = entity.id,
                                decision = UserCorrectionDecision.Unsure,
                                onChanged = onChanged
                            )
                        }
                    }
                }
            }
            if (accounts.size > REVIEW_PREVIEW_LIMIT) {
                TextButton(onClick = { showAllAccounts = !showAllAccounts }) {
                    Text(if (showAllAccounts) "Show fewer accounts" else "Show all ${accounts.size} accounts")
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
        HorizontalDivider(color = NeuralTheme.BorderColor)
        Spacer(modifier = Modifier.height(14.dp))
        ReviewSectionTitle(
            "Remediation tracking",
            "Status records your workflow only. Completed does not prove the remote source was removed; verify with a later scan."
        )
        if (actionableFindings.isEmpty()) {
            Text("No remediation actions were generated for this case.", color = NeuralTheme.TextMuted, fontSize = 11.5.sp)
        } else {
            visibleActions.forEach { finding ->
                val key = case.findingKey(finding)
                val existing = remediationByKey[key]
                RemediationRow(
                    finding = finding,
                    current = existing?.status ?: RemediationStatus.NotStarted,
                    onStatus = { status ->
                        val now = Instant.now().toString()
                        val record = existing?.copy(status = status, updatedAtUtc = now) ?: RemediationRecord(
                            findingKey = key,
                            sourceUrl = finding.sourceUrl,
                            action = finding.remediation,
                            status = status,
                            createdAtUtc = now,
                            updatedAtUtc = now
                        )
                        reanalysisScope.launch {
                            val ok = store.upsertRemediationAsync(case.caseId, record)
                            onChanged(
                                if (ok) {
                                    "Remediation status saved. Re-scan later to verify the exposure state."
                                } else {
                                    "Remediation status could not be saved."
                                }
                            )
                        }
                    }
                )
            }
            if (actionableFindings.size > REVIEW_PREVIEW_LIMIT) {
                TextButton(onClick = { showAllActions = !showAllActions }) {
                    Text(if (showAllActions) "Show fewer actions" else "Show all ${actionableFindings.size} actions")
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
        HorizontalDivider(color = NeuralTheme.BorderColor)
        Spacer(modifier = Modifier.height(14.dp))
        ReviewSectionTitle(
            "Share-safe export",
            "Creates PDF + JSON from redacted data. Direct values, source URLs, snippets, graph labels, breach identifiers and generated analysis are removed or generalized before files are written."
        )
        OutlinedButton(
            onClick = onShareRedacted,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = NeuralTheme.Cobalt)
        ) {
            Text("Share redacted case report", fontWeight = FontWeight.SemiBold)
        }
        Text(
            text = "Redaction reduces disclosure but cannot guarantee that every contextual clue is anonymous. Review the generated files before sharing.",
            color = NeuralTheme.TextMuted,
            fontSize = 10.5.sp,
            lineHeight = 15.sp,
            modifier = Modifier.padding(top = 5.dp)
        )
    }
}

@Composable
private fun EvidenceCorrectionRow(
    finding: Finding,
    current: UserCorrectionDecision?,
    onDecision: (UserCorrectionDecision) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(finding.type.name, color = NeuralTheme.TextSecondary, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                Text(finding.value, color = NeuralTheme.TextPrimary, fontSize = 12.5.sp, maxLines = 2)
            }
            Text(
                text = current?.displayLabel() ?: "Unreviewed",
                color = correctionColor(current),
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CorrectionButton("Mine", current == UserCorrectionDecision.ThisIsMe, Modifier.weight(1f)) {
                onDecision(UserCorrectionDecision.ThisIsMe)
            }
            CorrectionButton("Not mine", current == UserCorrectionDecision.ThisIsNotMe, Modifier.weight(1f)) {
                onDecision(UserCorrectionDecision.ThisIsNotMe)
            }
            CorrectionButton("Unsure", current == UserCorrectionDecision.Unsure, Modifier.weight(1f)) {
                onDecision(UserCorrectionDecision.Unsure)
            }
            CorrectionButton("Ignore", current == UserCorrectionDecision.IgnoreEvidence, Modifier.weight(1f)) {
                onDecision(UserCorrectionDecision.IgnoreEvidence)
            }
        }
    }
}

@Composable
private fun RemediationRow(
    finding: Finding,
    current: RemediationStatus,
    onStatus: (RemediationStatus) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        // Keep the action copy and workflow state on separate rows. The action is
        // user-authored/provider-authored text and can be long enough to wrap;
        // placing a right-aligned status in the same Row makes the last line
        // collide with it on narrow screens (and at larger font scales).
        Text(
            text = finding.remediation,
            color = NeuralTheme.TextPrimary,
            fontSize = 12.5.sp,
            lineHeight = 17.sp
        )
        Text(
            text = finding.value,
            color = NeuralTheme.TextMuted,
            fontSize = 10.5.sp,
            lineHeight = 15.sp,
            modifier = Modifier.padding(top = 2.dp)
        )
        Text(
            text = current.displayLabel(),
            color = remediationColor(current),
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            StatusButton("Start", current == RemediationStatus.InProgress, Modifier.weight(1f)) {
                onStatus(RemediationStatus.InProgress)
            }
            StatusButton("Submitted", current == RemediationStatus.Submitted, Modifier.weight(1f)) {
                onStatus(RemediationStatus.Submitted)
            }
            StatusButton("Waiting", current == RemediationStatus.AwaitingResponse, Modifier.weight(1f)) {
                onStatus(RemediationStatus.AwaitingResponse)
            }
            StatusButton("Complete", current == RemediationStatus.Completed, Modifier.weight(1f)) {
                onStatus(RemediationStatus.Completed)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            StatusButton("Reset", current == RemediationStatus.NotStarted, Modifier.weight(1f)) {
                onStatus(RemediationStatus.NotStarted)
            }
            StatusButton("Rejected", current == RemediationStatus.Rejected, Modifier.weight(1f)) {
                onStatus(RemediationStatus.Rejected)
            }
            StatusButton("Manual", current == RemediationStatus.NeedsManualAction, Modifier.weight(1f)) {
                onStatus(RemediationStatus.NeedsManualAction)
            }
        }
    }
}

@Composable
private fun CorrectionButton(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .height(48.dp)
            .semantics {
                this.selected = selected
                stateDescription = if (selected) "Selected" else "Not selected"
                contentDescription = "$label correction, ${if (selected) "selected" else "not selected"}"
            },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = if (selected) NeuralTheme.Cobalt else NeuralTheme.TextSecondary
        )
    ) {
        Text(label, fontSize = 9.5.sp, maxLines = 1)
    }
}

@Composable
private fun StatusButton(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .height(48.dp)
            .semantics {
                this.selected = selected
                stateDescription = if (selected) "Selected" else "Not selected"
                contentDescription = "$label remediation status, ${if (selected) "selected" else "not selected"}"
            },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = if (selected) NeuralTheme.Emerald else NeuralTheme.TextSecondary
        )
    ) {
        Text(label, fontSize = 9.sp, maxLines = 1)
    }
}

@Composable
private fun ReviewSectionTitle(title: String, subtitle: String) {
    Text(title, color = NeuralTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    Text(
        subtitle,
        color = NeuralTheme.TextSecondary,
        fontSize = 10.5.sp,
        lineHeight = 15.sp,
        modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
    )
}

private fun saveEntityCorrection(
    scope: CoroutineScope,
    store: CaseStore,
    case: DossierCase,
    entityId: String,
    decision: UserCorrectionDecision,
    onChanged: (String) -> Unit
) {
    scope.launch {
        val ok = store.recordCorrectionAsync(
            case.caseId,
            UserCorrection(
                entityId = entityId,
                decision = decision,
                createdAtUtc = Instant.now().toString()
            )
        )
        onChanged(
            if (ok) "Account attribution decision saved to the encrypted case."
            else "Account attribution decision could not be saved."
        )
    }
}

private fun UserCorrectionDecision.displayLabel(): String = when (this) {
    UserCorrectionDecision.ThisIsMe -> "Confirmed by user"
    UserCorrectionDecision.ThisIsNotMe -> "Rejected by user"
    UserCorrectionDecision.Unsure -> "User unsure"
    UserCorrectionDecision.IgnoreEvidence -> "Ignored in analysis"
}

@Composable
private fun correctionColor(decision: UserCorrectionDecision?): Color = when (decision) {
    UserCorrectionDecision.ThisIsMe -> NeuralTheme.Emerald
    UserCorrectionDecision.ThisIsNotMe -> NeuralTheme.Crimson
    UserCorrectionDecision.Unsure -> NeuralTheme.Amber
    UserCorrectionDecision.IgnoreEvidence -> NeuralTheme.TextMuted
    null -> NeuralTheme.TextMuted
}

private fun RemediationStatus.displayLabel(): String = when (this) {
    RemediationStatus.NotStarted -> "Not started"
    RemediationStatus.InProgress -> "In progress"
    RemediationStatus.Submitted -> "Submitted"
    RemediationStatus.AwaitingResponse -> "Awaiting response"
    RemediationStatus.Completed -> "Completed — verify"
    RemediationStatus.Rejected -> "Rejected"
    RemediationStatus.NeedsManualAction -> "Manual action"
}

private fun CaseComparison.RemediationVerificationState.displayLabel(): String = when (this) {
    CaseComparison.RemediationVerificationState.NotRechecked -> "Not rechecked"
    CaseComparison.RemediationVerificationState.StillObserved -> "Still observed"
    CaseComparison.RemediationVerificationState.NotObservedInLatestScan -> "Not observed in latest scan"
    CaseComparison.RemediationVerificationState.StatusChanged -> "Workflow status changed"
}

@Composable
private fun remediationVerificationColor(
    state: CaseComparison.RemediationVerificationState
): Color = when (state) {
    CaseComparison.RemediationVerificationState.StillObserved -> NeuralTheme.Crimson
    CaseComparison.RemediationVerificationState.NotObservedInLatestScan -> NeuralTheme.Emerald
    CaseComparison.RemediationVerificationState.StatusChanged -> NeuralTheme.Cobalt
    CaseComparison.RemediationVerificationState.NotRechecked -> NeuralTheme.TextMuted
}

@Composable
private fun remediationColor(status: RemediationStatus): Color = when (status) {
    RemediationStatus.Completed -> NeuralTheme.Emerald
    RemediationStatus.Rejected -> NeuralTheme.Crimson
    RemediationStatus.Submitted,
    RemediationStatus.AwaitingResponse,
    RemediationStatus.InProgress -> NeuralTheme.Cobalt
    RemediationStatus.NeedsManualAction -> NeuralTheme.Amber
    RemediationStatus.NotStarted -> NeuralTheme.TextMuted
}

private sealed interface SavedCaseAiReanalysisOutcome {
    data class Success(val remotePermission: AiRemotePermission) : SavedCaseAiReanalysisOutcome

    data class Failure(val message: String) : SavedCaseAiReanalysisOutcome
}

internal fun savedCaseAiActionVisible(case: DossierCase): Boolean =
    case.aiSummaryNeedsRefresh || case.aiSummary.isNullOrBlank()

internal fun savedCaseAiRemotePermission(
    hasUsableRemoteProvider: Boolean
): AiRemotePermission = if (hasUsableRemoteProvider) {
    AiRemotePermission.AllowRedactedEvidence
} else {
    AiRemotePermission.Denied
}

private fun configuredSavedCaseAiRemotePermission(context: android.content.Context): AiRemotePermission {
    val hasUsableRemoteProvider = runCatching {
        AiProviderConfigStore(context).firstUsableRemoteProvider() != null
    }.getOrDefault(false)
    return savedCaseAiRemotePermission(hasUsableRemoteProvider)
}

private fun savedCaseAiPrivacyStatus(hasUsableRemoteProvider: Boolean): String = if (hasUsableRemoteProvider) {
    "Remote AI is configured; only the service's redacted evidence snapshot may leave this device when you run analysis."
} else {
    "Remote AI is not enabled; this analysis stays on-device unless you explicitly configure a usable provider."
}

private fun aiSummaryPrivacyStatus(summary: String): String {
    val source = summary.lineSequence()
        .firstOrNull { it.startsWith("Analysis source:") }
        ?: "Analysis source: not reported"
    val network = summary.lineSequence()
        .firstOrNull { it.startsWith("Network used for analysis:") }
        ?: "Network used for analysis: not reported"
    val policy = summary.lineSequence()
        .firstOrNull { it.startsWith("Input policy:") }
        ?: "Input policy: not reported"
    return "$source · $network · $policy"
}

private suspend fun reanalyzeSavedCaseSnapshot(
    loadedCase: DossierCase,
    store: CaseStore,
    aiInsightService: AiInsightService,
    remotePermission: AiRemotePermission
): SavedCaseAiReanalysisOutcome {
    val summary = aiInsightService.summarizeCase(
        case = loadedCase,
        remotePermission = remotePermission
    )?.trim()?.takeIf { it.isNotBlank() }
        ?: return SavedCaseAiReanalysisOutcome.Failure(
            "No validated analysis result was returned. Existing analysis and refresh state were preserved."
        )
    return when (store.saveAnalysisIfUnchangedAsync(loadedCase, summary)) {
        CaseAnalysisUpdateResult.Applied -> SavedCaseAiReanalysisOutcome.Success(remotePermission)
        CaseAnalysisUpdateResult.Conflict -> SavedCaseAiReanalysisOutcome.Failure(
            "The saved case changed while analysis was running. The current case was preserved; re-run analysis again."
        )
        CaseAnalysisUpdateResult.MissingCase -> SavedCaseAiReanalysisOutcome.Failure(
            "The saved case was removed while analysis was running. Existing analysis and refresh state were preserved."
        )
        CaseAnalysisUpdateResult.StorageFailure -> SavedCaseAiReanalysisOutcome.Failure(
            "The refreshed analysis could not be saved. Existing analysis and refresh state were preserved."
        )
    }
}

private const val REVIEW_PREVIEW_LIMIT = 8
private const val MAX_VISIBLE_MEDIA_HISTORY = 8
private const val MAX_VISIBLE_MEDIA_OBSERVATIONS = 4
private const val MAX_VISIBLE_MEDIA_MEMBERS = 4
private const val MAX_VISIBLE_FINGERPRINT_CHARS = 36
private const val MAX_EVIDENCE_CHANGE_ROWS = 40
