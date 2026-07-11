package io.dossier.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.dossier.app.domain.scanner.ScanSession
import io.dossier.app.ui.components.AnimatedObsidianBackground
import io.dossier.app.ui.components.LottieLoop
import io.dossier.app.ui.components.LottieTags
import io.dossier.app.ui.theme.NeuralTheme
import kotlinx.coroutines.delay

/**
 * Scan screen — calm, honest, real progress.
 *
 * Replaces the prior fake terminal-log animation (a fixed list of log strings
 * printed on a timer that said "complete" before the real scan finished) with
 * the ACTUAL scan stage from ScanSession.progressText, plus a live log of real
 * verification events. Navigation to the report fires only when the real scan
 * completes (isScanning becomes false).
 */
@Composable
fun ScanScreen(onScanComplete: () -> Unit) {
    val context = LocalContext.current
    val progressText by ScanSession.progressText.collectAsState()
    val isScanning by ScanSession.isScanning.collectAsState()
    val profileResults by ScanSession.profileScanResults.collectAsState()

    // Real, live log entries — appended as actual scan milestones occur.
    val liveLogs = remember { mutableStateListOf<String>() }
    val scrollState = rememberScrollState()

    // Map the real progressText codes to a friendly stage label + log entry.
    LaunchedEffect(progressText) {
        if (progressText.isNotBlank() && liveLogs.lastOrNull() != progressText) {
            liveLogs.add(friendlyStage(progressText))
        }
    }

    // Track confirmed-profile count as a real signal.
    LaunchedEffect(profileResults.size) {
        val confirmed = profileResults.count { it.exists && it.verified }
        if (confirmed > 0) {
            val msg = "Confirmed $confirmed profile(s) so far…"
            if (liveLogs.lastOrNull()?.startsWith("Confirmed") != true) {
                liveLogs.add(msg)
            }
        }
    }

    LaunchedEffect(Unit) {
        val input = ScanSession.tempInput
        liveLogs.add("Starting scan…")
        if (input != null) {
            val deepResearch = ScanSession.deepResearchEnabled.value
            if (deepResearch) liveLogs.add("Deep Research enabled — following linked sites")
            ScanSession.executeScan(context, input, deepResearch = deepResearch)
        }
        liveLogs.add("Scan complete.")
        delay(500)
        onScanComplete()
    }

    // Auto-scroll the log to the bottom as entries arrive.
    LaunchedEffect(liveLogs.size) {
        if (liveLogs.isNotEmpty()) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedObsidianBackground(showGrid = false)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            // Header
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (isScanning) "Scanning" else "Compiling report",
                    color = NeuralTheme.TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "Exposure Search",
                    color = NeuralTheme.TextPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            // Hero — calm indeterminate ring + Lottie, no layered glow/sparks.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(200.dp)
            ) {
                io.dossier.app.ui.components.SquigglyProgressIndicator(
                    size = 160.dp,
                    progress = null
                )
                LottieLoop(
                    tag = LottieTags.SEARCH,
                    size = 110.dp,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            // Live status + log
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = friendlyStageLabel(progressText),
                    color = NeuralTheme.Cobalt,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )

                io.dossier.app.ui.components.LinearWavyProgressIndicator(
                    progress = if (isScanning) null else 1f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    strokeWidth = 3.dp
                )

                // Live log — real entries only, no fake terminal animation.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp)
                        .verticalScroll(scrollState)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        liveLogs.forEach { log ->
                            Text(
                                text = "· $log",
                                color = NeuralTheme.TextSecondary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Convert the raw progressText code into a friendly line for the log. */
private fun friendlyStage(raw: String): String = when {
    raw.contains("DISCOVERING", ignoreCase = true) -> "Resolving name → username variants"
    raw.contains("COMPARING", ignoreCase = true) -> "Comparing selfie vs profile avatars"
    raw.contains("BREACH", ignoreCase = true) -> "Checking email breach / public exposure"
    raw.contains("ENTITY", ignoreCase = true) -> "Building entity relationship graph"
    raw.contains("COMPILING", ignoreCase = true) -> "Compiling exposure levels"
    raw.contains("GENERATING_AI", ignoreCase = true) -> "Generating AI analysis"
    raw.contains("AUDITING", ignoreCase = true) -> "Auditing place image metadata"
    else -> raw.lowercase().replace('_', ' ')
}

/** A short label for the current stage, shown above the progress bar. */
private fun friendlyStageLabel(raw: String): String = when {
    raw.isBlank() -> "Initializing"
    raw.contains("DISCOVERING", ignoreCase = true) -> "Discovering usernames"
    raw.contains("COMPARING", ignoreCase = true) -> "Comparing faces"
    raw.contains("BREACH", ignoreCase = true) -> "Breach exposure"
    raw.contains("ENTITY", ignoreCase = true) -> "Entity graph"
    raw.contains("COMPILING", ignoreCase = true) -> "Compiling report"
    raw.contains("GENERATING_AI", ignoreCase = true) -> "Generating AI analysis"
    else -> raw.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
}
