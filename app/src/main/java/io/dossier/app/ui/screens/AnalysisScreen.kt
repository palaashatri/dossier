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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.WorkInfo
import io.dossier.app.domain.analysis.OsintAnalysisBundle
import io.dossier.app.domain.analysis.PresenceState
import io.dossier.app.domain.case.CaseTimelineBuilder
import io.dossier.app.domain.case.DossierCase
import io.dossier.app.domain.scanner.BackgroundScanManager
import io.dossier.app.domain.scanner.BackgroundScanResultStore
import io.dossier.app.domain.scanner.ScanLifecyclePhase
import io.dossier.app.domain.scanner.ScanSession
import io.dossier.app.export.GraphExportService
import io.dossier.app.ui.components.AnimatedObsidianBackground
import io.dossier.app.ui.labels.userFacingLabel
import io.dossier.app.ui.theme.NeuralTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Non-blocking landing surface for durable collection and post-processing. */
@Composable
fun AnalysisScreen(
    onOpenReport: () -> Unit,
    onBackToSetup: () -> Unit
) {
    val context = LocalContext.current
    val graphExporter = remember(context) { GraphExportService(context) }
    val workInfos by remember(context) {
        BackgroundScanManager.statusFlow(context)
    }.collectAsState(initial = emptyList())
    val profileCount by ScanSession.profileScanResults.collectAsState()
    val findingCount by ScanSession.findings.collectAsState()
    val entityGraph by ScanSession.entityGraph.collectAsState()
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()

    var snapshot by remember { mutableStateOf<BackgroundScanResultStore.Snapshot?>(null) }
    var lifecyclePhase by remember { mutableStateOf<ScanLifecyclePhase?>(null) }
    val latestInfo = BackgroundScanManager.selectRelevantWorkInfo(
        context = context,
        workInfos = workInfos,
        completedWorkId = snapshot?.workId
    )
    val status = latestInfo?.let(BackgroundScanManager::toStatus)

    LaunchedEffect(Unit) {
        BackgroundScanManager.latestResultAsync(context)?.let { restored ->
            snapshot = restored
            restored.dossierCase.let(ScanSession::restoreFromCase)
        }
    }

    // A paused request has a cancelled WorkManager row by design. Keep the
    // encrypted lifecycle marker as the source of truth and poll only while
    // the exact pause transition is still settling.
    LaunchedEffect(latestInfo?.state, latestInfo?.id, lifecyclePhase) {
        val observed = BackgroundScanManager.lifecyclePhaseAsync(context)
        if (observed != lifecyclePhase) lifecyclePhase = observed
        if (observed == ScanLifecyclePhase.Paused || observed == ScanLifecyclePhase.Succeeded) {
            // A worker can publish the encrypted result immediately before a
            // pause cancellation wins. WorkManager then reports CANCELLED,
            // so lifecycle changes—not the row state—must trigger this read.
            BackgroundScanManager.latestResultAsync(context)?.let { restored ->
                snapshot = restored
                restored.dossierCase.let(ScanSession::restoreFromCase)
            }
        }
        if (observed == ScanLifecyclePhase.Pausing) {
            while (isActive) {
                delay(250L)
                val next = BackgroundScanManager.lifecyclePhaseAsync(context)
                if (next != lifecyclePhase) {
                    lifecyclePhase = next
                    break
                }
            }
        }
    }

    LaunchedEffect(latestInfo?.state, latestInfo?.id) {
        if (latestInfo?.state == WorkInfo.State.SUCCEEDED) {
            snapshot = BackgroundScanManager.latestResultAsync(context)
            snapshot?.dossierCase?.let(ScanSession::restoreFromCase)
        }
    }

    val pausing = lifecyclePhase == ScanLifecyclePhase.Pausing
    val paused = lifecyclePhase == ScanLifecyclePhase.Paused
    val running = status?.running == true && !pausing && !paused
    val failed = latestInfo?.state == WorkInfo.State.FAILED
    val cancelled = latestInfo?.state == WorkInfo.State.CANCELLED && !paused && !pausing
    val analysis = snapshot?.analysis ?: OsintAnalysisBundle()

    AnimatedObsidianBackground(showGrid = false)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "Background analysis",
            color = NeuralTheme.TextPrimary,
            fontSize = 27.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() }
        )
        Text(
            text = when {
                pausing -> "Pause requested. Dossier is waiting for the exact WorkManager owner to stop safely."
                paused && snapshot != null -> "The scan is paused; its encrypted transient result is ready for review."
                paused -> "The scan is paused with its encrypted checkpoint retained. Resume when you are ready."
                running -> "You can use the rest of Dossier while collection and analysis continue."
                snapshot != null -> "The latest encrypted transient result is ready for review."
                failed -> "The background scan failed. Partial in-memory results may still be available."
                cancelled -> "The background scan was cancelled."
                else -> "No completed background analysis is available yet."
            },
            color = NeuralTheme.TextSecondary,
            fontSize = 13.sp,
            lineHeight = 19.sp
        )

        if (running || pausing) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Background scan in progress" }
            )
            AnalysisCard("Current stage") {
                MonoText(if (pausing) "PAUSE_REQUESTED" else status?.stage ?: "BACKGROUND_SCAN_RUNNING")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Metric("Profiles", profileCount.size)
                    Metric("Entities", entityGraph.entities.size)
                    Metric("Findings", findingCount.size)
                }
            }
        } else if (paused && snapshot == null) {
            AnalysisCard("Paused checkpoint") {
                Text(
                    text = "No WorkManager execution is active. The encrypted request checkpoint remains local and will be used only after you explicitly resume this scan.",
                    color = NeuralTheme.TextSecondary,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp
                )
            }
        } else if (failed) {
            AnalysisCard("Scan error") {
                Text(
                    text = status?.error ?: "The worker reported a terminal failure.",
                    color = NeuralTheme.Crimson,
                    fontSize = 12.5.sp
                )
            }
        }

        snapshot?.let { completed ->
            val completedCase = completed.dossierCase
            IdentitySurfaceSection(analysis)
            BehavioralSection(analysis)
            InteractionSection(analysis)
            TimelineSection(completedCase)

            if (completedCase.entityGraph.entities.isNotEmpty()) {
                OutlinedButton(
                    onClick = {
                        graphExporter.share(
                            case = completedCase,
                            label = completedCase.subjectName.ifBlank { "investigation" }
                        )
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = io.dossier.app.ui.theme.DossierButtonShape,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NeuralTheme.Cobalt)
                ) {
                    Text("EXPORT GRAPH · GRAPHML + CSV + JSON", fontWeight = FontWeight.Bold, fontSize = 11.5.sp)
                }
            }

            Button(
                onClick = {
                    ScanSession.restoreFromCase(completedCase)
                    onOpenReport()
                },
                colors = ButtonDefaults.buttonColors(containerColor = NeuralTheme.Cobalt),
                shape = io.dossier.app.ui.theme.DossierButtonShape,
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Text("OPEN FULL REPORT", fontWeight = FontWeight.Bold)
            }
        }

        if (running) {
            OutlinedButton(
                onClick = {
                    coroutineScope.launch {
                        val requested = withContext(Dispatchers.IO) {
                            BackgroundScanManager.pause(context)
                        }
                        lifecyclePhase = withContext(Dispatchers.IO) {
                            BackgroundScanManager.lifecyclePhaseAsync(context)
                        }
                        if (!requested && lifecyclePhase == null) {
                            snapshot = BackgroundScanManager.latestResultAsync(context)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = io.dossier.app.ui.theme.DossierButtonShape,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = NeuralTheme.Cobalt)
            ) {
                Text("PAUSE BACKGROUND SCAN", fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = {
                    coroutineScope.launch {
                        withContext(Dispatchers.IO) {
                            BackgroundScanManager.cancel(context)
                        }
                        lifecyclePhase = withContext(Dispatchers.IO) {
                            BackgroundScanManager.lifecyclePhaseAsync(context)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = io.dossier.app.ui.theme.DossierButtonShape,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = NeuralTheme.Crimson)
            ) {
                Text("CANCEL BACKGROUND SCAN", fontWeight = FontWeight.Bold)
            }
        }

        if (pausing) {
            OutlinedButton(
                onClick = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = io.dossier.app.ui.theme.DossierButtonShape
            ) {
                Text("PAUSING…", fontWeight = FontWeight.Bold)
            }
        }

        if (paused && snapshot == null) {
            Button(
                onClick = {
                    coroutineScope.launch {
                        withContext(Dispatchers.IO) {
                            BackgroundScanManager.resume(context)
                        }
                        lifecyclePhase = withContext(Dispatchers.IO) {
                            BackgroundScanManager.lifecyclePhaseAsync(context)
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = NeuralTheme.Cobalt),
                shape = io.dossier.app.ui.theme.DossierButtonShape,
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Text("RESUME PAUSED SCAN", fontWeight = FontWeight.Bold)
            }
        }

        OutlinedButton(
            onClick = onBackToSetup,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = io.dossier.app.ui.theme.DossierButtonShape
        ) {
            Text("IDENTITY SETUP", fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun IdentitySurfaceSection(bundle: OsintAnalysisBundle) {
    val map = bundle.identitySurface
    AnalysisCard("Identity Surface Map") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Metric("Verified", map.confirmedCount)
            Metric("Review", map.reviewCount)
            Metric("No match", map.noMatchCount)
            Metric("Unavailable", map.unavailableCount)
        }
        Spacer(Modifier.height(9.dp))
        map.entries
            .filter { it.state == PresenceState.Exists || it.state == PresenceState.SuspiciousSimilarity }
            .take(12)
            .forEach { item ->
                Text(
                    text = "${item.platform} · @${item.username} · ${item.state.userFacingLabel()}",
                    color = if (item.state == PresenceState.Exists) NeuralTheme.Emerald else NeuralTheme.Amber,
                    fontSize = 11.5.sp,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        if (map.entries.isEmpty()) {
            Text("No presence matrix has been produced for this result.", color = NeuralTheme.TextSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun BehavioralSection(bundle: OsintAnalysisBundle) {
    val behavior = bundle.behavioral
    AnalysisCard("Behavioral Profile") {
        Text(
            text = "${behavior.textSampleCount} text samples · ${behavior.timestampedSampleCount} timestamped",
            color = NeuralTheme.TextSecondary,
            fontSize = 12.sp
        )
        behavior.dominantPostingWindowUtc?.let {
            Text("Dominant observed UTC window: $it", color = NeuralTheme.TextPrimary, fontSize = 12.sp)
        }
        if (behavior.topics.isNotEmpty()) {
            Text(
                text = "Recurring terms: ${behavior.topics.take(10).joinToString(", ")}",
                color = NeuralTheme.TextPrimary,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        }
        behavior.timezoneHypotheses.take(3).forEach { hypothesis ->
            Text(
                text = "Activity-window hypothesis UTC${if (hypothesis.utcOffsetHours >= 0) "+" else ""}${hypothesis.utcOffsetHours} · ${(hypothesis.score * 100).toInt()}% fit",
                color = NeuralTheme.Amber,
                fontSize = 11.5.sp
            )
        }
        behavior.crossSourceStyle.take(4).forEach { comparison ->
            Text(
                text = "Writing-feature similarity: ${comparison.sourceA} ↔ ${comparison.sourceB} · ${(comparison.similarity * 100).toInt()}%",
                color = NeuralTheme.TextSecondary,
                fontSize = 11.5.sp
            )
        }
        Text(
            text = behavior.caveat,
            color = NeuralTheme.TextSecondary,
            fontSize = 10.5.sp,
            lineHeight = 15.sp,
            modifier = Modifier.padding(top = 5.dp)
        )
    }
}

@Composable
private fun InteractionSection(bundle: OsintAnalysisBundle) {
    val graph = bundle.interactionGraph
    AnalysisCard("Interaction Network") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Metric("Nodes", graph.nodeCount)
            Metric("Edges", graph.edgeCount)
            Metric("Clusters", graph.clusters.size)
        }
        graph.influenceNodes.take(6).forEachIndexed { index, node ->
            Text(
                text = "${index + 1}. @${node.node} · PageRank ${"%.3f".format(node.pageRank)} · weight ${"%.1f".format(node.weightedDegree)}",
                color = NeuralTheme.TextPrimary,
                fontSize = 11.5.sp,
                modifier = Modifier.padding(vertical = 2.dp)
            )
        }
        if (graph.nodeCount == 0) {
            Text(
                "No reply/mention relationships were available in the collected public sample.",
                color = NeuralTheme.TextSecondary,
                fontSize = 12.sp
            )
        }
        Text(
            text = graph.caveat,
            color = NeuralTheme.TextSecondary,
            fontSize = 10.5.sp,
            lineHeight = 15.sp,
            modifier = Modifier.padding(top = 5.dp)
        )
    }
}

@Composable
private fun TimelineSection(case: DossierCase) {
    val timeline = remember(case) { CaseTimelineBuilder.build(case, limit = 120) }
    AnalysisCard("Investigation Timeline") {
        if (timeline.isEmpty()) {
            Text(
                "No timestamped evidence is available. Dossier does not invent dates for undated observations.",
                color = NeuralTheme.TextSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        } else {
            Text(
                "${timeline.size} provenance-backed events · newest first",
                color = NeuralTheme.TextSecondary,
                fontSize = 11.5.sp
            )
            timeline.take(18).forEach { event ->
                val timestamp = Instant.ofEpochMilli(event.timestampEpochMillis)
                    .atZone(ZoneId.systemDefault())
                    .format(TIMELINE_FORMAT)
                Text(
                    text = "${if (event.historical) "HISTORICAL · " else ""}$timestamp · ${event.title}",
                    color = if (event.historical) NeuralTheme.Amber else NeuralTheme.TextPrimary,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .semantics {
                            stateDescription = if (event.historical) {
                                "Historical observation"
                            } else {
                                "Current observation"
                            }
                        }
                )
                Text(
                    text = event.detail,
                    color = NeuralTheme.TextSecondary,
                    fontSize = 10.5.sp,
                    lineHeight = 15.sp
                )
            }
            if (timeline.size > 18) {
                Text(
                    "+ ${timeline.size - 18} more events retained in the case timeline",
                    color = NeuralTheme.Cobalt,
                    fontSize = 10.5.sp,
                    modifier = Modifier.padding(top = 5.dp)
                )
            }
        }
    }
}

@Composable
private fun AnalysisCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(NeuralTheme.CardBackground.copy(alpha = 0.92f), RoundedCornerShape(12.dp))
            .border(1.dp, NeuralTheme.BorderColor, RoundedCornerShape(12.dp))
            .padding(15.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            title,
            color = NeuralTheme.Cobalt,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() }
        )
        content()
    }
}

@Composable
private fun Metric(label: String, value: Int) {
    Column(
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = "$value $label"
        }
    ) {
        Text(value.toString(), color = NeuralTheme.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(label, color = NeuralTheme.TextSecondary, fontSize = 9.5.sp)
    }
}

@Composable
private fun MonoText(value: String) {
    Text(
        value.replace('_', ' '),
        color = NeuralTheme.Cobalt,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.5.sp,
        fontWeight = FontWeight.Medium
    )
}

private val TIMELINE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
