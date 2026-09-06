package io.dossier.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.dossier.app.data.face.FaceCorrelationCalibrationStore
import io.dossier.app.data.face.FaceCorrelationConsentStore
import io.dossier.app.data.face.FaceCorrelationModelPack
import io.dossier.app.data.face.FaceCorrelationSessionPolicy
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.search.hasUsableUniversalSeed
import io.dossier.app.domain.discovery.DiscoveryScanPreferences
import io.dossier.app.domain.discovery.ScanCoordinatorRuntime
import io.dossier.app.domain.discovery.ScanRequest
import io.dossier.app.domain.scanner.BackgroundScanWorker
import io.dossier.app.domain.scanner.ScanSession
import io.dossier.app.ui.components.AnimatedObsidianBackground
import io.dossier.app.ui.components.LottieLoop
import io.dossier.app.ui.components.LottieTags
import io.dossier.app.ui.theme.NeuralTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Scan screen backed only by real ScanSession state.
 *
 * A selfie never silently enables cross-photo biometric-derived processing.
 * Every eligible scan explicitly chooses strong local YuNet/SFace correlation
 * or basic near-duplicate/photo-reuse matching. The choice is process-local and
 * is reset when the scan completes, fails, or is cancelled.
 */
@Composable
fun ScanScreen(
    onScanComplete: () -> Unit,
    onScanFailed: () -> Unit,
    onScanCancelled: () -> Unit,
    onInvalidInput: () -> Unit = onScanCancelled,
    onScanBackgrounded: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val progressText by ScanSession.progressText.collectAsState()
    val isScanning by ScanSession.isScanning.collectAsState()
    val profileResults by ScanSession.profileScanResults.collectAsState()

    val modelPack = remember { FaceCorrelationModelPack(context) }
    val consentStore = remember { FaceCorrelationConsentStore(context) }
    val calibrationStore = remember { FaceCorrelationCalibrationStore(context) }

    val liveLogs = remember { mutableStateListOf<String>() }
    val scrollState = rememberScrollState()
    var startError by remember { mutableStateOf<String?>(null) }
    var hasStarted by remember { mutableStateOf(false) }
    var navigationCompleted by remember { mutableStateOf(false) }
    var cancelledByUser by remember { mutableStateOf(false) }
    var pendingInput by remember { mutableStateOf<IdentityInput?>(null) }
    var pendingDeepResearch by remember { mutableStateOf(false) }
    var showFaceSetup by remember { mutableStateOf(false) }
    var facePackInstalling by remember { mutableStateOf(false) }
    var facePackProgress by remember { mutableStateOf(0f) }
    var facePackMessage by remember { mutableStateOf<String?>(null) }
    var faceStateRefresh by remember { mutableStateOf(0) }

    val facePackReady = remember(faceStateRefresh) { modelPack.isReady() }
    val activeCalibration = remember(faceStateRefresh) { calibrationStore.getThresholds() }
    val consentActive = remember(faceStateRefresh) { consentStore.hasConsent() }

    val calibrationImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                runCatching { calibrationStore.importCalibration(uri) }
                    .onSuccess { thresholds ->
                        faceStateRefresh++
                        facePackMessage =
                            "Measured calibration imported: review >= ${"%.3f".format(thresholds.reviewThreshold)}, " +
                                "high >= ${"%.3f".format(thresholds.highSimilarityThreshold)}; " +
                                "${thresholds.positivePairCount} positive / " +
                                "${thresholds.negativePairCount} negative held-out pairs."
                    }
                    .onFailure { error ->
                        facePackMessage = error.localizedMessage
                            ?: "Unable to import the YuNet/SFace calibration file."
                    }
            }
        }
    }

    fun startResolvedScan(
        input: IdentityInput,
        deepResearch: Boolean,
        useStrongCorrelation: Boolean,
        visualMode: String
    ) {
        if (useStrongCorrelation) {
            FaceCorrelationSessionPolicy.useStrongCorrelation()
        } else {
            FaceCorrelationSessionPolicy.useBasicMatching()
        }
        showFaceSetup = false
        liveLogs.add(visualMode)
        liveLogs.add("Starting scan…")
        if (deepResearch) liveLogs.add("Deep Research enabled — following linked sites")
        coroutineScope.launch {
            ScanCoordinatorRuntime.start(
                context = context,
                request = ScanRequest(
                    input = input,
                    mode = DiscoveryScanPreferences.selectedMode.value,
                    deepResearch = deepResearch
                )
            )
        }
    }

    LaunchedEffect(progressText) {
        if (
            (hasStarted || isScanning) &&
            progressText.isNotBlank() &&
            liveLogs.lastOrNull() != progressText
        ) {
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
        FaceCorrelationSessionPolicy.useBasicMatching()
        val resume = ScanSession.loadResumePoint(context)
        val input = ScanSession.tempInput ?: ScanSession.currentInput.value ?: resume?.first
        if (input == null || !input.hasUsableUniversalSeed()) {
            startError = "No usable search seed was supplied. Return to Search and enter text or choose a photo."
            liveLogs.add("Scan not started: search seed is missing")
            return@LaunchedEffect
        }

        val deepResearch = if (ScanSession.tempInput == null && ScanSession.currentInput.value == null) {
            resume?.second ?: ScanSession.deepResearchEnabled.value
        } else {
            ScanSession.deepResearchEnabled.value
        }

        if (!input.selfieUri.isNullOrBlank()) {
            pendingInput = input
            pendingDeepResearch = deepResearch
            showFaceSetup = true
            liveLogs.add("Waiting for per-scan face-correlation choice")
        } else {
            startResolvedScan(
                input = input,
                deepResearch = deepResearch,
                useStrongCorrelation = false,
                visualMode = "No selfie supplied — face correlation skipped"
            )
        }
    }

    LaunchedEffect(isScanning) {
        if (isScanning) {
            hasStarted = true
        } else if (hasStarted) {
            FaceCorrelationSessionPolicy.useBasicMatching()
            if (
                !navigationCompleted &&
                !cancelledByUser &&
                progressText != "SCAN_CANCELLED"
            ) {
                val failed = progressText.startsWith(BackgroundScanWorker.STAGE_FAILED)
                liveLogs.add(if (failed) "Scan failed." else "Scan complete.")
                navigationCompleted = true
                delay(300)
                if (failed) onScanFailed() else onScanComplete()
            }
        }
    }

    LaunchedEffect(liveLogs.size) {
        if (liveLogs.isNotEmpty()) scrollState.animateScrollTo(scrollState.maxValue)
    }

    if (showFaceSetup) {
        FaceCorrelationChoiceDialog(
            facePackReady = facePackReady,
            consentActive = consentActive,
            measuredCalibration = activeCalibration.measured,
            calibrationSummary = activeCalibration.summary(),
            expectedPackBytes = modelPack.status().expectedBytes,
            installing = facePackInstalling,
            installProgress = facePackProgress,
            message = facePackMessage,
            onImportCalibration = {
                calibrationImportLauncher.launch(
                    arrayOf("application/json", "text/json", "text/plain", "*/*")
                )
            },
            onDeleteModels = {
                FaceCorrelationSessionPolicy.useBasicMatching()
                modelPack.delete()
                consentStore.revoke()
                calibrationStore.clear()
                facePackProgress = 0f
                facePackMessage =
                    "YuNet/SFace models, measured calibration and stored consent were deleted."
                faceStateRefresh++
            },
            onUseBasic = {
                pendingInput?.let { input ->
                    startResolvedScan(
                        input = input,
                        deepResearch = pendingDeepResearch,
                        useStrongCorrelation = false,
                        visualMode =
                            "Basic photo-reuse matching selected; cross-photo face embeddings disabled for this scan"
                    )
                }
            },
            onUseStrong = {
                val input = pendingInput
                if (input != null) {
                    if (facePackReady) {
                        consentStore.grantForInstalledPipeline()
                        faceStateRefresh++
                        startResolvedScan(
                            input = input,
                            deepResearch = pendingDeepResearch,
                            useStrongCorrelation = true,
                            visualMode = if (activeCalibration.measured) {
                                "Strong on-device YuNet/SFace correlation enabled with measured calibration"
                            } else {
                                "Strong on-device YuNet/SFace correlation enabled with reference manual-review policy"
                            }
                        )
                    } else {
                        coroutineScope.launch {
                            facePackInstalling = true
                            facePackMessage = null
                            facePackProgress = 0f
                            try {
                                modelPack.install { progress -> facePackProgress = progress }
                                consentStore.grantForInstalledPipeline()
                                faceStateRefresh++
                                startResolvedScan(
                                    input = input,
                                    deepResearch = pendingDeepResearch,
                                    useStrongCorrelation = true,
                                    visualMode =
                                        "Verified YuNet/SFace pack installed; strong local correlation enabled with reference manual-review policy"
                                )
                            } catch (cancelled: CancellationException) {
                                FaceCorrelationSessionPolicy.useBasicMatching()
                                throw cancelled
                            } catch (error: Exception) {
                                FaceCorrelationSessionPolicy.useBasicMatching()
                                facePackMessage = error.localizedMessage
                                    ?: "Unable to install the verified face-correlation models."
                            } finally {
                                facePackInstalling = false
                            }
                        }
                    }
                }
            }
        )
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
                        showFaceSetup -> "Face mode required"
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
                        startError != null || showFaceSetup -> 0f
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = startError?.let { "Scan cannot start" }
                        ?: if (showFaceSetup) {
                            "Waiting for per-scan face-correlation choice"
                        } else {
                            friendlyStageLabel(progressText)
                        },
                    color = if (startError == null) NeuralTheme.Cobalt else NeuralTheme.Crimson,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )

                io.dossier.app.ui.components.LinearWavyProgressIndicator(
                    progress = if (isScanning) null
                    else if (startError == null && !showFaceSetup) 1f
                    else 0f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
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
                            FaceCorrelationSessionPolicy.useBasicMatching()
                            navigationCompleted = true
                            onInvalidInput()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NeuralTheme.Cobalt),
                        shape = io.dossier.app.ui.theme.DossierButtonShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text("RETURN TO SEARCH", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }

                if (startError == null) {
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

                if (isScanning) {
                    Spacer(modifier = Modifier.height(12.dp))
                    onScanBackgrounded?.let { backgroundScan ->
                        OutlinedButton(
                            onClick = backgroundScan,
                            border = BorderStroke(
                                1.dp,
                                NeuralTheme.Cobalt.copy(alpha = 0.85f)
                            ),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = NeuralTheme.Cobalt
                            ),
                            shape = io.dossier.app.ui.theme.DossierButtonShape,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .semantics {
                                    contentDescription =
                                        "Continue using Dossier while this scan runs in the background"
                                }
                        ) {
                            Text(
                                text = "CONTINUE IN BACKGROUND",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                letterSpacing = 0.7.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    OutlinedButton(
                        onClick = {
                            FaceCorrelationSessionPolicy.useBasicMatching()
                            cancelledByUser = true
                            navigationCompleted = true
                            ScanCoordinatorRuntime.cancel()
                            onScanCancelled()
                        },
                        border = BorderStroke(1.2.dp, NeuralTheme.Crimson.copy(alpha = 0.8f)),
                        shape = io.dossier.app.ui.theme.DossierButtonShape,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NeuralTheme.Crimson),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
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

@Composable
private fun FaceCorrelationChoiceDialog(
    facePackReady: Boolean,
    consentActive: Boolean,
    measuredCalibration: Boolean,
    calibrationSummary: String,
    expectedPackBytes: Long,
    installing: Boolean,
    installProgress: Float,
    message: String?,
    onImportCalibration: () -> Unit,
    onDeleteModels: () -> Unit,
    onUseBasic: () -> Unit,
    onUseStrong: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!installing) onUseBasic() },
        title = {
            Text(
                text = if (facePackReady) {
                    "Choose face-correlation mode"
                } else {
                    "Enable strong local face correlation?"
                },
                color = NeuralTheme.TextPrimary,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Strong mode compares different photographs using YuNet five-landmark alignment and SFace embeddings. This is biometric-derived processing and is optional for every scan.",
                    color = NeuralTheme.TextSecondary,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp
                )
                Text(
                    text = "Images, aligned crops, landmarks and embeddings stay on this device and are discarded after each comparison. Results are supporting evidence, never proof of identity.",
                    color = NeuralTheme.TextSecondary,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp
                )

                if (!facePackReady) {
                    Text(
                        text = "The checksum-pinned OpenCV model pack is about ${formatFacePackSize(expectedPackBytes)} and is downloaded only after you select Install & Use Strong.",
                        color = NeuralTheme.TextSecondary,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp
                    )
                } else {
                    Text(
                        text = if (measuredCalibration) {
                            "Measured policy active: $calibrationSummary"
                        } else {
                            "Reference policy active: $calibrationSummary Scores remain manual-review evidence until a matching measured calibration is imported."
                        },
                        color = if (measuredCalibration) NeuralTheme.Emerald else NeuralTheme.Amber,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp
                    )
                    Text(
                        text = if (consentActive) {
                            "Installation consent is recorded, but this scan still requires a mode choice."
                        } else {
                            "The model files are installed, but installation consent is currently revoked."
                        },
                        color = NeuralTheme.TextSecondary,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                    OutlinedButton(
                        onClick = onImportCalibration,
                        enabled = !installing,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (measuredCalibration) {
                                "REPLACE MEASURED CALIBRATION"
                            } else {
                                "IMPORT MEASURED CALIBRATION"
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.5.sp
                        )
                    }
                    TextButton(
                        onClick = onDeleteModels,
                        enabled = !installing,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "DELETE MODELS & REVOKE CONSENT",
                            color = NeuralTheme.Crimson,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.5.sp
                        )
                    }
                }

                if (installing) {
                    LinearProgressIndicator(
                        progress = { installProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "Downloading and verifying models… ${(installProgress * 100).toInt()}%",
                        color = NeuralTheme.Cobalt,
                        fontSize = 11.5.sp
                    )
                }

                message?.let { value ->
                    Text(
                        text = value,
                        color = if (
                            value.contains("unable", ignoreCase = true) ||
                            value.contains("failed", ignoreCase = true)
                        ) NeuralTheme.Crimson else NeuralTheme.Cyan,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onUseStrong,
                enabled = !installing,
                colors = ButtonDefaults.buttonColors(containerColor = NeuralTheme.Cobalt)
            ) {
                Text(
                    text = if (facePackReady) "USE STRONG LOCAL" else "INSTALL & USE STRONG",
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.5.sp
                )
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onUseBasic, enabled = !installing) {
                Text("USE BASIC MATCHING", fontWeight = FontWeight.Bold, fontSize = 10.5.sp)
            }
        },
        containerColor = NeuralTheme.CardBackground
    )
}

private fun formatFacePackSize(bytes: Long): String =
    "%.1f MB".format(bytes.toDouble() / (1024.0 * 1024.0))

private fun friendlyStage(raw: String): String = when {
    raw.contains("DISCOVERING", ignoreCase = true) -> "Resolving name → username variants"
    raw.contains("COMPARING", ignoreCase = true) -> "Comparing selfie vs profile avatars"
    raw.contains("BREACH", ignoreCase = true) -> "Checking email breach / public exposure"
    raw.contains("ENTITY", ignoreCase = true) -> "Building entity relationship graph"
    raw.contains("COMPILING", ignoreCase = true) -> "Compiling exposure levels"
    raw.contains("GENERATING_AI", ignoreCase = true) -> "Generating analysis"
    raw.contains("AUDITING", ignoreCase = true) -> "Auditing place image metadata"
    raw.contains("CANCELLED", ignoreCase = true) -> "Scan cancelled"
    raw.contains("FAILED", ignoreCase = true) -> "Scan failed"
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
    raw.contains("FAILED", ignoreCase = true) -> "Failed"
    else -> raw.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
}
