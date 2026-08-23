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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.dossier.app.domain.case.CaseComparison
import io.dossier.app.domain.case.CaseStore
import io.dossier.app.domain.case.DossierCase
import io.dossier.app.domain.case.RemediationRecord
import io.dossier.app.domain.case.RemediationStatus
import io.dossier.app.domain.discovery.sanitizeTerminalFailureCode
import io.dossier.app.domain.case.UserCorrection
import io.dossier.app.domain.case.UserCorrectionDecision
import io.dossier.app.domain.evidence.toEvidence
import io.dossier.app.domain.model.EntityType
import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.RiskLevel
import io.dossier.app.export.ExportRedactionMode
import io.dossier.app.export.ReportExporter
import io.dossier.app.ui.components.AnimatedObsidianBackground
import io.dossier.app.ui.theme.DossierButtonShape
import io.dossier.app.ui.theme.NeuralTheme
import java.time.Instant

/** Explicit local saved-case selection, comparison, correction and remediation. */
@Composable
fun CaseComparisonScreen() {
    val context = LocalContext.current
    val store = remember { CaseStore(context) }
    val exporter = remember { ReportExporter(context) }
    var cases by remember { mutableStateOf(emptyList<DossierCase>()) }
    var selectedBefore by remember { mutableStateOf<String?>(null) }
    var selectedAfter by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<DossierCase?>(null) }
    var actionNotice by remember { mutableStateOf<String?>(null) }

    fun refreshCases() {
        cases = store.list()
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
                        store.delete(target.caseId)
                        pendingDelete = null
                        actionNotice = "Saved case deleted from this device."
                        refreshCases()
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

                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    when {
                        beforeCase != null && afterCase != null -> {
                            val diff = remember(beforeCase.caseId, afterCase.caseId) {
                                CaseComparison().compare(beforeCase, afterCase)
                            }
                            RenderDiff(beforeCase.label, afterCase.label, diff)
                        }
                        afterCase != null -> RenderSingleCase(afterCase)
                        beforeCase != null -> RenderSingleCase(beforeCase)
                        else -> Text(
                            text = "Choose at least one saved case to view its snapshot.",
                            color = NeuralTheme.TextSecondary,
                            fontSize = 13.sp
                        )
                    }
                }

                afterCase?.let { reviewCase ->
                    item(key = "review-${reviewCase.caseId}") {
                        CaseReviewPanel(
                            case = reviewCase,
                            store = store,
                            onShareRedacted = {
                                exporter.shareReport(
                                    findings = reviewCase.findings,
                                    subjectName = reviewCase.subjectName,
                                    profileSummaries = reviewCase.profileResults.map { result ->
                                        "${result.candidate.platform.name}: ${result.candidate.url} · exists=${result.exists} · verified=${result.verified}"
                                    },
                                    aiSummary = reviewCase.aiSummary,
                                    faceMatches = reviewCase.faceMatches,
                                    entityGraphSummary = reviewCase.entityGraph.entities.joinToString("\n") { entity ->
                                        "${entity.type}: ${entity.label}"
                                    },
                                    breachDigests = reviewCase.breachDigests.map { digest ->
                                        "${digest.email}: ${digest.breachCount} breach record(s)"
                                    },
                                    riskLevel = reviewCase.riskLevel.name,
                                    redactionMode = ExportRedactionMode.ShareSafe
                                )
                                actionNotice = "Share-safe export prepared. Review the generated files before sharing."
                            },
                            onChanged = { notice ->
                                actionNotice = notice
                                refreshCases()
                            }
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(32.dp)) }
            }
        }
    }
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
    }
}

@Composable
private fun RenderSingleCase(case: DossierCase) {
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
    onShareRedacted: () -> Unit,
    onChanged: (String) -> Unit
) {
    var showAllEvidence by remember(case.caseId) { mutableStateOf(false) }
    var showAllAccounts by remember(case.caseId) { mutableStateOf(false) }
    var showAllActions by remember(case.caseId) { mutableStateOf(false) }

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
                        val ok = store.recordCorrection(
                            case.caseId,
                            UserCorrection(
                                evidenceId = evidence.id,
                                decision = decision,
                                createdAtUtc = Instant.now().toString()
                            )
                        )
                        onChanged(if (ok) "Evidence decision saved to the encrypted case." else "Evidence decision could not be saved.")
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
                            saveEntityCorrection(store, case, entity.id, UserCorrectionDecision.ThisIsMe, onChanged)
                        }
                        CorrectionButton("Not me", current == UserCorrectionDecision.ThisIsNotMe, Modifier.weight(1f)) {
                            saveEntityCorrection(store, case, entity.id, UserCorrectionDecision.ThisIsNotMe, onChanged)
                        }
                        CorrectionButton("Unsure", current == UserCorrectionDecision.Unsure, Modifier.weight(1f)) {
                            saveEntityCorrection(store, case, entity.id, UserCorrectionDecision.Unsure, onChanged)
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
                        val ok = store.upsertRemediation(case.caseId, record)
                        onChanged(
                            if (ok) "Remediation status saved. Re-scan later to verify the exposure state."
                            else "Remediation status could not be saved."
                        )
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
        modifier = modifier.height(48.dp),
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
        modifier = modifier.height(48.dp),
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
    store: CaseStore,
    case: DossierCase,
    entityId: String,
    decision: UserCorrectionDecision,
    onChanged: (String) -> Unit
) {
    val ok = store.recordCorrection(
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

private const val REVIEW_PREVIEW_LIMIT = 8
