package io.dossier.app.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
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
 * Scan screen backed only by real ScanSession state.
 *
 * Missing navigation/session input is a visible recoverable error. Dossier must
 * never fabricate a subject and generate a plausible-looking report for it.
 */
@Composable
fun ScanScreen(
    onScanComplete: () -> Unit,
    onScanCancelled: () -> Unit,
    onInvalidInput: () -> Unit = onScanCancelled
) {
    val context = LocalContext.current
    val progressText by ScanSession.progressText.collectAsState()
    val isScanning by ScanSession.isScanning.collectAsState()
    val profileResults by ScanSession.profileScanResults.collectAsState()

    val liveLogs = remember { mutableStateListOf<String>() }
    val scrollState = rememberScrollState()
    var startError by remember { mutableStateOf<String?>(null) }
    var hasStarted by remember { mutableStateOf(false) }
    var navigationCompleted by remember { mutableStateOf(false) }
    var cancelledByUser by remember { mutableStateOf(false) }

    LaunchedEffect(progressText) {
        if (progressText.isNotBlank() && liveLogs.lastOrNull() != progressText) {
            liveLogs.add(friendlyStage(progressText))
        }
    }

    LaunchedEffect(profileResults.size) {
        val confirmed = profileResults.count { it.exists && it.verified }
        if (confirmed > 0) {
            val message = "Confirmed $confirmed profile(s) so far…"
            if (liveLogs.lastOrNull()?.startsWith("Confirmed") != true) liveLogs.add(message)
        }
    }

    LaunchedEffect(Unit) {
        val resume = ScanSession.loadResumePoint(context)
        val input = ScanSession.tempInput ?: ScanSession.currentInput.value ?: resume?.first
        if (input == null || !hasUsableIdentityInput(input)) {
            startError = "No valid identity input was supplied. Return to Identity Setup and enter at least one name, username, email, phone number, or profile URL."
            liveLogs.add("Scan not started: identity input is missing")
            return@LaunchedEffect
        }

        liveLogs.add("Starting scan…")
        val deepResearch = if (ScanSession.tempInput == null && ScanSession.currentInput.value == null) {
            resume?.second ?: ScanSession.deepResearchEnabled.value
        } else {
            ScanSession.deepResearchEnabled.value
        }
        if (deepResearch) liveLogs.add("Deep Research enabled — following linked sites")
        ScanSession.startScan(context, input, deepResearch = deepResearch)
    }

    LaunchedEffect(isScanning) {
        if (isScanning) {
            hasStarted = true
        } else if (
            hasStarted &&
            !navigationCompleted &&
            !cancelledByUser &&
            progressText != "SCAN_CANCELLED"
        ) {
            liveLogs.add("Scan complete.")
            navigationCompleted = true
            delay(300)
            onScanComplete()
        }
    }

    LaunchedEffect(liveLogs.size) {
        if (liveLogs.isNotEmpty()) scrollState.animateScrollTo(scrollState.maxValue)
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

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = when {
                        startError != null -> "Input required"
                        isScanning -> "Scanning"
                        else -> "Compiling report"
                    },
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

            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(200.dp)) {
                io.dossier.app.ui.components.SquigglyProgressIndicator(
                    size = 160.dp,
                    progress = when {
                        startError != null -> 0f
                        isScanning -> null
                        else -> 1f
                    }
                )
                LottieLoop(
                    tag = if (startError == null) LottieTags.SEARCH else LottieTags.INVESTIGATE,
                    size = 110.dp,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = startError?.let { "Scan cannot start" } ?: friendlyStageLabel(progressText),
                    color = if (startError == null) NeuralTheme.Cobalt else NeuralTheme.Crimson,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )

                io.dossier.app.ui.components.LinearWavyProgressIndicator(
                    progress = if (isScanning) null else if (startError == null) 1f else 0f,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    strokeWidth = 3.dp
                )

                startError?.let { message ->
                    Text(
                        text = message,
                        color = NeuralTheme.TextPrimary,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    Button(
                        onClick = {
                            navigationCompleted = true
                            onInvalidInput()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NeuralTheme.Cobalt),
                        shape = io.dossier.app.ui.theme.DossierButtonShape,
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text("RETURN TO IDENTITY SETUP", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }

                if (startError == null) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(130.dp).verticalScroll(scrollState)
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

                if (isScanning) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            cancelledByUser = true
                            navigationCompleted = true
                            ScanSession.cancelScan()
                            onScanCancelled()
                        },
                        border = BorderStroke(1.2.dp, NeuralTheme.Crimson.copy(alpha = 0.8f)),
                        shape = io.dossier.app.ui.theme.DossierButtonShape,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NeuralTheme.Crimson),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text(
                            text = "CANCEL SCAN",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }
        }
    }
}

private fun hasUsableIdentityInput(input: io.dossier.app.domain.model.IdentityInput): Boolean =
    input.fullName.isNotBlank() ||
        !input.primaryUsername.isNullOrBlank() ||
        input.usernames.any { it.isNotBlank() } ||
        input.emails.any { it.isNotBlank() } ||
        input.phones.any { it.isNotBlank() } ||
        input.profileUrls.any { it.isNotBlank() }

private fun friendlyStage(raw: String): String = when {
    raw.contains("DISCOVERING", ignoreCase = true) -> "Resolving name → username variants"
    raw.contains("COMPARING", ignoreCase = true) -> "Comparing selfie vs profile avatars"
    raw.contains("BREACH", ignoreCase = true) -> "Checking email breach / public exposure"
    raw.contains("ENTITY", ignoreCase = true) -> "Building entity relationship graph"
    raw.contains("COMPILING", ignoreCase = true) -> "Compiling exposure levels"
    raw.contains("GENERATING_AI", ignoreCase = true) -> "Generating analysis"
    raw.contains("AUDITING", ignoreCase = true) -> "Auditing place image metadata"
    raw.contains("CANCELLED", ignoreCase = true) -> "Scan cancelled"
    else -> raw.lowercase().replace('_', ' ')
}

private fun friendlyStageLabel(raw: String): String = when {
    raw.isBlank() -> "Initializing"
    raw.contains("DISCOVERING", ignoreCase = true) -> "Discovering usernames"
    raw.contains("COMPARING", ignoreCase = true) -> "Comparing visual consistency"
    raw.contains("BREACH", ignoreCase = true) -> "Breach and exposure coverage"
    raw.contains("ENTITY", ignoreCase = true) -> "Entity graph"
    raw.contains("COMPILING", ignoreCase = true) -> "Compiling report"
    raw.contains("GENERATING_AI", ignoreCase = true) -> "Generating analysis"
    raw.contains("CANCELLED", ignoreCase = true) -> "Cancelled"
    else -> raw.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
}
