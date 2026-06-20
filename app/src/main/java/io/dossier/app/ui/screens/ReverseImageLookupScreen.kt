package io.dossier.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.dossier.app.domain.model.ReverseImageLookupResult
import io.dossier.app.domain.model.ReverseVideoLookupResult
import io.dossier.app.domain.place.ReverseImageLookupService
import io.dossier.app.domain.place.ReverseVideoLookupService
import io.dossier.app.ui.components.AnimatedObsidianBackground
import io.dossier.app.ui.components.CircularWavyProgressIndicator
import io.dossier.app.ui.components.GeminiSpark
import io.dossier.app.ui.theme.NeuralTheme
import kotlinx.coroutines.launch

/**
 * Reverse Image Lookup tab — estimates WHERE an image was taken using EXIF GPS,
 * on-device vision (OCR + scene labels), and public-web search of the extracted
 * clues. Faces trigger the safety gate (identity search skipped, location
 * continues). Image bytes never leave the device.
 */
@Composable
fun ReverseImageLookupScreen(onNavigateToBrowser: (String) -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedVideoUri by remember { mutableStateOf<Uri?>(null) }
    var result by remember { mutableStateOf<ReverseImageLookupResult?>(null) }
    var videoResult by remember { mutableStateOf<ReverseVideoLookupResult?>(null) }
    var isAnalyzing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Shared analysis trigger — called when an image is selected (camera or gallery).
    fun startAnalysis(uri: Uri) {
        selectedUri = uri
        selectedVideoUri = null
        result = null
        videoResult = null
        error = null
        isAnalyzing = true
        coroutineScope.launch {
            try {
                result = ReverseImageLookupService(context).lookup(uri, deepResearch = io.dossier.app.domain.scanner.ScanSession.deepResearchEnabled.value)
            } catch (e: Exception) {
                e.printStackTrace()
                error = "Lookup failed: ${e.localizedMessage ?: e.javaClass.simpleName}"
            } finally {
                isAnalyzing = false
            }
        }
    }

    fun startVideoAnalysis(uri: Uri) {
        selectedVideoUri = uri
        selectedUri = null
        result = null
        videoResult = null
        error = null
        isAnalyzing = true
        coroutineScope.launch {
            try {
                videoResult = ReverseVideoLookupService(context).lookup(uri, deepResearch = io.dossier.app.domain.scanner.ScanSession.deepResearchEnabled.value)
            } catch (e: Exception) {
                e.printStackTrace()
                error = "Video lookup failed: ${e.localizedMessage ?: e.javaClass.simpleName}"
            } finally {
                isAnalyzing = false
            }
        }
    }

    val videoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { startVideoAnalysis(it) }
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
                    text = "REVERSE MEDIA LOOKUP",
                    color = NeuralTheme.Cyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
                Spacer(modifier = Modifier.width(8.dp))
                GeminiSpark(size = 18.dp, glowColor = NeuralTheme.Cyan)
            }
            Text(
                text = "Image & Video Location Intelligence",
                color = NeuralTheme.TextPrimary,
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
            )
            Text(
                text = "Estimate where media was captured. EXIF GPS for images, sampled video frames, on-device OCR/scene detection, and public-web search of extracted clues. Faces disable identity search; location lookup continues.",
                color = NeuralTheme.TextSecondary,
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            HorizontalDivider(color = NeuralTheme.BorderColor, thickness = 1.dp)
            Spacer(modifier = Modifier.height(20.dp))

            // Image picker + preview (camera + gallery)
            io.dossier.app.ui.components.ImageSourcePicker(
                label = "Target Image",
                selectedUri = selectedUri,
                onImageSelected = { startAnalysis(it) }
            )

            Spacer(modifier = Modifier.height(12.dp))

            VideoSourcePicker(
                label = "Target Video",
                selectedUri = selectedVideoUri,
                onClick = { videoLauncher.launch("video/*") }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Deep Research toggle — fetches top evidence pages for richer location context.
            io.dossier.app.ui.components.DeepResearchToggle()

            if (selectedUri != null || selectedVideoUri != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = NeuralTheme.CardBackground.copy(alpha = 0.85f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, NeuralTheme.BorderColor, cardShape),
                    shape = cardShape
                ) {
                    Image(
                        painter = androidx.compose.ui.res.painterResource(
                            if (selectedVideoUri != null) android.R.drawable.ic_media_play else android.R.drawable.ic_menu_gallery
                        ),
                        contentDescription = if (selectedVideoUri != null) "Selected video" else "Selected image preview",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(topStart = 24.dp, bottomEnd = 24.dp))
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            if (isAnalyzing) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Lottie investigate + web loops layered with the spinner.
                    io.dossier.app.ui.components.LottieLoop(
                        tag = io.dossier.app.ui.components.LottieTags.INVESTIGATE,
                        size = 64.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    CircularWavyProgressIndicator(
                        size = 32.dp,
                        brush = NeuralTheme.GeminiGradient,
                        strokeWidth = 2.5.dp,
                        waveCount = 5,
                        amplitude = 2.5.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    io.dossier.app.ui.components.LottieLoop(
                        tag = io.dossier.app.ui.components.LottieTags.WEB,
                        size = 64.dp
                    )
                }
                Text(
                    text = "Extracting on-device media clues + searching web...",
                    color = NeuralTheme.Cobalt,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                io.dossier.app.ui.components.ScanlineStrip(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    height = 2.dp
                )
            }

            error?.let {
                Card(
                    colors = CardDefaults.cardColors(containerColor = NeuralTheme.Crimson.copy(alpha = 0.1f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, NeuralTheme.Crimson.copy(alpha = 0.5f), cardShape),
                    shape = cardShape
                ) {
                    Text(
                        text = it,
                        color = NeuralTheme.Crimson,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            result?.let { res -> RenderLookupResult(res, cardShape, onNavigateToBrowser) }
            videoResult?.let { res -> RenderVideoLookupResult(res, cardShape, onNavigateToBrowser) }

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = NeuralTheme.CardBackground.copy(alpha = 0.6f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, NeuralTheme.BorderColor, cardShape),
                shape = cardShape
            ) {
                Text(
                    text = "Privacy: location clues are extracted on-device, then only text/label clues are searched on the public web. Image and video bytes never leave your device. No facial identification is performed.",
                    color = NeuralTheme.TextSecondary,
                    fontSize = 11.sp,
                    fontStyle = FontStyle.Italic,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(16.dp)
                )
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RenderLookupResult(
    res: ReverseImageLookupResult,
    cardShape: RoundedCornerShape,
    onNavigateToBrowser: (String) -> Unit,
    showGps: Boolean = true
) {
    // Face safety gate warning — calm static border
    if (res.faceDetected) {
        Card(
            colors = CardDefaults.cardColors(containerColor = NeuralTheme.Crimson.copy(alpha = 0.08f)),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, NeuralTheme.Crimson.copy(alpha = 0.5f), cardShape),
            shape = cardShape
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(18.dp)) {
                        GeminiSpark(size = 14.dp, glowColor = NeuralTheme.Crimson)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "FACE DETECTED — IDENTITY SEARCH DISABLED",
                        color = NeuralTheme.Crimson,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = res.faceWarning ?: "",
                    color = NeuralTheme.TextPrimary,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp
                )
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
    }

    SectionHeader("Resolved Location")

    // Resolved location + maps link
    Card(
        colors = CardDefaults.cardColors(containerColor = NeuralTheme.CardBackground.copy(alpha = 0.85f)),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, NeuralTheme.BorderColor, cardShape),
        shape = cardShape
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "ESTIMATED LOCATION",
                color = NeuralTheme.TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Text(
                text = res.resolvedLocation ?: "Could not resolve a location from available clues",
                color = if (res.resolvedLocation != null) NeuralTheme.Emerald else NeuralTheme.TextSecondary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp)
            )

            res.mapsUrl?.let { url ->
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Open in Maps →",
                    color = NeuralTheme.Cyan,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable { onNavigateToBrowser(url) }
                )
            }
        }
    }

    if (showGps) {
        Spacer(modifier = Modifier.height(20.dp))

        // GPS card
        SectionHeader("EXIF GPS")
        Card(
            colors = CardDefaults.cardColors(containerColor = NeuralTheme.CardBackground.copy(alpha = 0.85f)),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, NeuralTheme.BorderColor, cardShape),
            shape = cardShape
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "GPS Coordinates",
                        color = NeuralTheme.TextSecondary,
                        fontSize = 13.sp
                    )
                    Text(
                        text = res.gps ?: "NOT EMBEDDED",
                        color = if (res.gps != null) NeuralTheme.Crimson else NeuralTheme.Emerald,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (res.gps == null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "No EXIF GPS — location resolved from visual + web clues instead.",
                        color = NeuralTheme.TextSecondary,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }

    // Extracted OCR text
    if (!res.extractedText.isNullOrBlank()) {
        Spacer(modifier = Modifier.height(20.dp))
        SectionHeader("On-Device OCR (Visible Text)")
        Card(
            colors = CardDefaults.cardColors(containerColor = NeuralTheme.CardBackground.copy(alpha = 0.85f)),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, NeuralTheme.BorderColor, cardShape),
            shape = cardShape
        ) {
            Text(
                text = res.extractedText,
                color = NeuralTheme.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(18.dp)
            )
        }
    }

    // Scene labels
    if (res.labels.isNotEmpty()) {
        Spacer(modifier = Modifier.height(20.dp))
        SectionHeader("Detected Scene Labels")
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            res.labels.forEach { label ->
                Box(
                    modifier = Modifier
                        .background(NeuralTheme.Cyan.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                        .border(1.dp, NeuralTheme.Cyan.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "${label.text} (${(label.confidence * 100).toInt()}%)",
                        color = NeuralTheme.Cyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    // Web evidence
    if (res.webEvidence.isNotEmpty()) {
        Spacer(modifier = Modifier.height(20.dp))
        SectionHeader("Public Web Evidence")
        res.webEvidence.forEach { ev ->
            Card(
                colors = CardDefaults.cardColors(containerColor = NeuralTheme.CardBackground.copy(alpha = 0.85f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .border(1.dp, NeuralTheme.BorderColor, cardShape),
                shape = cardShape
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = ev.title,
                        color = NeuralTheme.TextPrimary,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (ev.snippet.isNotBlank()) {
                        Text(
                            text = ev.snippet,
                            color = NeuralTheme.TextSecondary,
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    if (ev.url.startsWith("http")) {
                        Text(
                            text = ev.url,
                            color = NeuralTheme.Cyan,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            textDecoration = TextDecoration.Underline,
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .clickable { onNavigateToBrowser(ev.url) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RenderVideoLookupResult(
    res: ReverseVideoLookupResult,
    cardShape: RoundedCornerShape,
    onNavigateToBrowser: (String) -> Unit
) {
    SectionHeader("Video Sampling")
    Card(
        colors = CardDefaults.cardColors(containerColor = NeuralTheme.CardBackground.copy(alpha = 0.85f)),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, NeuralTheme.BorderColor, cardShape),
        shape = cardShape
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Sampled frames",
                    color = NeuralTheme.TextSecondary,
                    fontSize = 13.sp
                )
                Text(
                    text = res.sampledFrames.toString(),
                    color = NeuralTheme.Cyan,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Duration",
                    color = NeuralTheme.TextSecondary,
                    fontSize = 13.sp
                )
                Text(
                    text = formatDuration(res.durationMs),
                    color = NeuralTheme.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            if (res.frameSummaries.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                res.frameSummaries.take(5).forEach { frame ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                    ) {
                        Text(
                            text = "FRAME ${formatDuration(frame.timestampMs)}",
                            color = NeuralTheme.TextSecondary,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        if (!frame.extractedText.isNullOrBlank()) {
                            Text(
                                text = frame.extractedText.take(140),
                                color = NeuralTheme.TextPrimary,
                                fontSize = 11.5.sp,
                                lineHeight = 15.sp,
                                modifier = Modifier.padding(top = 3.dp)
                            )
                        }
                        if (frame.labels.isNotEmpty()) {
                            FlowRow(
                                modifier = Modifier.padding(top = 5.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                frame.labels.take(4).forEach { label ->
                                    Text(
                                        text = label.text,
                                        color = NeuralTheme.Cyan,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                        if (frame.faceDetected) {
                            Text(
                                text = "Face safety gate active for this frame",
                                color = NeuralTheme.Crimson,
                                fontSize = 10.5.sp,
                                modifier = Modifier.padding(top = 3.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(20.dp))
    RenderLookupResult(
        res = res.asImageLookupResult(),
        cardShape = cardShape,
        onNavigateToBrowser = onNavigateToBrowser,
        showGps = false
    )
}

@Composable
private fun VideoSourcePicker(
    label: String,
    selectedUri: Uri?,
    onClick: () -> Unit
) {
    val outlineColor = if (selectedUri != null) NeuralTheme.Cobalt else NeuralTheme.BorderColor

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = NeuralTheme.TextSecondary,
            fontSize = 11.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(NeuralTheme.CardBackground, io.dossier.app.ui.theme.DossierCardShape)
                .border(1.dp, outlineColor, io.dossier.app.ui.theme.DossierCardShape)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick)
                    .background(NeuralTheme.SurfaceDark.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Select Video",
                    color = NeuralTheme.Cobalt,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        if (selectedUri != null) {
            Text(
                text = selectedUri.path?.substringAfterLast("/") ?: "Video selected",
                color = NeuralTheme.Cobalt,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

private fun ReverseVideoLookupResult.asImageLookupResult(): ReverseImageLookupResult {
    return ReverseImageLookupResult(
        gps = null,
        extractedText = extractedText,
        labels = labels,
        faceDetected = faceDetected,
        faceWarning = faceWarning,
        resolvedLocation = resolvedLocation,
        mapsUrl = mapsUrl,
        webEvidence = webEvidence
    )
}

private fun formatDuration(durationMs: Long?): String {
    if (durationMs == null) return "Unknown"
    val totalSeconds = durationMs / 1_000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    val tenths = (durationMs % 1_000L) / 100L
    return if (minutes > 0L) {
        "%d:%02d.%d".format(minutes, seconds, tenths)
    } else {
        "%d.%ds".format(seconds, tenths)
    }
}

@Composable
private fun SectionHeader(text: String) {
    // Delegates to the shared HUD label kit for consistency across screens.
    io.dossier.app.ui.components.HudLabel(
        text = text.uppercase(),
        marker = "»",
        blinkDot = true,
        dotLevel = io.dossier.app.ui.components.HudLevel.INFO
    )
}
