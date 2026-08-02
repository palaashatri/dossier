package io.dossier.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import io.dossier.app.domain.scanner.ScanSession
import io.dossier.app.ui.components.AnimatedObsidianBackground
import io.dossier.app.ui.components.CircularWavyProgressIndicator
import io.dossier.app.ui.components.GeminiSpark
import io.dossier.app.ui.theme.NeuralTheme
import kotlinx.coroutines.launch

/**
 * Reverse media lookup with on-device OCR/location analysis and genuine whole-image
 * duplicate/repost matching. The selected image is not uploaded; public candidate
 * images are downloaded and compared locally using perceptual fingerprints.
 */
@Composable
fun ReverseImageLookupScreen(onNavigateToBrowser: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cardShape = io.dossier.app.ui.theme.DossierCardShape

    var selectedImage by remember { mutableStateOf<Uri?>(null) }
    var selectedVideo by remember { mutableStateOf<Uri?>(null) }
    var imageResult by remember { mutableStateOf<ReverseImageLookupResult?>(null) }
    var videoResult by remember { mutableStateOf<ReverseVideoLookupResult?>(null) }
    var analyzing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun analyzeImage(uri: Uri) {
        selectedImage = uri
        selectedVideo = null
        imageResult = null
        videoResult = null
        error = null
        analyzing = true
        scope.launch {
            try {
                imageResult = ReverseImageLookupService(context).lookup(
                    uri,
                    deepResearch = ScanSession.deepResearchEnabled.value
                )
            } catch (throwable: Throwable) {
                error = "Lookup failed: ${throwable.localizedMessage ?: throwable.javaClass.simpleName}"
            } finally {
                analyzing = false
            }
        }
    }

    fun analyzeVideo(uri: Uri) {
        selectedVideo = uri
        selectedImage = null
        imageResult = null
        videoResult = null
        error = null
        analyzing = true
        scope.launch {
            try {
                videoResult = ReverseVideoLookupService(context).lookup(
                    uri,
                    deepResearch = ScanSession.deepResearchEnabled.value
                )
            } catch (throwable: Throwable) {
                error = "Video lookup failed: ${throwable.localizedMessage ?: throwable.javaClass.simpleName}"
            } finally {
                analyzing = false
            }
        }
    }

    val videoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(::analyzeVideo)
    }

    Box(Modifier.fillMaxSize()) {
        AnimatedObsidianBackground(showGrid = true)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "REVERSE MEDIA LOOKUP",
                    color = NeuralTheme.Cyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
                Spacer(Modifier.width(6.dp))
                GeminiSpark(size = 14.dp, glowColor = NeuralTheme.Cyan)
            }
            Text(
                "Location + Visual Repost Discovery",
                color = NeuralTheme.TextPrimary,
                fontSize = 24.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 6.dp, bottom = 6.dp)
            )
            Text(
                "Extract EXIF, OCR, and scene clues; search several public image indexes; then compare downloaded candidates locally for exact copies, resizes, recompressions, screenshots, and modest crops.",
                color = NeuralTheme.TextSecondary,
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            HorizontalDivider(color = NeuralTheme.BorderColor)
            Spacer(Modifier.height(20.dp))

            io.dossier.app.ui.components.ImageSourcePicker(
                label = "Target Image",
                selectedUri = selectedImage,
                onImageSelected = ::analyzeImage
            )
            Spacer(Modifier.height(12.dp))
            VideoSourcePicker(
                label = "Target Video",
                selectedUri = selectedVideo,
                onClick = { videoLauncher.launch("video/*") }
            )
            Spacer(Modifier.height(16.dp))
            io.dossier.app.ui.components.DeepResearchToggle()

            if (analyzing) {
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularWavyProgressIndicator(
                        size = 34.dp,
                        brush = NeuralTheme.GeminiGradient,
                        strokeWidth = 2.5.dp,
                        waveCount = 5,
                        amplitude = 2.5.dp
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Fingerprinting locally + checking public candidates…",
                        color = NeuralTheme.Cobalt,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            error?.let { message ->
                Spacer(Modifier.height(16.dp))
                InfoCard(message, NeuralTheme.Crimson, cardShape)
            }

            imageResult?.let {
                Spacer(Modifier.height(20.dp))
                RenderLookupResult(it, cardShape, onNavigateToBrowser)
            }
            videoResult?.let {
                Spacer(Modifier.height(20.dp))
                RenderVideoLookupResult(it, cardShape, onNavigateToBrowser)
            }

            Spacer(Modifier.height(20.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = NeuralTheme.CardBackground.copy(alpha = 0.65f)),
                modifier = Modifier.fillMaxWidth().border(1.dp, NeuralTheme.BorderColor, cardShape),
                shape = cardShape
            ) {
                Text(
                    "Privacy: the selected image/video is never uploaded by Dossier. Only text and identity clues are sent as search queries. Public candidate images are downloaded and compared on-device. Facial identification across different photos is not performed.",
                    color = NeuralTheme.TextSecondary,
                    fontSize = 11.sp,
                    fontStyle = FontStyle.Italic,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(16.dp)
                )
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RenderLookupResult(
    result: ReverseImageLookupResult,
    cardShape: RoundedCornerShape,
    onNavigateToBrowser: (String) -> Unit,
    showGps: Boolean = true
) {
    if (result.faceDetected) {
        InfoCard(
            result.faceWarning ?: "Face detected; facial identification remains disabled.",
            NeuralTheme.Crimson,
            cardShape,
            title = "FACE DETECTED — NO FACIAL IDENTIFICATION"
        )
        Spacer(Modifier.height(18.dp))
    }

    SectionHeader("Visual duplicate / repost matches")
    result.visualSearchNote?.let { note ->
        Text(
            note,
            color = NeuralTheme.TextSecondary,
            fontSize = 11.5.sp,
            lineHeight = 16.sp,
            modifier = Modifier.padding(bottom = 10.dp)
        )
    }
    if (result.visualMatches.isEmpty()) {
        InfoCard(
            "No locally verified near-duplicate was found in the current candidate corpus.",
            NeuralTheme.TextSecondary,
            cardShape
        )
    } else {
        result.visualMatches.forEach { match ->
            Card(
                colors = CardDefaults.cardColors(containerColor = NeuralTheme.CardBackground.copy(alpha = 0.88f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 9.dp)
                    .border(1.dp, NeuralTheme.Cyan.copy(alpha = 0.38f), cardShape),
                shape = cardShape
            ) {
                Column(Modifier.padding(15.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            match.matchType.uppercase(),
                            color = NeuralTheme.Cyan,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.sp
                        )
                        Text(
                            "${(match.similarity * 100).toInt()}%",
                            color = if (match.similarity >= 0.9f) NeuralTheme.Emerald else NeuralTheme.Cobalt,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        match.title,
                        color = NeuralTheme.TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    Text(
                        "${match.source} • ${match.evidence}",
                        color = NeuralTheme.TextSecondary,
                        fontSize = 10.5.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    val target = match.sourcePageUrl.takeIf { it.startsWith("http") } ?: match.imageUrl
                    Text(
                        "Open source page →",
                        color = NeuralTheme.Cyan,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier.padding(top = 7.dp).clickable { onNavigateToBrowser(target) }
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(20.dp))
    SectionHeader("Resolved location")
    Card(
        colors = CardDefaults.cardColors(containerColor = NeuralTheme.CardBackground.copy(alpha = 0.85f)),
        modifier = Modifier.fillMaxWidth().border(1.dp, NeuralTheme.BorderColor, cardShape),
        shape = cardShape
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                result.resolvedLocation ?: "Could not resolve a location from available clues",
                color = if (result.resolvedLocation != null) NeuralTheme.Emerald else NeuralTheme.TextSecondary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
            result.mapsUrl?.let { url ->
                Text(
                    "Open in Maps →",
                    color = NeuralTheme.Cyan,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.padding(top = 8.dp).clickable { onNavigateToBrowser(url) }
                )
            }
        }
    }

    if (showGps) {
        Spacer(Modifier.height(18.dp))
        SectionHeader("EXIF GPS")
        InfoCard(result.gps ?: "No GPS metadata embedded", if (result.gps != null) NeuralTheme.Emerald else NeuralTheme.TextSecondary, cardShape)
    }

    if (!result.extractedText.isNullOrBlank()) {
        Spacer(Modifier.height(18.dp))
        SectionHeader("On-device OCR")
        InfoCard(result.extractedText, NeuralTheme.TextPrimary, cardShape)
    }

    if (result.labels.isNotEmpty()) {
        Spacer(Modifier.height(18.dp))
        SectionHeader("Detected scene labels")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            result.labels.forEach { label ->
                Text(
                    "${label.text} ${(label.confidence * 100).toInt()}%",
                    color = NeuralTheme.Cyan,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .padding(bottom = 7.dp)
                        .background(NeuralTheme.Cyan.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                        .border(1.dp, NeuralTheme.Cyan.copy(alpha = 0.28f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 9.dp, vertical = 6.dp)
                )
            }
        }
    }

    if (result.webEvidence.isNotEmpty()) {
        Spacer(Modifier.height(18.dp))
        SectionHeader("Public web location evidence")
        result.webEvidence.forEach { evidence ->
            Card(
                colors = CardDefaults.cardColors(containerColor = NeuralTheme.CardBackground.copy(alpha = 0.85f)),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).border(1.dp, NeuralTheme.BorderColor, cardShape),
                shape = cardShape
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(evidence.title, color = NeuralTheme.TextPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                    if (evidence.snippet.isNotBlank()) {
                        Text(evidence.snippet, color = NeuralTheme.TextSecondary, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    if (evidence.url.startsWith("http")) {
                        Text(
                            evidence.url,
                            color = NeuralTheme.Cyan,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.5.sp,
                            textDecoration = TextDecoration.Underline,
                            modifier = Modifier.padding(top = 5.dp).clickable { onNavigateToBrowser(evidence.url) }
                        )
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(18.dp))
    SectionHeader("Optional external visual indexes")
    Text(
        "Dossier does not silently upload your image. For broader internet-scale coverage, open one of these services and explicitly choose the image yourself:",
        color = NeuralTheme.TextSecondary,
        fontSize = 11.5.sp,
        lineHeight = 16.sp,
        modifier = Modifier.padding(bottom = 8.dp)
    )
    listOf(
        "Yandex Images" to "https://yandex.com/images/",
        "Google Lens" to "https://lens.google.com/",
        "TinEye" to "https://tineye.com/"
    ).forEach { (name, url) ->
        Text(
            "$name →",
            color = NeuralTheme.Cyan,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.padding(vertical = 4.dp).clickable { onNavigateToBrowser(url) }
        )
    }
}

@Composable
private fun RenderVideoLookupResult(
    result: ReverseVideoLookupResult,
    cardShape: RoundedCornerShape,
    onNavigateToBrowser: (String) -> Unit
) {
    SectionHeader("Video sampling")
    InfoCard(
        "Sampled ${result.sampledFrames} frames • duration ${formatDuration(result.durationMs)}",
        NeuralTheme.TextPrimary,
        cardShape
    )
    Spacer(Modifier.height(18.dp))
    RenderLookupResult(result.asImageResult(), cardShape, onNavigateToBrowser, showGps = false)
}

@Composable
private fun VideoSourcePicker(label: String, selectedUri: Uri?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, color = NeuralTheme.TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = NeuralTheme.CardBackground),
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
            shape = io.dossier.app.ui.theme.DossierCardShape
        ) {
            Text(
                selectedUri?.path?.substringAfterLast('/') ?: "Select Video",
                color = if (selectedUri != null) NeuralTheme.Cobalt else NeuralTheme.TextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(18.dp)
            )
        }
    }
}

@Composable
private fun InfoCard(
    message: String,
    accent: Color,
    cardShape: RoundedCornerShape,
    title: String? = null
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = NeuralTheme.CardBackground.copy(alpha = 0.85f)),
        modifier = Modifier.fillMaxWidth().border(1.dp, accent.copy(alpha = 0.45f), cardShape),
        shape = cardShape
    ) {
        Column(Modifier.padding(16.dp)) {
            title?.let {
                Text(it, color = accent, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                Spacer(Modifier.height(6.dp))
            }
            Text(message, color = accent, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

private fun ReverseVideoLookupResult.asImageResult(): ReverseImageLookupResult =
    ReverseImageLookupResult(
        gps = null,
        extractedText = extractedText,
        labels = labels,
        faceDetected = faceDetected,
        faceWarning = faceWarning,
        resolvedLocation = resolvedLocation,
        mapsUrl = mapsUrl,
        webEvidence = webEvidence
    )

private fun formatDuration(durationMs: Long?): String {
    if (durationMs == null) return "unknown"
    val seconds = durationMs / 1_000L
    return if (seconds >= 60) "%d:%02d".format(seconds / 60, seconds % 60) else "${seconds}s"
}

@Composable
private fun SectionHeader(text: String) {
    io.dossier.app.ui.components.HudLabel(
        text = text.uppercase(),
        marker = "»",
        blinkDot = true,
        dotLevel = io.dossier.app.ui.components.HudLevel.INFO
    )
}
