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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.dossier.app.domain.case.UserCorrection
import io.dossier.app.domain.case.UserCorrectionDecision
import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceRuntimeCache
import io.dossier.app.domain.evidence.persistedLinkedProfileEvidenceId
import io.dossier.app.domain.model.ReverseImageLookupResult
import io.dossier.app.domain.model.ReverseVideoLookupResult
import io.dossier.app.domain.place.ReverseImageLookupService
import io.dossier.app.domain.place.ReverseVideoLookupService
import io.dossier.app.domain.place.MediaIntelligenceSession
import io.dossier.app.domain.scanner.ScanSession
import io.dossier.app.ui.components.AnimatedObsidianBackground
import io.dossier.app.ui.components.CircularWavyProgressIndicator
import io.dossier.app.ui.components.GeminiSpark
import io.dossier.app.ui.theme.NeuralTheme
import java.time.Instant
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Guards Compose-visible media state when an older lookup completes after a
 * newer image/video request. The persistence layer has its own binding token;
 * this generation protects the transient selection, result, error, and
 * progress state rendered by this screen.
 */
internal class ReverseMediaLookupRequestGate {
    private val generation = AtomicLong(0L)

    fun begin(): Long = generation.incrementAndGet()

    fun isCurrent(token: Long): Boolean = generation.get() == token
}

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
    val evidenceCollection by EvidenceRuntimeCache.collection.collectAsState()
    val draftCorrections by ScanSession.userCorrections.collectAsState()
    val draftCorrectionsByEvidence = remember(draftCorrections) {
        draftCorrections
            .filter { it.evidenceId != null }
            .associateBy { it.evidenceId!! }
    }

    var selectedImage by remember { mutableStateOf<Uri?>(null) }
    var selectedVideo by remember { mutableStateOf<Uri?>(null) }
    var imageResult by remember { mutableStateOf<ReverseImageLookupResult?>(null) }
    var videoResult by remember { mutableStateOf<ReverseVideoLookupResult?>(null) }
    var analyzing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var correctionMessage by remember { mutableStateOf<String?>(null) }
    val requestGate = remember { ReverseMediaLookupRequestGate() }

    fun analyzeImage(uri: Uri) {
        val requestToken = requestGate.begin()
        selectedImage = uri
        selectedVideo = null
        imageResult = null
        videoResult = null
        error = null
        correctionMessage = null
        analyzing = true
        val bindingToken = ScanSession.currentInput.value?.let(MediaIntelligenceSession::bindTo)
        if (bindingToken == null) {
            MediaIntelligenceSession.clear()
            error = "Start an authorized identity scan before attaching media evidence."
            analyzing = false
            return
        }
        scope.launch {
            try {
                val result = ReverseImageLookupService(context).lookup(
                    uri,
                    deepResearch = ScanSession.deepResearchEnabled.value,
                    bindingToken = bindingToken
                )
                if (requestGate.isCurrent(requestToken)) imageResult = result
            } catch (throwable: Throwable) {
                if (requestGate.isCurrent(requestToken)) {
                    error = "Lookup failed: ${throwable.localizedMessage ?: throwable.javaClass.simpleName}"
                }
            } finally {
                if (requestGate.isCurrent(requestToken)) analyzing = false
            }
        }
    }

    fun analyzeVideo(uri: Uri) {
        val requestToken = requestGate.begin()
        selectedVideo = uri
        selectedImage = null
        imageResult = null
        videoResult = null
        error = null
        correctionMessage = null
        analyzing = true
        val bindingToken = ScanSession.currentInput.value?.let(MediaIntelligenceSession::bindTo)
        if (bindingToken == null) {
            MediaIntelligenceSession.clear()
            error = "Start an authorized identity scan before attaching media evidence."
            analyzing = false
            return
        }
        scope.launch {
            try {
                val result = ReverseVideoLookupService(context).lookup(
                    uri,
                    deepResearch = ScanSession.deepResearchEnabled.value,
                    bindingToken = bindingToken
                )
                if (requestGate.isCurrent(requestToken)) videoResult = result
            } catch (throwable: Throwable) {
                if (requestGate.isCurrent(requestToken)) {
                    error = "Video lookup failed: ${throwable.localizedMessage ?: throwable.javaClass.simpleName}"
                }
            } finally {
                if (requestGate.isCurrent(requestToken)) analyzing = false
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
                modifier = Modifier
                    .padding(top = 6.dp, bottom = 6.dp)
                    .semantics { heading() }
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
                RenderLookupResult(
                    result = it,
                    cardShape = cardShape,
                    onNavigateToBrowser = onNavigateToBrowser,
                    evidenceRecords = evidenceCollection.evidence,
                    draftCorrections = draftCorrectionsByEvidence,
                    draftCorrectionMessage = correctionMessage,
                    onDraftCorrection = { evidenceId, decision ->
                        val accepted = ScanSession.recordDraftCorrection(
                            UserCorrection(
                                evidenceId = evidenceId,
                                decision = decision,
                                createdAtUtc = Instant.now().toString()
                            )
                        )
                        correctionMessage = if (accepted) {
                            "Draft linked-profile decision applied locally. Use Actions → Save encrypted case to persist it."
                        } else {
                            "Draft correction limit reached; no change was applied."
                        }
                    }
                )
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
    showGps: Boolean = true,
    evidenceRecords: List<Evidence> = emptyList(),
    draftCorrections: Map<String, UserCorrection> = emptyMap(),
    draftCorrectionMessage: String? = null,
    onDraftCorrection: ((String, UserCorrectionDecision) -> Unit)? = null
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
                    match.clusterId?.let { clusterId ->
                        Text(
                            "Cluster ${clusterId.substringAfter(':').take(10)}",
                            color = NeuralTheme.TextMuted,
                            fontSize = 9.5.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(top = 3.dp)
                        )
                    }
                    val target = match.sourcePageUrl.takeIf { it.startsWith("http") } ?: match.imageUrl
                    Text(
                        "Open source page →",
                        color = NeuralTheme.Cyan,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .padding(top = 7.dp)
                            .clickable(role = Role.Button) { onNavigateToBrowser(target) }
                            .semantics { contentDescription = "Open image match source $target" }
                    )
                }
            }
        }
    }

    if (result.visualCandidates.isNotEmpty()) {
        Spacer(Modifier.height(20.dp))
        RenderVisualProvenance(
            result = result,
            cardShape = cardShape,
            onNavigateToBrowser = onNavigateToBrowser,
            evidenceRecords = evidenceRecords,
            draftCorrections = draftCorrections,
            draftCorrectionMessage = draftCorrectionMessage,
            onDraftCorrection = onDraftCorrection
        )
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
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .padding(top = 8.dp)
                        .clickable(role = Role.Button) { onNavigateToBrowser(url) }
                        .semantics { contentDescription = "Open resolved location in Maps $url" }
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
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .padding(top = 5.dp)
                                .clickable(role = Role.Button) { onNavigateToBrowser(evidence.url) }
                                .semantics { contentDescription = "Open public location evidence ${evidence.title}" }
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
            modifier = Modifier
                .heightIn(min = 48.dp)
                .padding(vertical = 4.dp)
                .clickable(role = Role.Button) { onNavigateToBrowser(url) }
                .semantics { contentDescription = "Open external visual index $name" }
        )
    }
}

@Composable
internal fun RenderVisualProvenance(
    result: ReverseImageLookupResult,
    cardShape: RoundedCornerShape,
    onNavigateToBrowser: (String) -> Unit,
    evidenceRecords: List<Evidence> = emptyList(),
    draftCorrections: Map<String, UserCorrection> = emptyMap(),
    draftCorrectionMessage: String? = null,
    onDraftCorrection: ((String, UserCorrectionDecision) -> Unit)? = null
) {
    var expanded by remember(result.visualCandidates) { mutableStateOf(false) }
    val candidates = result.visualCandidates
    val shown = if (expanded) candidates else candidates.take(PROVENANCE_PREVIEW_COUNT)
    val stateCounts = candidates.groupingBy { it.state }.eachCount()

    SectionHeader("Image candidate provenance")
    Text(
        text = buildString {
            append("${candidates.size} public candidate(s) recorded")
            append(" · ${stateCounts[ReverseImageLookupResult.ImageCandidateState.Matched] ?: 0} matched")
            append(" · ${stateCounts[ReverseImageLookupResult.ImageCandidateState.ComparedNoMatch] ?: 0} compared/no match")
            val unavailable = (stateCounts[ReverseImageLookupResult.ImageCandidateState.DownloadUnavailable] ?: 0) +
                (stateCounts[ReverseImageLookupResult.ImageCandidateState.DecodeFailed] ?: 0)
            if (unavailable > 0) append(" · $unavailable unavailable")
        },
        color = NeuralTheme.TextSecondary,
        fontSize = 11.5.sp,
        lineHeight = 16.sp,
        modifier = Modifier.padding(bottom = 8.dp)
    )
    Text(
        "Candidate provenance describes where public images came from and how whole-image comparison behaved. Hash similarity indicates duplicate/repost content, not a person's identity.",
        color = NeuralTheme.TextMuted,
        fontSize = 10.5.sp,
        lineHeight = 15.sp,
        modifier = Modifier.padding(bottom = 9.dp)
    )
    draftCorrectionMessage?.let { message ->
        InfoCard(message, NeuralTheme.Cobalt, cardShape)
        Spacer(Modifier.height(8.dp))
    }

    if (result.visualClusters.isNotEmpty()) {
        Text(
            "Duplicate/repost clusters",
            color = NeuralTheme.TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 5.dp)
        )
        result.visualClusters.forEach { cluster ->
            Text(
                text = "• ${cluster.type.displayLabel()} · ${cluster.memberCandidateIds.size} public candidates · ${cluster.id.substringAfter(':').take(10)}",
                color = if (cluster.type == ReverseImageLookupResult.ImageClusterType.ExactContent) NeuralTheme.Emerald else NeuralTheme.Cobalt,
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(bottom = 3.dp)
            )
        }
        Spacer(Modifier.height(7.dp))
    }

    shown.forEach { candidate ->
        Card(
            colors = CardDefaults.cardColors(containerColor = NeuralTheme.CardBackground.copy(alpha = 0.82f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 7.dp)
                .border(1.dp, candidate.state.accent().copy(alpha = 0.30f), cardShape),
            shape = cardShape
        ) {
            Column(Modifier.padding(13.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        candidate.title,
                        color = NeuralTheme.TextPrimary,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        candidate.state.displayLabel(),
                        color = candidate.state.accent(),
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                Text(
                    "${candidate.source} · query: ${candidate.acquisitionQuery}",
                    color = NeuralTheme.TextSecondary,
                    fontSize = 9.5.sp,
                    lineHeight = 14.sp,
                    modifier = Modifier.padding(top = 3.dp)
                )
                val technical = buildList {
                    if (candidate.width != null && candidate.height != null) add("${candidate.width}×${candidate.height}")
                    candidate.comparisonScore?.let { add("score ${(it * 100).toInt()}%") }
                    candidate.contentSha256?.let { add("sha256 ${it.take(10)}…") }
                    candidate.perceptualHashHex?.let { add("pHash ${it.take(10)}…") }
                    candidate.clusterId?.let { add("cluster ${it.substringAfter(':').take(8)}") }
                }
                if (technical.isNotEmpty()) {
                    Text(
                        technical.joinToString(" · "),
                        color = NeuralTheme.TextMuted,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 13.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                if (candidate.sourcePageUrl.startsWith("http")) {
                    Text(
                        "Open candidate source →",
                        color = NeuralTheme.Cyan,
                        fontSize = 10.5.sp,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .padding(top = 5.dp)
                            .clickable(role = Role.Button) {
                                onNavigateToBrowser(candidate.sourcePageUrl)
                            }
                            .semantics {
                                contentDescription = "Open public candidate source ${candidate.title}"
                            }
                    )
                }
                if (onDraftCorrection != null) {
                    val evidenceId = candidate.persistedLinkedProfileEvidenceId(evidenceRecords)
                    val currentCorrection = evidenceId?.let(draftCorrections::get)?.decision
                    if (evidenceId != null) {
                        Text(
                            "This control applies only to the exact linked profile observation; it does not establish image ownership. Raw media and profile evidence remain retained until encrypted case save.",
                            color = NeuralTheme.TextMuted,
                            fontSize = 9.5.sp,
                            lineHeight = 13.sp,
                            modifier = Modifier.padding(top = 7.dp)
                        )
                        MediaDraftCorrectionRow(
                            current = currentCorrection,
                            onDecision = { decision -> onDraftCorrection(evidenceId, decision) }
                        )
                    } else {
                        Text(
                            "Correction unavailable: no unique persisted profile evidence record backs this account linkage.",
                            color = NeuralTheme.TextMuted,
                            fontSize = 9.5.sp,
                            lineHeight = 13.sp,
                            modifier = Modifier.padding(top = 7.dp)
                        )
                    }
                }
            }
        }
    }

    if (candidates.size > PROVENANCE_PREVIEW_COUNT) {
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.semantics {
                stateDescription = if (expanded) "Expanded" else "Collapsed"
            }
        ) {
            Text(
                if (expanded) "Show fewer candidates" else "Inspect all ${candidates.size} candidates",
                color = NeuralTheme.Cyan
            )
        }
    }
}

@Composable
private fun MediaDraftCorrectionRow(
    current: UserCorrectionDecision?,
    onDecision: (UserCorrectionDecision) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(
            "Draft linked-profile decision · not saved",
            color = NeuralTheme.TextSecondary,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.SemiBold
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            MediaDraftCorrectionButton(
                label = "Confirm",
                decision = UserCorrectionDecision.ThisIsMe,
                selected = current == UserCorrectionDecision.ThisIsMe,
                modifier = Modifier.weight(1f),
                onClick = onDecision
            )
            MediaDraftCorrectionButton(
                label = "Reject",
                decision = UserCorrectionDecision.ThisIsNotMe,
                selected = current == UserCorrectionDecision.ThisIsNotMe,
                modifier = Modifier.weight(1f),
                onClick = onDecision
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            MediaDraftCorrectionButton(
                label = "Unsure",
                decision = UserCorrectionDecision.Unsure,
                selected = current == UserCorrectionDecision.Unsure,
                modifier = Modifier.weight(1f),
                onClick = onDecision
            )
            MediaDraftCorrectionButton(
                label = "Ignore",
                decision = UserCorrectionDecision.IgnoreEvidence,
                selected = current == UserCorrectionDecision.IgnoreEvidence,
                modifier = Modifier.weight(1f),
                onClick = onDecision
            )
        }
    }
}

@Composable
private fun MediaDraftCorrectionButton(
    label: String,
    decision: UserCorrectionDecision,
    selected: Boolean,
    modifier: Modifier,
    onClick: (UserCorrectionDecision) -> Unit
) {
    OutlinedButton(
        onClick = { onClick(decision) },
        modifier = modifier
            .heightIn(min = 48.dp)
            .semantics {
                this.selected = selected
                contentDescription = "$label linked profile evidence correction"
                stateDescription = if (selected) "Selected" else "Not selected"
            }
    ) {
        Text(label, fontSize = 10.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
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
        val selectedVideoName = selectedUri?.path?.substringAfterLast('/')
        val pickerDescription = selectedVideoName?.let {
            "Selected video $it. Double tap to choose a different video."
        } ?: "Select a video for analysis."
        Card(
            colors = CardDefaults.cardColors(containerColor = NeuralTheme.CardBackground),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = pickerDescription },
            shape = io.dossier.app.ui.theme.DossierCardShape
        ) {
            Text(
                selectedVideoName ?: "Select Video",
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

private fun ReverseImageLookupResult.ImageCandidateState.displayLabel(): String = when (this) {
    ReverseImageLookupResult.ImageCandidateState.Indexed -> "Indexed"
    ReverseImageLookupResult.ImageCandidateState.DownloadUnavailable -> "Unavailable"
    ReverseImageLookupResult.ImageCandidateState.DecodeFailed -> "Decode failed"
    ReverseImageLookupResult.ImageCandidateState.ComparedNoMatch -> "Compared"
    ReverseImageLookupResult.ImageCandidateState.Matched -> "Matched"
}

@Composable
private fun ReverseImageLookupResult.ImageCandidateState.accent(): Color = when (this) {
    ReverseImageLookupResult.ImageCandidateState.Matched -> NeuralTheme.Emerald
    ReverseImageLookupResult.ImageCandidateState.ComparedNoMatch -> NeuralTheme.Cobalt
    ReverseImageLookupResult.ImageCandidateState.DownloadUnavailable,
    ReverseImageLookupResult.ImageCandidateState.DecodeFailed -> NeuralTheme.Amber
    ReverseImageLookupResult.ImageCandidateState.Indexed -> NeuralTheme.TextMuted
}

private fun ReverseImageLookupResult.ImageClusterType.displayLabel(): String = when (this) {
    ReverseImageLookupResult.ImageClusterType.ExactContent -> "Exact-content cluster"
    ReverseImageLookupResult.ImageClusterType.PerceptualNearDuplicate -> "Perceptual repost cluster"
}

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
        dotLevel = io.dossier.app.ui.components.HudLevel.INFO,
        modifier = Modifier.semantics { heading() }
    )
}

private const val PROVENANCE_PREVIEW_COUNT = 6
