package io.dossier.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.dossier.app.domain.case.EffectiveCaseProjection
import io.dossier.app.domain.case.UserCorrection
import io.dossier.app.domain.case.UserCorrectionDecision
import io.dossier.app.domain.evidence.AttackPathFinder
import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceRuntimeCache
import io.dossier.app.domain.evidence.ExposureEngine
import io.dossier.app.domain.evidence.persistedEvidenceId
import io.dossier.app.domain.evidence.toEvidence
import io.dossier.app.domain.discovery.ScanHistoryRuntime
import io.dossier.app.domain.graph.GraphEvidenceReconciliationReport
import io.dossier.app.domain.graph.graphEvidenceReconciliation
import io.dossier.app.domain.model.BreachDigest
import io.dossier.app.domain.model.EntityGraph
import io.dossier.app.domain.model.FaceConsistencyMatch
import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.FindingType
import io.dossier.app.domain.model.ProfileScanResult
import io.dossier.app.domain.model.RiskLevel
import io.dossier.app.domain.remediation.RemediationItem
import io.dossier.app.domain.scanner.ScanSession
import io.dossier.app.export.ExportRedactionMode
import io.dossier.app.export.GraphExportService
import io.dossier.app.export.ReportExporter
import io.dossier.app.ui.components.AnimatedObsidianBackground
import io.dossier.app.ui.labels.userFacingStatusLabel
import io.dossier.app.ui.theme.DossierButtonShape
import io.dossier.app.ui.theme.DossierCardShape
import io.dossier.app.ui.theme.NeuralTheme
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ReportView(val label: String) {
    Overview("Overview"),
    Evidence("Evidence"),
    Timeline("Timeline"),
    Connections("Connections"),
    Actions("Actions")
}

@Composable
fun ReportScreen(
    onReset: () -> Unit,
    onNavigateToBrowser: (String) -> Unit,
    onDeepResearch: () -> Unit = {}
) {
    val context = LocalContext.current
    val findings by ScanSession.findings.collectAsState()
    val faceMatches by ScanSession.faceConsistencyMatches.collectAsState()
    val riskLevel by ScanSession.riskLevel.collectAsState()
    val remediationTips by ScanSession.remediationTips.collectAsState()
    val remediationItems by ScanSession.remediationItems.collectAsState()
    val aiSummary by ScanSession.aiSummary.collectAsState()
    val input by ScanSession.currentInput.collectAsState()
    val memoryDropped by ScanSession.memoryDropped.collectAsState()
    val profileResults by ScanSession.profileScanResults.collectAsState()
    val entityGraph by ScanSession.entityGraph.collectAsState()
    val relationshipConfidence by ScanSession.relationshipConfidence.collectAsState()
    val exposure by ScanSession.exposure.collectAsState()
    val attackPaths by ScanSession.attackPaths.collectAsState()
    val breachDigests by ScanSession.breachDigests.collectAsState()
    val scanHistory by ScanSession.scanHistory.collectAsState()
    val draftCorrections by ScanSession.userCorrections.collectAsState()
    val evidenceCollection by EvidenceRuntimeCache.collection.collectAsState()

    var selectedViewIndex by rememberSaveable { mutableIntStateOf(0) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var draftCorrectionMessage by remember { mutableStateOf<String?>(null) }
    var confirmSessionDelete by remember { mutableStateOf(false) }
    val generatedAt by rememberSaveable {
        mutableStateOf(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
    }
    val exporter = remember { ReportExporter(context) }
    val graphExporter = remember(context) { GraphExportService(context) }
    val purgeScope = rememberCoroutineScope()

    val subject = input?.fullName?.trim().orEmpty()
        .ifBlank { input?.primaryUsername?.let { "@$it" }.orEmpty() }
        .ifBlank { input?.emails?.firstOrNull().orEmpty() }
        .ifBlank { "Unnamed subject" }
    val effectiveCase = remember(
        input,
        findings,
        profileResults,
        entityGraph,
        breachDigests,
        exposure,
        scanHistory,
        evidenceCollection,
        draftCorrections
    ) {
        if (draftCorrections.isEmpty() || evidenceCollection.evidence.isEmpty()) {
            null
        } else {
            ScanSession.buildCase()?.let { current ->
                EffectiveCaseProjection.from(
                    current.copy(
                        evidenceRecords = evidenceCollection.evidence,
                        userCorrections = draftCorrections
                    )
                ).presentationCase()
            }
        }
    }
    val reportFindings = effectiveCase?.findings ?: findings
    val reportProfileResults = effectiveCase?.profileResults ?: profileResults
    val reportEntityGraph = effectiveCase?.entityGraph ?: entityGraph
    val reportRiskLevel = effectiveCase?.riskLevel ?: riskLevel
    val reportExposure = effectiveCase?.exposure ?: exposure
    // Keep scanner/plugin assertions separate from the graph projection. When
    // corrections are absent this reads the current DossierCase snapshot;
    // it never reconstructs assertions from graph edges.
    val reportCanonicalRelationships = effectiveCase?.canonicalEvidenceRelationships()
        ?: ScanSession.buildCase()?.canonicalEvidenceRelationships().orEmpty()
    // Keep this diagnostic tied to the exact read-only report snapshot. It
    // compares persisted canonical assertions with graph material, but never
    // rewrites either collection or treats extra derived edges as corruption.
    val reportGraphReconciliation = remember(
        effectiveCase,
        reportEntityGraph,
        reportCanonicalRelationships,
        evidenceCollection.evidence
    ) {
        (effectiveCase ?: ScanSession.buildCase())?.graphEvidenceReconciliation()
    }
    val onShareSafeGraphExport: (() -> Unit)? = if (reportEntityGraph.entities.isNotEmpty()) {
        {
            val exportCase = (effectiveCase ?: ScanSession.buildCase())
                ?.copy(entityGraph = reportEntityGraph)
            if (exportCase != null) {
                graphExporter.share(
                    case = exportCase,
                    label = subject,
                    redactionMode = ExportRedactionMode.ShareSafe
                )
            } else {
                graphExporter.share(
                    graph = reportEntityGraph,
                    label = subject,
                    redactionMode = ExportRedactionMode.ShareSafe
                )
            }
        }
    } else null
    val draftCorrectionByEvidence = remember(draftCorrections) {
        draftCorrections
            .filter { it.evidenceId != null }
            .associateBy { it.evidenceId!! }
    }
    val verifiedProfiles = reportProfileResults.count { it.exists && it.verified }
    val reviewProfiles = reportProfileResults.count { it.exists && !it.verified }
    val unavailableProfiles = reportProfileResults.count {
        !it.exists && it.verificationStatus?.contains("unverifiable", true) == true
    }
    val confirmedBreaches = breachDigests.sumOf(BreachDigest::breachCount)
    val measuredFaceMatches = faceMatches.count { match ->
        match.warning.contains("Measured", ignoreCase = true) &&
            (match.warning.contains("high visual similarity", true) ||
                match.warning.contains("review-range", true))
    }
    val entityGraphLines = remember(reportEntityGraph, reportFindings, reportProfileResults) {
        formatEntityGraphFromSession(reportEntityGraph)
            .ifEmpty { formatEntityGraphLines(reportFindings, reportProfileResults) }
    }
    val breachLines = remember(breachDigests, reportFindings) {
        formatBreachDigestsFromSession(breachDigests)
            .ifEmpty { formatBreachDigestLines(reportFindings) }
    }
    val timelineCase = remember(
        input,
        findings,
        profileResults,
        faceMatches,
        entityGraph,
        breachDigests,
        scanHistory,
        evidenceCollection,
        draftCorrections
    ) {
        ScanSession.buildCase()?.let { current ->
            val history = if (current.scanHistory.isNotEmpty()) {
                current.scanHistory
            } else {
                input?.let(ScanHistoryRuntime::latestFor)?.let(::listOf).orEmpty()
            }
            current.copy(
                evidenceRecords = evidenceCollection.evidence,
                scanHistory = history
            )
        }
    }

    if (confirmSessionDelete) {
        AlertDialog(
            onDismissRequest = { confirmSessionDelete = false },
            title = { Text("Delete current session data?") },
            text = {
                Text(
                    "This clears the active scan, temporary image/profile caches, and in-memory report. Saved encrypted cases and exported files are not deleted."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmSessionDelete = false
                        purgeScope.launch {
                            ScanSession.purgeSessionAsync(context)
                            withContext(Dispatchers.Main.immediate) {
                                onReset()
                            }
                        }
                    }
                ) {
                    Text("Delete session", color = NeuralTheme.Crimson, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmSessionDelete = false }) { Text("Cancel") }
            },
            containerColor = NeuralTheme.CardBackground
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedObsidianBackground(showGrid = false)
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                Text(
                    text = "Privacy audit report",
                    color = NeuralTheme.TextPrimary,
                    fontSize = 27.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { heading() }
                )
                Text(
                    text = subject,
                    color = NeuralTheme.Cobalt,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 3.dp)
                )
                Text(
                    text = "Generated $generatedAt · public-source evidence · manual review required",
                    color = NeuralTheme.TextSecondary,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }

            // Keep every report view reachable without ellipsizing a tab label
            // on a phone-width layout. The selected tab is brought into view
            // automatically while the row remains horizontally scrollable.
            ScrollableTabRow(
                selectedTabIndex = selectedViewIndex,
                containerColor = NeuralTheme.CardBackground,
                contentColor = NeuralTheme.Cobalt,
                edgePadding = 12.dp,
                divider = { HorizontalDivider(color = NeuralTheme.BorderColor) }
            ) {
                ReportTabItems(
                    selectedIndex = selectedViewIndex,
                    onSelected = { selectedViewIndex = it }
                )
            }

            when (ReportView.entries[selectedViewIndex]) {
                ReportView.Overview -> OverviewReport(
                    modifier = Modifier.weight(1f),
                    riskLevel = reportRiskLevel,
                    exposure = reportExposure,
                    findings = reportFindings,
                    verifiedProfiles = verifiedProfiles,
                    reviewProfiles = reviewProfiles,
                    unavailableProfiles = unavailableProfiles,
                    confirmedBreaches = confirmedBreaches,
                    faceMatches = faceMatches,
                    measuredFaceMatches = measuredFaceMatches,
                    hasSelfie = !input?.selfieUri.isNullOrBlank(),
                    memoryDropped = memoryDropped,
                    aiSummary = aiSummary
                )
                ReportView.Evidence -> EvidenceReport(
                    modifier = Modifier.weight(1f),
                    findings = findings,
                    // Keep the raw profile observations in the correction view so a
                    // rejected/ignored card remains available for an explicit review
                    // decision. Overview/scoring still use the effective projection.
                    profileResults = profileResults,
                    evidenceRecords = evidenceCollection.evidence,
                    faceMatches = faceMatches,
                    breachDigests = breachDigests,
                    onNavigateToBrowser = onNavigateToBrowser,
                    draftCorrections = draftCorrectionByEvidence,
                    draftCorrectionMessage = draftCorrectionMessage,
                    onDraftCorrection = { evidenceId, decision ->
                        val accepted = ScanSession.recordDraftCorrection(
                            UserCorrection(
                                evidenceId = evidenceId,
                                decision = decision,
                                createdAtUtc = Instant.now().toString()
                            )
                        )
                        draftCorrectionMessage = if (accepted) {
                            "Draft evidence decision applied locally. Use Actions → Save encrypted case to persist it."
                        } else {
                            "Draft correction limit reached; no change was applied."
                        }
                    }
                )
                ReportView.Timeline -> if (timelineCase != null) {
                    HistoricalTimelinePanel(
                        case = timelineCase,
                        modifier = Modifier.weight(1f),
                        onNavigateToBrowser = onNavigateToBrowser
                    )
                } else {
                    TimelineUnavailablePanel(modifier = Modifier.weight(1f))
                }
                ReportView.Connections -> ConnectionsReport(
                    modifier = Modifier.weight(1f),
                    entityGraph = reportEntityGraph,
                    relationshipConfidence = relationshipConfidence,
                    attackPaths = attackPaths,
                    graphReconciliation = reportGraphReconciliation,
                    onShareSafeGraphExport = onShareSafeGraphExport
                )
                ReportView.Actions -> ActionsReport(
                    modifier = Modifier.weight(1f),
                    remediationItems = remediationItems,
                    remediationTips = remediationTips,
                    onNavigateToBrowser = onNavigateToBrowser,
                    actionMessage = actionMessage,
                    onSaveCase = {
                        purgeScope.launch {
                            actionMessage = if (ScanSession.saveCaseAsync(context) != null) {
                                "Encrypted case saved locally."
                            } else {
                                "The case could not be saved. No plaintext fallback was used."
                            }
                        }
                    },
                    onExport = {
                        exporter.shareReport(
                            findings = reportFindings,
                            subjectName = subject,
                            profileSummaries = reportProfileResults.map(::profileExportLine),
                            aiSummary = aiSummary,
                            faceMatches = faceMatches,
                            entityGraphSummary = entityGraphLines.joinToString("\n"),
                            canonicalRelationships = reportCanonicalRelationships,
                            breachDigests = breachLines,
                            riskLevel = reportRiskLevel.name
                        )
                    },
                    onShareSafeGraphExport = if (reportEntityGraph.entities.isNotEmpty()) onShareSafeGraphExport else null,
                    onDeepResearch = onDeepResearch,
                    onNewAudit = {
                        purgeScope.launch {
                            ScanSession.purgeSessionAsync(context)
                            withContext(Dispatchers.Main.immediate) {
                                onReset()
                            }
                        }
                    },
                    onDeleteSession = { confirmSessionDelete = true }
                )
            }
        }
    }
}

@Composable
private fun ReportTabItems(
    selectedIndex: Int,
    onSelected: (Int) -> Unit
) {
    ReportView.entries.forEachIndexed { index, view ->
        Tab(
            selected = selectedIndex == index,
            onClick = { onSelected(index) },
            modifier = Modifier.heightIn(min = 48.dp),
            text = {
                Text(
                    view.label,
                    fontSize = 12.sp,
                    fontWeight = if (selectedIndex == index) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
        )
    }
}

@Composable
private fun OverviewReport(
    modifier: Modifier,
    riskLevel: RiskLevel,
    exposure: ExposureEngine.ExposureResult?,
    findings: List<Finding>,
    verifiedProfiles: Int,
    reviewProfiles: Int,
    unavailableProfiles: Int,
    confirmedBreaches: Int,
    faceMatches: List<FaceConsistencyMatch>,
    measuredFaceMatches: Int,
    hasSelfie: Boolean,
    memoryDropped: Int,
    aiSummary: String?
) {
    val priorityColor = riskColor(riskLevel)
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            ReportCard(borderColor = priorityColor.copy(alpha = 0.55f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Exposure priority", color = NeuralTheme.TextSecondary, fontSize = 12.sp)
                        Text(
                            riskLevel.name,
                            color = priorityColor,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            riskExplanation(riskLevel),
                            color = NeuralTheme.TextSecondary,
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp,
                            modifier = Modifier.padding(top = 3.dp)
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            findings.size.toString(),
                            color = NeuralTheme.TextPrimary,
                            fontSize = 25.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text("reportable findings", color = NeuralTheme.TextSecondary, fontSize = 11.sp)
                        exposure?.let {
                            Text(
                                "Exposure score ${it.overall}/100",
                                color = NeuralTheme.TextSecondary,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        item {
            SectionHeading("Coverage inspected", "Coverage is bounded by public sources and provider availability.")
            ReportCard {
                CoverageRow("Directly verified profiles", verifiedProfiles, NeuralTheme.Emerald)
                CoverageRow("Review-only profile candidates", reviewProfiles, NeuralTheme.Amber)
                CoverageRow("Unverifiable profile checks", unavailableProfiles, NeuralTheme.TextSecondary)
                CoverageRow(
                    "Confirmed HIBP breach records",
                    confirmedBreaches,
                    if (confirmedBreaches > 0) NeuralTheme.Crimson else NeuralTheme.TextSecondary
                )
                CoverageRow("Local visual comparisons", faceMatches.size, NeuralTheme.Cobalt)
            }
        }

        exposure?.let { result ->
            item {
                SectionHeading("Exposure dimensions", "Scores show severity, not certainty.")
                ReportCard {
                    result.dimensions.forEach { dimension ->
                        ExposureRow(dimension)
                        Spacer(modifier = Modifier.height(9.dp))
                    }
                }
            }
        }

        if (memoryDropped > 0) {
            item {
                NoticeCard(
                    "$memoryDropped finding(s) were omitted after the in-memory report limit was reached. Exported conclusions should be treated as incomplete.",
                    NeuralTheme.Amber
                )
            }
        }

        item {
            NoticeCard(
                faceConsistencySummary(hasSelfie, faceMatches.size, measuredFaceMatches),
                if (measuredFaceMatches > 0) NeuralTheme.Cobalt else NeuralTheme.TextSecondary
            )
        }

        if (findings.isEmpty()) {
            item {
                NoticeCard(
                    "No reportable finding was detected in the sources inspected. This does not prove that no public exposure exists; private, blocked, deleted, and unindexed content may be absent from this report.",
                    NeuralTheme.TextSecondary
                )
            }
        } else {
            item { SectionHeading("Highest-priority evidence", "Risk and attribution confidence are shown separately.") }
            items(findings.sortedWith(findingOrder()).take(5)) { finding ->
                FindingCard(finding = finding, onNavigateToBrowser = null)
            }
        }

        if (!aiSummary.isNullOrBlank()) {
            item {
                SectionHeading("Analysis", "The text below states which engine was used and whether network analysis occurred.")
                ReportCard {
                    Text(aiSummary, color = NeuralTheme.TextPrimary, fontSize = 13.sp, lineHeight = 19.sp)
                }
            }
        }
    }
}

@Composable
private fun EvidenceReport(
    modifier: Modifier,
    findings: List<Finding>,
    profileResults: List<ProfileScanResult>,
    evidenceRecords: List<Evidence>,
    faceMatches: List<FaceConsistencyMatch>,
    breachDigests: List<BreachDigest>,
    onNavigateToBrowser: (String) -> Unit,
    draftCorrections: Map<String, UserCorrection>,
    draftCorrectionMessage: String?,
    onDraftCorrection: (String, UserCorrectionDecision) -> Unit
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionHeading(
                "Evidence",
                "Open sources to verify them manually. Confidence measures attribution support; risk measures potential impact."
            )
            Text(
                "Draft decisions update this report locally. Use Actions → Save encrypted case to persist the raw evidence and decisions.",
                color = NeuralTheme.TextSecondary,
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 5.dp)
            )
            draftCorrectionMessage?.let { message ->
                NoticeCard(message, NeuralTheme.Cobalt)
            }
        }
        if (findings.isEmpty()) {
            item { NoticeCard("No reportable evidence in the inspected source set.", NeuralTheme.TextSecondary) }
        } else {
            items(findings.sortedWith(findingOrder()), key = { findingKey(it) }) { finding ->
                FindingCard(
                    finding = finding,
                    onNavigateToBrowser = onNavigateToBrowser,
                    currentCorrection = draftCorrections[finding.toEvidence().id]?.decision,
                    onDraftCorrection = { decision ->
                        onDraftCorrection(finding.toEvidence().id, decision)
                    }
                )
            }
        }

        item { SectionHeading("Profiles", "Verified, review-only, unavailable, and not-found states remain distinct.") }
        if (profileResults.isEmpty()) {
            item { NoticeCard("No profile checks were recorded.", NeuralTheme.TextSecondary) }
        } else {
            items(profileResults, key = { it.candidate.url }) { result ->
                val evidenceId = result.persistedEvidenceId(evidenceRecords)
                ProfileEvidenceCard(
                    result = result,
                    onNavigateToBrowser = onNavigateToBrowser,
                    evidenceId = evidenceId,
                    currentCorrection = evidenceId?.let(draftCorrections::get)?.decision,
                    onDraftCorrection = evidenceId?.let { id ->
                        { decision -> onDraftCorrection(id, decision) }
                    }
                )
            }
        }

        item { SectionHeading("Local visual comparison", "A visual score is supporting evidence, never ownership proof.") }
        if (faceMatches.isEmpty()) {
            item { NoticeCard("No local visual comparison result was available.", NeuralTheme.TextSecondary) }
        } else {
            items(faceMatches, key = FaceConsistencyMatch::profileUrl) { match ->
                ReportCard {
                    Text(
                        match.profileUrl,
                        color = NeuralTheme.Cobalt,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable(role = Role.Button) { onNavigateToBrowser(match.profileUrl) }
                            .semantics {
                                contentDescription = "Open visual match profile ${match.profileUrl}"
                            }
                    )
                    Text(
                        "Similarity score ${"%.3f".format(match.similarityScore)}",
                        color = NeuralTheme.TextSecondary,
                        fontSize = 11.5.sp,
                        modifier = Modifier.padding(top = 5.dp)
                    )
                    Text(
                        match.warning,
                        color = NeuralTheme.TextPrimary,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(top = 5.dp)
                    )
                }
            }
        }

        item { SectionHeading("Breach coverage", "Authoritative records and ordinary public mentions are not interchangeable.") }
        if (breachDigests.isEmpty()) {
            item { NoticeCard("No email breach coverage result was recorded for this scan.", NeuralTheme.TextSecondary) }
        } else {
            items(breachDigests, key = BreachDigest::email) { digest ->
                ReportCard(
                    borderColor = if (digest.breachCount > 0) NeuralTheme.Crimson.copy(alpha = 0.5f) else NeuralTheme.BorderColor
                ) {
                    Text(digest.email, color = NeuralTheme.TextPrimary, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (digest.breachCount > 0) {
                            "${digest.breachCount} authoritative breach record(s)"
                        } else {
                            "No authoritative breach record stored in this result"
                        },
                        color = if (digest.breachCount > 0) NeuralTheme.Crimson else NeuralTheme.TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    if (digest.sources.isNotEmpty()) {
                        Text(
                            digest.sources.take(8).joinToString(", "),
                            color = NeuralTheme.TextSecondary,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    digest.note?.let {
                        Text(it, color = NeuralTheme.TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
        item { Spacer(modifier = Modifier.height(20.dp)) }
    }
}

@Composable
private fun ConnectionsReport(
    modifier: Modifier,
    entityGraph: EntityGraph,
    relationshipConfidence: Map<String, io.dossier.app.domain.evidence.RelationshipConfidence>,
    attackPaths: List<AttackPathFinder.AttackPath>,
    graphReconciliation: GraphEvidenceReconciliationReport?,
    onShareSafeGraphExport: (() -> Unit)? = null
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            SectionHeading("Relationships", "Connections are evidence paths, not proof that two accounts share an owner.")
            ReportCard {
                EntityGraphView(graph = entityGraph, confidenceByEdge = relationshipConfidence)
            }
        }
        item {
            SectionHeading(
                "Graph/evidence consistency",
                "Read-only diagnostics compare canonical evidence relationships with persisted graph edges."
            )
            GraphReconciliationCard(graphReconciliation)
        }
        if (entityGraph.entities.isNotEmpty() && onShareSafeGraphExport != null) {
            item {
                SectionHeading(
                    "Graph export",
                    "Export topological structure for Gephi/Cytoscape analysis. Shared exports are redacted to prevent identity leakage."
                )
                ReportCard {
                    Text(
                        "Share-safe graph export",
                        color = NeuralTheme.TextPrimary,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Redacts subject labels, account names, source URLs, and evidence IDs while deterministically preserving graph topology, relationship types, and node states. Graph connections do not prove identity.",
                        color = NeuralTheme.TextSecondary,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                    )
                    OutlinedButton(
                        onClick = onShareSafeGraphExport,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = DossierButtonShape,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NeuralTheme.Cobalt)
                    ) {
                        Text("Share-safe graph export (GraphML + CSV + JSON)", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    }
                }
            }
        }
        item {
            SectionHeading(
                "Exposure pathways",
                "These paths explain how recorded evidence connects the subject to a breach endpoint."
            )
        }
        if (attackPaths.isEmpty()) {
            item { NoticeCard("No breach pathway was derivable from the recorded graph.", NeuralTheme.TextSecondary) }
        } else {
            items(attackPaths, key = { it.endpointLabel }) { path -> ExposurePathCard(path) }
        }
        item { Spacer(modifier = Modifier.height(20.dp)) }
    }
}

@Composable
private fun GraphReconciliationCard(report: GraphEvidenceReconciliationReport?) {
    val summary = graphReconciliationUiSummary(report)
    ReportCard(borderColor = if (summary.hasDiagnosticIssues) NeuralTheme.Amber else NeuralTheme.BorderColor) {
        Text(
            summary.statusLabel,
            color = NeuralTheme.TextPrimary,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics {
                stateDescription = summary.statusLabel
                contentDescription = "Graph/evidence reconciliation: ${summary.statusLabel}. ${summary.detail}"
            }
        )
        Text(
            summary.detail,
            color = NeuralTheme.TextSecondary,
            fontSize = 11.5.sp,
            lineHeight = 16.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
        Text(
            "No graph or evidence records were changed by this diagnostic.",
            color = NeuralTheme.TextSecondary,
            fontSize = 11.5.sp,
            lineHeight = 16.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun ActionsReport(
    modifier: Modifier,
    remediationItems: List<RemediationItem>,
    remediationTips: List<String>,
    onNavigateToBrowser: (String) -> Unit,
    actionMessage: String?,
    onSaveCase: () -> Unit,
    onExport: () -> Unit,
    onShareSafeGraphExport: (() -> Unit)? = null,
    onDeepResearch: () -> Unit,
    onNewAudit: () -> Unit,
    onDeleteSession: () -> Unit
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionHeading(
                "Recommended actions",
                "Prioritize source removal, de-indexing, credential rotation, and reduced cross-account correlation."
            )
        }
        if (remediationItems.isNotEmpty()) {
            items(remediationItems.take(20)) { item -> RemediationCard(item, onNavigateToBrowser) }
        } else {
            items(remediationTips) { tip -> NoticeCard(tip, NeuralTheme.TextSecondary) }
        }

        item {
            SectionHeading("Report controls", "Saving and exporting are explicit. Nothing is uploaded by these actions.")
            actionMessage?.let { NoticeCard(it, NeuralTheme.Cobalt) }
            Button(
                onClick = onSaveCase,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = DossierButtonShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeuralTheme.Cobalt,
                    contentColor = NeuralTheme.OnAccent
                )
            ) { Text("Save encrypted case", fontWeight = FontWeight.SemiBold) }
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButton(
                onClick = onExport,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = NeuralTheme.Cobalt)
            ) { Text("Export PDF + evidence JSON", fontWeight = FontWeight.SemiBold) }
            Spacer(modifier = Modifier.height(10.dp))
            if (onShareSafeGraphExport != null) {
                OutlinedButton(
                    onClick = onShareSafeGraphExport,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NeuralTheme.Cobalt)
                ) { Text("Share-safe graph export (GraphML + CSV + JSON)", fontWeight = FontWeight.SemiBold) }
                Spacer(modifier = Modifier.height(10.dp))
            }
            OutlinedButton(
                onClick = onDeepResearch,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = NeuralTheme.Cobalt)
            ) { Text("Run expanded public-link scan", fontWeight = FontWeight.SemiBold) }
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButton(
                onClick = onNewAudit,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = NeuralTheme.TextPrimary)
            ) { Text("Start a new audit", fontWeight = FontWeight.SemiBold) }
            Spacer(modifier = Modifier.height(10.dp))
            TextButton(
                onClick = onDeleteSession,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Text("Delete current session data", color = NeuralTheme.Crimson, fontWeight = FontWeight.SemiBold)
            }
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun FindingCard(
    finding: Finding,
    onNavigateToBrowser: ((String) -> Unit)?,
    currentCorrection: UserCorrectionDecision? = null,
    onDraftCorrection: ((UserCorrectionDecision) -> Unit)? = null
) {
    ReportCard(borderColor = riskColor(finding.risk).copy(alpha = 0.5f)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(finding.type.displayName(), color = NeuralTheme.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    finding.value,
                    color = NeuralTheme.TextPrimary,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
            RiskBadge(finding.risk)
        }
        finding.evidenceSnippet?.takeIf(String::isNotBlank)?.let {
            Text(it, color = NeuralTheme.TextSecondary, fontSize = 11.5.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 8.dp))
        }
        Text(
            "Attribution confidence ${(finding.confidence * 100).toInt()}%",
            color = NeuralTheme.TextSecondary,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
        LinearProgressIndicator(
            progress = { finding.confidence.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp).padding(top = 2.dp),
            color = NeuralTheme.Cobalt,
            trackColor = NeuralTheme.BorderColor
        )
        finding.sourceUrl?.takeIf(::isHttpUrl)?.let { source ->
            Text(
                source,
                color = NeuralTheme.Cobalt,
                fontSize = 11.5.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(
                        enabled = onNavigateToBrowser != null,
                        role = Role.Button
                    ) { onNavigateToBrowser?.invoke(source) }
                    .semantics {
                        contentDescription = "Open evidence source $source"
                    }
                    .padding(top = 9.dp, bottom = 4.dp)
            )
        }
        if (finding.remediation.isNotBlank()) {
            Text(
                "Suggested action: ${finding.remediation}",
                color = NeuralTheme.TextPrimary,
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        if (onDraftCorrection != null) {
            currentCorrection?.let { decision ->
                Text(
                    "Draft decision: ${decision.draftLabel()}. Raw evidence remains retained until encrypted case save.",
                    color = NeuralTheme.Amber,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            DraftCorrectionRow(
                current = currentCorrection,
                onDecision = onDraftCorrection,
                targetLabel = "evidence"
            )
        }
    }
}

@Composable
private fun DraftCorrectionRow(
    current: UserCorrectionDecision?,
    onDecision: (UserCorrectionDecision) -> Unit,
    targetLabel: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            "Draft $targetLabel decision · not saved",
            color = NeuralTheme.TextSecondary,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            DraftCorrectionButton(
                label = "Confirm",
                decision = UserCorrectionDecision.ThisIsMe,
                selected = current == UserCorrectionDecision.ThisIsMe,
                modifier = Modifier.weight(1f),
                targetLabel = targetLabel,
                onClick = onDecision
            )
            DraftCorrectionButton(
                label = "Reject",
                decision = UserCorrectionDecision.ThisIsNotMe,
                selected = current == UserCorrectionDecision.ThisIsNotMe,
                modifier = Modifier.weight(1f),
                targetLabel = targetLabel,
                onClick = onDecision
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            DraftCorrectionButton(
                label = "Unsure",
                decision = UserCorrectionDecision.Unsure,
                selected = current == UserCorrectionDecision.Unsure,
                modifier = Modifier.weight(1f),
                targetLabel = targetLabel,
                onClick = onDecision
            )
            DraftCorrectionButton(
                label = "Ignore",
                decision = UserCorrectionDecision.IgnoreEvidence,
                selected = current == UserCorrectionDecision.IgnoreEvidence,
                modifier = Modifier.weight(1f),
                targetLabel = targetLabel,
                onClick = onDecision
            )
        }
    }
}

@Composable
private fun DraftCorrectionButton(
    label: String,
    decision: UserCorrectionDecision,
    selected: Boolean,
    modifier: Modifier,
    targetLabel: String,
    onClick: (UserCorrectionDecision) -> Unit
) {
    OutlinedButton(
        onClick = { onClick(decision) },
        modifier = modifier
            .heightIn(min = 48.dp)
            .semantics {
                this.selected = selected
                contentDescription = "$label $targetLabel correction"
                stateDescription = if (selected) "Selected" else "Not selected"
            },
        colors = ButtonDefaults.outlinedButtonColors(contentColor = NeuralTheme.Cobalt)
    ) {
        Text(label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

private fun UserCorrectionDecision.draftLabel(): String = when (this) {
    UserCorrectionDecision.ThisIsMe -> "confirmed by you"
    UserCorrectionDecision.ThisIsNotMe -> "rejected by you"
    UserCorrectionDecision.Unsure -> "marked unsure"
    UserCorrectionDecision.IgnoreEvidence -> "ignored in active analysis"
}

@Composable
private fun ProfileEvidenceCard(
    result: ProfileScanResult,
    onNavigateToBrowser: (String) -> Unit,
    evidenceId: String?,
    currentCorrection: UserCorrectionDecision?,
    onDraftCorrection: ((UserCorrectionDecision) -> Unit)?
) {
    val statusColor = when {
        result.exists && result.verified -> NeuralTheme.Emerald
        result.exists -> NeuralTheme.Amber
        result.verificationStatus?.contains("unverifiable", true) == true -> NeuralTheme.TextSecondary
        else -> NeuralTheme.TextMuted
    }
    ReportCard(borderColor = statusColor.copy(alpha = 0.45f)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(result.candidate.platform.name, color = NeuralTheme.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    result.displayName?.takeIf(String::isNotBlank) ?: result.candidate.username,
                    color = NeuralTheme.TextPrimary,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(result.userFacingStatusLabel(), color = statusColor, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            result.candidate.url,
            color = NeuralTheme.Cobalt,
            fontSize = 11.5.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button) { onNavigateToBrowser(result.candidate.url) }
                .semantics {
                    contentDescription = "Open profile ${result.candidate.url}"
                }
                .padding(vertical = 8.dp)
        )
        result.verificationStatus?.let {
            Text(it, color = NeuralTheme.TextSecondary, fontSize = 11.5.sp, lineHeight = 16.sp)
        }
        result.provenance?.let {
            Text("Provenance: $it", color = NeuralTheme.TextSecondary, fontSize = 10.5.sp, modifier = Modifier.padding(top = 4.dp))
        }
        if (onDraftCorrection != null) {
            currentCorrection?.let { decision ->
                Text(
                    "Draft decision: ${decision.draftLabel()}. Raw profile evidence remains retained until encrypted case save.",
                    color = NeuralTheme.Amber,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            DraftCorrectionRow(
                current = currentCorrection,
                onDecision = onDraftCorrection,
                targetLabel = "profile"
            )
        } else if (evidenceId == null) {
            Text(
                "Correction unavailable: no unique persisted profile evidence record was found.",
                color = NeuralTheme.TextMuted,
                fontSize = 10.5.sp,
                lineHeight = 14.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun ExposurePathCard(path: AttackPathFinder.AttackPath) {
    ReportCard(borderColor = NeuralTheme.Crimson.copy(alpha = 0.4f)) {
        Text(path.endpointLabel, color = NeuralTheme.TextPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
        Text(path.riskHint, color = NeuralTheme.TextSecondary, fontSize = 11.sp)
        path.steps.forEach { step ->
            Text(
                buildString {
                    append("${step.fromLabel} → ${step.toLabel} · ${step.relation}")
                    step.confidence?.let { append(" · ${(it * 100).toInt()}%") }
                },
                color = NeuralTheme.TextPrimary,
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 7.dp)
            )
            step.evidence?.let {
                Text(it.take(180), color = NeuralTheme.TextSecondary, fontSize = 10.5.sp, lineHeight = 14.sp)
            }
        }
    }
}

@Composable
private fun RemediationCard(item: RemediationItem, onNavigateToBrowser: (String) -> Unit) {
    ReportCard(borderColor = riskColor(item.risk).copy(alpha = 0.4f)) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                item.problem,
                color = NeuralTheme.TextPrimary,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            RiskBadge(item.risk)
        }
        Text(item.suggestedFix, color = NeuralTheme.TextPrimary, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 8.dp))
        Text(
            "Expected impact: ${item.estimatedImpact}",
            color = NeuralTheme.TextSecondary,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            modifier = Modifier.padding(top = 5.dp)
        )
        item.resource.providerName?.let { providerName ->
            Text(
                text = "Resource: $providerName · ${item.resource.state.name}",
                color = NeuralTheme.TextSecondary,
                fontSize = 10.5.sp,
                modifier = Modifier.padding(top = 7.dp)
            )
        } ?: Text(
            text = "Resource: ${item.resource.state.name}",
            color = NeuralTheme.TextSecondary,
            fontSize = 10.5.sp,
            modifier = Modifier.padding(top = 7.dp)
        )
        Text(
            text = item.resource.note,
            color = NeuralTheme.TextMuted,
            fontSize = 10.5.sp,
            lineHeight = 14.sp,
            modifier = Modifier.padding(top = 2.dp)
        )
        item.resource.actionUrl?.let { actionUrl ->
            TextButton(
                onClick = { onNavigateToBrowser(actionUrl) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Text(
                    text = item.resource.actionLabel ?: "Open provider resource",
                    color = NeuralTheme.Cobalt,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun ExposureRow(score: ExposureEngine.DimensionScore) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(score.dimension.name, color = NeuralTheme.TextPrimary, fontSize = 12.5.sp)
            if (score.contributingTypes.isNotEmpty()) {
                Text(
                    score.contributingTypes.joinToString(", ") { it.displayName() },
                    color = NeuralTheme.TextSecondary,
                    fontSize = 10.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Text(
            "${score.score}/100",
            color = exposureColor(score.score),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
    LinearProgressIndicator(
        progress = { score.score.coerceIn(0, 100) / 100f },
        modifier = Modifier.fillMaxWidth().height(6.dp).padding(top = 3.dp),
        color = exposureColor(score.score),
        trackColor = NeuralTheme.BorderColor
    )
}

@Composable
private fun CoverageRow(label: String, value: Int, color: Color) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = NeuralTheme.TextSecondary, fontSize = 12.5.sp)
        Text(value.toString(), color = color, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun RiskBadge(risk: RiskLevel) {
    val color = riskColor(risk)
    Text(
        risk.name,
        color = color,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Composable
private fun SectionHeading(title: String, subtitle: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            title,
            color = NeuralTheme.TextPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() }
        )
        Text(subtitle, color = NeuralTheme.TextSecondary, fontSize = 11.5.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun ReportCard(
    borderColor: Color = NeuralTheme.BorderColor,
    content: @Composable Column.() -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = NeuralTheme.CardBackground),
        shape = DossierCardShape,
        modifier = Modifier.fillMaxWidth().border(1.dp, borderColor, DossierCardShape)
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun NoticeCard(text: String, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.08f), DossierCardShape)
            .border(1.dp, color.copy(alpha = 0.28f), DossierCardShape)
            .padding(14.dp)
    ) {
        Text(text, color = NeuralTheme.TextPrimary, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun riskColor(risk: RiskLevel): Color = when (risk) {
    RiskLevel.Low -> NeuralTheme.Emerald
    RiskLevel.Medium -> NeuralTheme.Amber
    RiskLevel.High, RiskLevel.Critical -> NeuralTheme.Crimson
}

@Composable
private fun exposureColor(score: Int): Color = when {
    score >= 80 -> NeuralTheme.Crimson
    score >= 50 -> NeuralTheme.Amber
    score > 0 -> NeuralTheme.Cobalt
    else -> NeuralTheme.TextSecondary
}

private fun riskExplanation(risk: RiskLevel): String = when (risk) {
    RiskLevel.Low -> "Lower-priority evidence was recorded. Continue reviewing coverage gaps."
    RiskLevel.Medium -> "Some evidence may increase correlation, targeting, or account-recovery risk."
    RiskLevel.High -> "Important exposure was recorded and should be reviewed promptly."
    RiskLevel.Critical -> "Evidence indicates an immediate credential, account, or safety concern."
}

private fun findingOrder(): Comparator<Finding> = compareByDescending<Finding> {
    when (it.risk) {
        RiskLevel.Critical -> 4
        RiskLevel.High -> 3
        RiskLevel.Medium -> 2
        RiskLevel.Low -> 1
    }
}.thenByDescending(Finding::confidence)

private fun findingKey(finding: Finding): String =
    "${finding.type}|${finding.value}|${finding.sourceUrl.orEmpty()}"

private fun profileExportLine(result: ProfileScanResult): String =
    "${result.candidate.platform.name}: ${result.candidate.url} — ${result.userFacingStatusLabel()} — ${result.verificationStatus.orEmpty()}"

private fun FindingType.displayName(): String = name.replace(Regex("([a-z])([A-Z])"), "$1 $2")
private fun isHttpUrl(value: String): Boolean = value.startsWith("https://", true) || value.startsWith("http://", true)

internal fun faceConsistencySummary(
    hasSelfie: Boolean,
    faceMatchCount: Int,
    calibratedMatchCount: Int
): String = when {
    !hasSelfie -> "No reference photo was supplied. Provide a selfie only when the subject consents and visual correlation is useful."
    faceMatchCount == 0 -> "A reference photo was supplied, but no profile image produced a usable local comparison."
    calibratedMatchCount == 0 -> "$faceMatchCount local visual score(s) were produced, but they are not treated as identity evidence without a matching measured calibration."
    else -> "$calibratedMatchCount calibrated visual match(es) were recorded from $faceMatchCount comparison(s). They remain supporting evidence, not ownership proof."
}

internal data class GraphReconciliationUiSummary(
    val statusLabel: String,
    val detail: String,
    val hasDiagnosticIssues: Boolean
)

internal fun graphReconciliationUiSummary(
    report: GraphEvidenceReconciliationReport?
): GraphReconciliationUiSummary {
    if (report == null) {
        return GraphReconciliationUiSummary(
            statusLabel = "Unavailable",
            detail = "No active case snapshot is available; no consistency claim is made.",
            hasDiagnosticIssues = false
        )
    }

    val detail = listOf(
        "Matched canonical relationships: ${report.matchedRelationships}",
        "Missing graph edges: ${report.missingGraphEdges}",
        "Extra graph edges: ${report.extraGraphEdges}",
        "Conflicting evidence: ${report.conflictingEvidence}",
        "Ambiguous relationships: ${report.ambiguousRelationships}",
        "Dangling canonical evidence references: ${report.danglingCanonicalEvidenceIds}",
        "Dangling graph-edge evidence references: ${report.danglingGraphEvidenceIds}",
        "Dangling graph-entity evidence references: ${report.danglingGraphEntityEvidenceIds}",
        "Truncated canonical relationships: ${report.truncatedCanonicalRelationships}",
        "Truncated graph edges: ${report.truncatedGraphEdges}",
        "Truncated canonical evidence references: ${report.truncatedCanonicalEvidenceIds}",
        "Truncated graph-edge evidence references: ${report.truncatedGraphEvidenceIds}",
        "Truncated graph-entity evidence references: ${report.truncatedGraphEntityEvidenceIds}"
    ).joinToString("; ")
    return GraphReconciliationUiSummary(
        statusLabel = if (report.isConsistent) "Consistent" else "Review needed",
        detail = detail,
        hasDiagnosticIssues = !report.isConsistent
    )
}

internal fun formatEntityGraphFromSession(graph: EntityGraph): List<String> {
    if (graph.entities.isEmpty() && graph.edges.isEmpty()) return emptyList()
    val byId = graph.entities.associateBy { it.id }
    val entityLines = graph.entities.sortedByDescending { it.confidence }.map { entity ->
        "${entity.type}: ${entity.label} (${(entity.confidence * 100).toInt()}%)"
    }
    val edgeLines = graph.edges.map { edge ->
        val from = byId[edge.fromId]?.label ?: edge.fromId
        val to = byId[edge.toId]?.label ?: edge.toId
        "$from —${edge.relation}→ $to${edge.evidence?.let { " [$it]" }.orEmpty()}"
    }
    return entityLines + edgeLines
}

internal fun formatBreachDigestsFromSession(digests: List<BreachDigest>): List<String> =
    digests.map { digest ->
        buildString {
            append("${digest.email}: ${digest.breachCount} breach record(s)")
            if (digest.sources.isNotEmpty()) append(" — ${digest.sources.joinToString(", ")}")
            digest.note?.let { append(" — $it") }
        }
    }

internal fun formatEntityGraphLines(
    findings: List<Finding>,
    profiles: List<ProfileScanResult>
): List<String> = buildList {
    findings.distinctBy { it.type to it.value }.forEach {
        add("${it.type}: ${it.value} (${(it.confidence * 100).toInt()}%)")
    }
    profiles.filter { it.exists }.forEach {
        add("Profile: ${it.candidate.url} — ${it.userFacingStatusLabel()}")
    }
}

internal fun formatBreachDigestLines(findings: List<Finding>): List<String> =
    findings.filter {
        it.evidenceSnippet?.contains("breach", ignoreCase = true) == true ||
            it.remediation.contains("MFA", ignoreCase = true)
    }.map { "${it.value}: ${it.evidenceSnippet.orEmpty()}" }
