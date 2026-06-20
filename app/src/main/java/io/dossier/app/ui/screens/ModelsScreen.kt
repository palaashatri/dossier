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
import io.dossier.app.data.ai.AiProviderConfigStore
import io.dossier.app.domain.ai.AiProviderConfig
import io.dossier.app.domain.ai.AiProviderType
import io.dossier.app.domain.ai.LocalAiModelDownloader
import io.dossier.app.domain.ai.LocalAiModelType
import io.dossier.app.domain.scanner.ScanSession
import io.dossier.app.ui.components.AnimatedObsidianBackground
import io.dossier.app.ui.components.GeminiSpark
import io.dossier.app.ui.theme.NeuralTheme
import kotlinx.coroutines.launch

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

    val selectedModel by ScanSession.selectedModel.collectAsState()
    val isDownloadingMap by LocalAiModelDownloader.isDownloading.collectAsState()
    val downloadProgressMap by LocalAiModelDownloader.downloadProgress.collectAsState()
    val downloadErrorMap by LocalAiModelDownloader.downloadError.collectAsState()

    var refreshTrigger by remember { mutableStateOf(0) }
    var pendingImportModel by remember { mutableStateOf<LocalAiModelType?>(null) }
    var providerConfigs by remember { mutableStateOf(providerStore.getAll()) }

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
                Spacer(modifier = Modifier.width(8.dp))
                GeminiSpark(size = 18.dp, glowColor = NeuralTheme.Cyan)
            }
            Text(
                text = "AI Engine Configuration",
                color = NeuralTheme.TextPrimary,
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
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

            providerConfigs.forEach { config ->
                ProviderConfigCard(
                    config = config,
                    onConfigChange = { updated ->
                        providerStore.save(updated)
                        providerConfigs = providerConfigs.map {
                            if (it.provider == updated.provider) updated else it
                        }
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
private fun ProviderConfigCard(
    config: AiProviderConfig,
    onConfigChange: (AiProviderConfig) -> Unit
) {
    val cardShape = io.dossier.app.ui.theme.DossierCardShape

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
        ProviderTextField(
            value = config.apiKey,
            onValueChange = { onConfigChange(config.copy(apiKey = it)) },
            label = if (config.provider == AiProviderType.OLLAMA) "Bearer token (optional)" else "API key",
            password = true
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

private fun providerHint(provider: AiProviderType): String = when (provider) {
    AiProviderType.OPENAI -> "OpenAI-compatible chat completions."
    AiProviderType.ANTHROPIC -> "Anthropic Messages API."
    AiProviderType.OLLAMA -> "Local, LAN, hosted, or subscription Ollama-compatible endpoint."
    AiProviderType.HUGGINGFACE -> "Hugging Face Inference API model endpoint."
    AiProviderType.OPENROUTER -> "OpenRouter chat completions gateway."
}
