package io.dossier.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.dossier.app.data.ai.AiCoreEngine
import io.dossier.app.data.ai.AiCoreStatus
import io.dossier.app.data.ai.AiModelDiscoveryService
import io.dossier.app.data.ai.AiProviderConfigStore
import io.dossier.app.data.ai.RemoteAiModel
import io.dossier.app.data.face.FaceEmbeddingCalibrationStore
import io.dossier.app.data.face.FaceEmbeddingModelStore
import io.dossier.app.domain.ai.AiProviderConfig
import io.dossier.app.domain.ai.AiProviderType
import io.dossier.app.domain.ai.LocalAiModelDownloader
import io.dossier.app.domain.ai.LocalAiModelType
import io.dossier.app.domain.scanner.ScanSession
import io.dossier.app.ui.components.AnimatedObsidianBackground
import io.dossier.app.ui.components.GeminiSpark
import io.dossier.app.ui.theme.NeuralTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Models tab — manage on-device AI engines. Moved here from IdentityScreen so the
 * identity panel stays focused. Each engine shows its REAL availability and
 * download status (no more silent 1KB dummy files / fabricated results).
 */
@Composable
fun ModelsScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val providerStore = remember { AiProviderConfigStore(context) }
    val aiCoreEngine = remember { AiCoreEngine(context) }
    val faceModelStore = remember { FaceEmbeddingModelStore(context) }
    val faceCalibrationStore = remember { FaceEmbeddingCalibrationStore(context) }

    val selectedModel by ScanSession.selectedModel.collectAsState()
    val isDownloadingMap by LocalAiModelDownloader.isDownloading.collectAsState()
    val downloadProgressMap by LocalAiModelDownloader.downloadProgress.collectAsState()
    val downloadErrorMap by LocalAiModelDownloader.downloadError.collectAsState()

    var refreshTrigger by remember { mutableStateOf(0) }
    var faceModelRefreshTrigger by remember { mutableStateOf(0) }
    var faceModelImportMessage by remember { mutableStateOf<String?>(null) }
    var aiCoreStatus by remember { mutableStateOf<AiCoreStatus?>(null) }
    var aiCoreChecking by remember { mutableStateOf(false) }
    var pendingImportModel by remember { mutableStateOf<LocalAiModelType?>(null) }
    var providerConfigs by remember { mutableStateOf(providerStore.getAll()) }
    // Install bundled FaceNet + factory calibration on first Models visit.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            faceModelStore.ensureModelAvailable()
        }
        faceModelRefreshTrigger++
    }
    val isFaceModelImported = remember(faceModelRefreshTrigger) { faceModelStore.isModelImported() }
    val faceModelSource = remember(faceModelRefreshTrigger) { faceModelStore.modelSourceLabel() }
    val faceUsingBundled = remember(faceModelRefreshTrigger) { faceModelStore.isUsingBundledModel() }
    val faceModelSizeBytes = remember(faceModelRefreshTrigger) { faceModelStore.importedModelSizeBytes() }
    val faceThresholds = remember(faceModelRefreshTrigger) { faceCalibrationStore.getThresholds() }

    fun saveProviderConfigs(configs: List<AiProviderConfig>) {
        val normalized = configs.mapIndexed { index, config -> config.copy(priority = index) }
        normalized.forEach(providerStore::save)
        providerConfigs = normalized
    }

    fun refreshAiCoreStatus(prepare: Boolean) {
        coroutineScope.launch {
            aiCoreChecking = true
            aiCoreStatus = if (prepare) aiCoreEngine.prepare() else aiCoreEngine.status()
            aiCoreChecking = false
        }
    }

    LaunchedEffect(Unit) {
        refreshAiCoreStatus(prepare = false)
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val modelType = pendingImportModel
        pendingImportModel = null
        if (uri != null && modelType != null) {
            coroutineScope.launch {
                LocalAiModelDownloader.importModel(context, modelType, uri)
                refreshTrigger++
            }
        }
    }
    val faceModelImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                runCatching {
                    faceModelStore.importModel(uri)
                }.onSuccess {
                    faceModelRefreshTrigger++
                    val modelSha256 = faceModelStore.importedModelSha256()
                    faceModelImportMessage =
                        "Face embedding model imported (${formatBytes(faceModelStore.importedModelSizeBytes())}). SHA-256 ${modelSha256?.take(12) ?: "unavailable"}..."
                }.onFailure { error ->
                    faceModelImportMessage = error.localizedMessage ?: "Unable to import face embedding model."
                }
            }
        }
    }
    val faceCalibrationImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                runCatching {
                    faceCalibrationStore.importCalibration(uri)
                }.onSuccess {
                    faceModelRefreshTrigger++
                    val thresholds = faceCalibrationStore.getThresholds()
                    faceModelImportMessage =
                        if (thresholds != null) {
                            "Face calibration imported: review >= ${formatThreshold(thresholds.reviewThreshold)}, high >= ${formatThreshold(thresholds.samePersonThreshold)}."
                        } else {
                            "Face calibration import did not produce usable thresholds."
                        }
                }.onFailure { error ->
                    faceModelImportMessage = error.localizedMessage ?: "Unable to import face calibration file."
                }
            }
        }
    }

    val cardShape = io.dossier.app.ui.theme.DossierCardShape

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedObsidianBackground(showGrid = true)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "ON-DEVICE AI ENGINES",
                    color = NeuralTheme.Cyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
                Spacer(modifier = Modifier.width(6.dp))
                GeminiSpark(size = 14.dp, glowColor = NeuralTheme.Cyan)
            }
            Text(
                text = "AI Engine Configuration",
                color = NeuralTheme.TextPrimary,
                fontSize = 24.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 6.dp, bottom = 6.dp)
            )
            Text(
                text = "All inference runs locally. ML Kit Vision is always available; optional LLM engines work only on supported devices.",
                color = NeuralTheme.TextSecondary,
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            HorizontalDivider(color = NeuralTheme.BorderColor, thickness = 1.dp)
            Spacer(modifier = Modifier.height(20.dp))

            LocalAiModelType.entries.forEach { modelType ->
                val isSelected = selectedModel == modelType
                val isDownloading = isDownloadingMap[modelType] ?: false
                val progress = downloadProgressMap[modelType] ?: 0f
                val downloadError = downloadErrorMap[modelType]
                val isDownloaded = remember(modelType, refreshTrigger) {
                    LocalAiModelDownloader.isModelDownloaded(context, modelType)
                }

                val itemBorder = if (isSelected) NeuralTheme.Cyan else NeuralTheme.BorderColor
                val itemBg = if (isSelected) NeuralTheme.Cobalt.copy(alpha = 0.12f) else NeuralTheme.CardBackground

                Card(
                    colors = CardDefaults.cardColors(containerColor = itemBg),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .border(1.dp, itemBorder, io.dossier.app.ui.theme.DossierCardShape),
                    shape = io.dossier.app.ui.theme.DossierCardShape
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = modelType.displayName,
                                    color = NeuralTheme.TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = modelType.description,
                                    color = NeuralTheme.TextSecondary,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .background(NeuralTheme.Emerald.copy(alpha = 0.15f), CircleShape)
                                        .border(1.dp, NeuralTheme.Emerald, CircleShape)
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text("ACTIVE", color = NeuralTheme.Emerald, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Status row — honest per engine, as HUD pills + notes.
                        when {
                            modelType == LocalAiModelType.MLKIT_VISION -> {
                                io.dossier.app.ui.components.HudStatusPill(
                                    text = "ON-DEVICE READY",
                                    level = io.dossier.app.ui.components.HudLevel.OK
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                StatusLine("Always available via Google Play Services", NeuralTheme.TextSecondary)
                            }
                            modelType == LocalAiModelType.AICORE -> {
                                io.dossier.app.ui.components.HudStatusPill(
                                    text = "DEVICE-DEPENDENT",
                                    level = io.dossier.app.ui.components.HudLevel.WARN
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                StatusLine("Available only on supported devices (not wired in this build)", NeuralTheme.TextSecondary)
                            }
                            isDownloading -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    LinearProgressIndicator(
                                        progress = { progress },
                                        color = NeuralTheme.Cobalt,
                                        trackColor = NeuralTheme.BorderColor,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(6.dp)
                                            .clip(CircleShape)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "${(progress * 100).toInt()}%",
                                        color = NeuralTheme.Cobalt,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            isDownloaded -> {
                                io.dossier.app.ui.components.HudStatusPill(
                                    text = "DOWNLOADED",
                                    level = io.dossier.app.ui.components.HudLevel.OK
                                )
                            }
                            downloadError != null -> {
                                io.dossier.app.ui.components.HudStatusPill(
                                    text = "FAILED",
                                    level = io.dossier.app.ui.components.HudLevel.CRIT
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                StatusLine(downloadError, NeuralTheme.Crimson)
                            }
                            modelType.downloadable -> {
                                io.dossier.app.ui.components.HudStatusPill(
                                    text = "NOT DOWNLOADED",
                                    level = io.dossier.app.ui.components.HudLevel.INFO
                                )
                            }
                            else -> {
                                io.dossier.app.ui.components.HudStatusPill(
                                    text = "UNAVAILABLE",
                                    level = io.dossier.app.ui.components.HudLevel.INFO
                                )
                            }
                        }

                        // Action row
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (modelType.downloadable && isDownloaded && !isDownloading) {
                                OutlinedButton(
                                    onClick = {
                                        LocalAiModelDownloader.deleteModel(context, modelType)
                                        refreshTrigger++
                                        if (isSelected) ScanSession.selectedModel.value = LocalAiModelType.DEFAULT
                                    },
                                    border = androidx.compose.foundation.BorderStroke(1.dp, NeuralTheme.Crimson.copy(alpha = 0.6f)),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                ) {
                                    Text("Clear", color = NeuralTheme.Crimson, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            if (modelType.downloadable && !isDownloading) {
                                OutlinedButton(
                                    onClick = {
                                        pendingImportModel = modelType
                                        importLauncher.launch(
                                            arrayOf(
                                                "application/octet-stream",
                                                "application/x-tflite",
                                                "application/vnd.google-mediapipe.task",
                                                "*/*"
                                            )
                                        )
                                    },
                                    border = androidx.compose.foundation.BorderStroke(1.dp, NeuralTheme.BorderColor),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                ) {
                                    Text("Import", color = NeuralTheme.Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            if (modelType.downloadable && modelType.url.isNotBlank() && !isDownloaded && !isDownloading) {
                                Button(
                                    onClick = {
                                        coroutineScope.launch {
                                            LocalAiModelDownloader.downloadModel(context, modelType)
                                            refreshTrigger++
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = NeuralTheme.CardBackground,
                                        contentColor = NeuralTheme.Cyan
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, NeuralTheme.BorderColor),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                ) {
                                    Text("Fetch", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            if (!isDownloading && (modelType == LocalAiModelType.MLKIT_VISION || isDownloaded || modelType == LocalAiModelType.AICORE)) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { ScanSession.selectedModel.value = modelType },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = NeuralTheme.Cyan,
                                        unselectedColor = NeuralTheme.TextSecondary
                                    )
                                )
                            }
                        }
                    }
                }
            }

            FaceEmbeddingModelCard(
                isReady = isFaceModelImported,
                sourceLabel = faceModelSource,
                usingBundled = faceUsingBundled,
                sizeBytes = faceModelSizeBytes,
                message = faceModelImportMessage,
                isCalibrationImported = faceThresholds != null,
                calibrationSummary = faceThresholds.summaryText(),
                onImport = { faceModelImportLauncher.launch(arrayOf("*/*")) },
                onRestoreBundled = {
                    runCatching {
                        faceModelStore.restoreBundledModel()
                        faceModelRefreshTrigger++
                        faceModelImportMessage = "Restored bundled FaceNet + factory calibration."
                    }.onFailure { error ->
                        faceModelImportMessage = error.localizedMessage ?: "Unable to restore bundled model."
                    }
                },
                onImportCalibration = {
                    faceCalibrationImportLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            AiCoreStatusCard(
                status = aiCoreStatus,
                checking = aiCoreChecking,
                onRefresh = { refreshAiCoreStatus(prepare = false) },
                onPrepare = { refreshAiCoreStatus(prepare = true) }
            )

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "ML Kit Vision runs on every device. Optional LLM engines work only on supported hardware and require a download.",
                color = NeuralTheme.TextSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            Spacer(modifier = Modifier.height(26.dp))
            HorizontalDivider(color = NeuralTheme.BorderColor, thickness = 1.dp)
            Spacer(modifier = Modifier.height(20.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "REMOTE AI PROVIDERS",
                    color = NeuralTheme.Cyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
                Spacer(modifier = Modifier.width(8.dp))
                GeminiSpark(size = 18.dp, glowColor = NeuralTheme.Cyan)
            }
            Text(
                text = "External AI Configuration",
                color = NeuralTheme.TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
            )

            providerConfigs.forEachIndexed { index, config ->
                ProviderConfigCard(
                    config = config,
                    canMoveUp = index > 0,
                    canMoveDown = index < providerConfigs.lastIndex,
                    onMoveUp = {
                        if (index > 0) {
                            val reordered = providerConfigs.toMutableList()
                            val moved = reordered.removeAt(index)
                            reordered.add(index - 1, moved)
                            saveProviderConfigs(reordered)
                        }
                    },
                    onMoveDown = {
                        if (index < providerConfigs.lastIndex) {
                            val reordered = providerConfigs.toMutableList()
                            val moved = reordered.removeAt(index)
                            reordered.add(index + 1, moved)
                            saveProviderConfigs(reordered)
                        }
                    },
                    onConfigChange = { updated ->
                        saveProviderConfigs(providerConfigs.map {
                            if (it.provider == updated.provider) updated else it
                        })
                    }
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

@Composable
private fun StatusLine(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text = text,
        color = color,
        fontSize = 11.5.sp,
        fontWeight = FontWeight.Medium
    )
}

@Composable
private fun AiCoreStatusCard(
    status: AiCoreStatus?,
    checking: Boolean,
    onRefresh: () -> Unit,
    onPrepare: () -> Unit
) {
    val cardShape = io.dossier.app.ui.theme.DossierCardShape
    val resolvedStatus = status
    val statusColor = when (resolvedStatus) {
        AiCoreStatus.Available -> NeuralTheme.Emerald
        AiCoreStatus.Downloadable,
        AiCoreStatus.Downloading -> NeuralTheme.Cyan
        is AiCoreStatus.Error -> NeuralTheme.Amber
        AiCoreStatus.Unavailable,
        null -> NeuralTheme.TextSecondary
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(NeuralTheme.CardBackground.copy(alpha = 0.65f), cardShape)
            .border(1.dp, NeuralTheme.BorderColor, cardShape)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "AICore Gemini Nano",
                    color = NeuralTheme.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (checking) {
                        "Checking Android AICore feature status..."
                    } else {
                        resolvedStatus?.detail ?: "Status not checked yet."
                    },
                    color = NeuralTheme.TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(statusColor.copy(alpha = 0.16f))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = if (checking) "Checking" else resolvedStatus?.label ?: "Unknown",
                    color = statusColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onRefresh,
                enabled = !checking,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = NeuralTheme.Cyan)
            ) {
                Text("Check", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = onPrepare,
                enabled = !checking && resolvedStatus == AiCoreStatus.Downloadable,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = NeuralTheme.Cyan)
            ) {
                Text("Download", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun FaceEmbeddingModelCard(
    isReady: Boolean,
    sourceLabel: String,
    usingBundled: Boolean,
    sizeBytes: Long,
    message: String?,
    isCalibrationImported: Boolean,
    calibrationSummary: String,
    onImport: () -> Unit,
    onRestoreBundled: () -> Unit,
    onImportCalibration: () -> Unit
) {
    val cardShape = io.dossier.app.ui.theme.DossierCardShape
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(NeuralTheme.CardBackground.copy(alpha = 0.65f), cardShape)
            .border(1.dp, NeuralTheme.BorderColor, cardShape)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Face Embedding Model",
                    color = NeuralTheme.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (isReady) {
                        "$sourceLabel ready (${formatBytes(sizeBytes)}). Selfie vs profile avatars score on-device. " +
                            "You can replace the model or calibration JSON for research."
                    } else {
                        "Bundled FaceNet failed to install from app assets."
                    },
                    color = NeuralTheme.TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        if (isReady) NeuralTheme.Cyan.copy(alpha = 0.16f) else NeuralTheme.BorderColor.copy(alpha = 0.35f)
                    )
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = if (!isReady) "Missing" else if (usingBundled) "Bundled" else "Custom",
                    color = if (isReady) NeuralTheme.Cyan else NeuralTheme.TextSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (message != null) {
            Text(
                text = message,
                color = if (isReady) NeuralTheme.Cyan else NeuralTheme.Amber,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(top = 10.dp)
            )
        }

        Text(
            text = calibrationSummary,
            color = if (isCalibrationImported) NeuralTheme.Cyan else NeuralTheme.Amber,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            modifier = Modifier.padding(top = 10.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = onImport,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = NeuralTheme.Cyan)
        ) {
            Text(
                text = "Replace with Custom Model",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }

        if (!usingBundled && isReady) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onRestoreBundled,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = NeuralTheme.Cyan)
            ) {
                Text(
                    text = "Restore Bundled FaceNet",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = onImportCalibration,
            enabled = isReady,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = NeuralTheme.Cyan)
        ) {
            Text(
                text = if (isCalibrationImported) "Replace Calibration JSON" else "Import Calibration JSON",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ProviderConfigCard(
    config: AiProviderConfig,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onConfigChange: (AiProviderConfig) -> Unit
) {
    val cardShape = io.dossier.app.ui.theme.DossierCardShape
    val coroutineScope = rememberCoroutineScope()
    var modelOptions by remember(config.provider, config.baseUrl, config.apiKey) {
        mutableStateOf(AiModelDiscoveryService.presets(config.provider))
    }
    var modelDiscoveryMessage by remember(config.provider, config.baseUrl, config.apiKey) {
        mutableStateOf<String?>(null)
    }
    var isRefreshingModels by remember(config.provider, config.baseUrl, config.apiKey) {
        mutableStateOf(false)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(NeuralTheme.CardBackground.copy(alpha = 0.85f), cardShape)
            .border(1.dp, NeuralTheme.BorderColor, cardShape)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = config.provider.displayName,
                    color = NeuralTheme.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = providerHint(config.provider),
                    color = NeuralTheme.TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
            Switch(
                checked = config.enabled,
                onCheckedChange = { onConfigChange(config.copy(enabled = it)) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = NeuralTheme.Cyan,
                    checkedTrackColor = NeuralTheme.Cobalt.copy(alpha = 0.45f),
                    uncheckedThumbColor = NeuralTheme.TextSecondary,
                    uncheckedTrackColor = NeuralTheme.BorderColor
                )
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Priority ${config.priority + 1}",
                color = NeuralTheme.TextSecondary,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(
                onClick = onMoveUp,
                enabled = canMoveUp,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier.height(32.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = NeuralTheme.Cyan)
            ) {
                Text("Up", fontSize = 11.sp)
            }
            OutlinedButton(
                onClick = onMoveDown,
                enabled = canMoveDown,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier.height(32.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = NeuralTheme.Cyan)
            ) {
                Text("Down", fontSize = 11.sp)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        ProviderTextField(
            value = config.baseUrl,
            onValueChange = { onConfigChange(config.copy(baseUrl = it)) },
            label = "Base URL"
        )
        Spacer(modifier = Modifier.height(8.dp))
        ProviderTextField(
            value = config.model,
            onValueChange = { onConfigChange(config.copy(model = it)) },
            label = "Model"
        )
        Spacer(modifier = Modifier.height(8.dp))
        ProviderModelChooser(
            currentModel = config.model,
            models = modelOptions,
            isRefreshing = isRefreshingModels,
            message = modelDiscoveryMessage,
            onRefresh = {
                isRefreshingModels = true
                modelDiscoveryMessage = null
                coroutineScope.launch {
                    val result = AiModelDiscoveryService().discover(config)
                    modelOptions = result.models
                    modelDiscoveryMessage = result.message
                    isRefreshingModels = false
                }
            },
            onModelSelected = { model ->
                onConfigChange(config.copy(model = model.id))
            }
        )
        Spacer(modifier = Modifier.height(8.dp))
        ProviderTextField(
            value = config.apiKey,
            onValueChange = { onConfigChange(config.copy(apiKey = it)) },
            label = if (config.provider == AiProviderType.OLLAMA) "Bearer token (optional)" else "API key",
            password = true
        )
    }
}

@Composable
private fun ProviderModelChooser(
    currentModel: String,
    models: List<RemoteAiModel>,
    isRefreshing: Boolean,
    message: String?,
    onRefresh: () -> Unit,
    onModelSelected: (RemoteAiModel) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val choices = remember(models, currentModel) {
        (listOfNotNull(
            currentModel.takeIf { it.isNotBlank() }?.let { RemoteAiModel(it, "$it (current)") }
        ) + models)
            .distinctBy { it.id }
            .take(100)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(
            onClick = onRefresh,
            enabled = !isRefreshing,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = NeuralTheme.Cyan),
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = if (isRefreshing) "Refreshing..." else "Refresh models",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = choices.isNotEmpty(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = NeuralTheme.TextPrimary),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Choose model",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.heightIn(max = 360.dp)
            ) {
                choices.forEach { model ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = model.displayName,
                                    fontSize = 12.sp,
                                    color = NeuralTheme.TextPrimary,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (model.displayName != model.id) {
                                    Text(
                                        text = model.id,
                                        fontSize = 10.sp,
                                        color = NeuralTheme.TextSecondary,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        },
                        onClick = {
                            expanded = false
                            onModelSelected(model)
                        }
                    )
                }
            }
        }
    }

    message?.let {
        Text(
            text = it,
            color = NeuralTheme.TextSecondary,
            fontSize = 10.5.sp,
            lineHeight = 14.sp,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
private fun ProviderTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    password: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = NeuralTheme.Cobalt,
            unfocusedBorderColor = NeuralTheme.BorderColor,
            focusedTextColor = NeuralTheme.TextPrimary,
            unfocusedTextColor = NeuralTheme.TextPrimary,
            focusedLabelColor = NeuralTheme.Cyan,
            unfocusedLabelColor = NeuralTheme.TextSecondary,
            cursorColor = NeuralTheme.Cobalt,
            focusedContainerColor = NeuralTheme.SurfaceDark,
            unfocusedContainerColor = NeuralTheme.SurfaceDark
        ),
        singleLine = true,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    )
}

private fun formatBytes(bytes: Long): String =
    when {
        bytes >= 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"
        bytes >= 1024L -> "${bytes / 1024L} KB"
        else -> "$bytes B"
    }

private fun io.dossier.app.data.face.FaceEmbeddingThresholds?.summaryText(): String =
    if (this != null) {
        "Calibrated thresholds: review >= ${formatThreshold(reviewThreshold)}, high >= ${formatThreshold(samePersonThreshold)} ($source, ${positivePairCount}+/${negativePairCount}- pairs, model ${modelSha256.take(12)}...)."
    } else {
        "No calibrated threshold file imported; face scores are computed but not used as identity evidence."
    }

private fun formatThreshold(value: Float): String =
    "%.2f".format(value)

private fun providerHint(provider: AiProviderType): String = when (provider) {
    AiProviderType.OPENAI -> "OpenAI-compatible chat completions."
    AiProviderType.ANTHROPIC -> "Anthropic Messages API."
    AiProviderType.OLLAMA -> "Local, LAN, hosted, or subscription Ollama-compatible endpoint."
    AiProviderType.HUGGINGFACE -> "Hugging Face Inference API model endpoint."
    AiProviderType.OPENROUTER -> "OpenRouter chat completions gateway."
}
