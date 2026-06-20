package io.dossier.app.ui.screens

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.dossier.app.data.platform.PLATFORMS
import io.dossier.app.domain.model.FaceConsistencyMatch
import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.FindingType
import io.dossier.app.domain.model.Platform
import io.dossier.app.domain.model.ProfileScanResult
import io.dossier.app.domain.model.RiskLevel
import io.dossier.app.domain.scanner.ScanSession
import io.dossier.app.export.ReportExporter
import io.dossier.app.ui.components.AnimatedObsidianBackground
import io.dossier.app.ui.components.GeminiSpark
import io.dossier.app.ui.components.SquigglyProgressIndicator
import io.dossier.app.ui.theme.NeuralTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

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
    val aiSummary by ScanSession.aiSummary.collectAsState()
    val input by ScanSession.currentInput.collectAsState()
    var selectedTab by remember { mutableStateOf(0) }

    val exporter = remember { ReportExporter(context) }

    val riskColor = when (riskLevel) {
        RiskLevel.Low -> NeuralTheme.Emerald
        RiskLevel.Medium -> NeuralTheme.Amber
        RiskLevel.High -> NeuralTheme.Amber
        RiskLevel.Critical -> NeuralTheme.Crimson
    }

    val cardShape = io.dossier.app.ui.theme.DossierCardShape

    Box(modifier = Modifier.fillMaxSize()) {
        // Shifting mesh gradient + floating diamond particles background
        AnimatedObsidianBackground(showGrid = true)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // ---- Classified dossier header (movie-style intelligence file) ----
            val subjectName = input?.fullName?.trim()?.ifBlank { "UNKNOWN SUBJECT" } ?: "UNKNOWN SUBJECT"
            val fileNumber = "DS-${java.time.LocalDate.now()}-${subjectName.replace(" ", "").take(6).uppercase()}"
            val prepDate = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

            // File number + classification line
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "FILE NO. $fileNumber",
                    color = NeuralTheme.TextMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
                // Classification stamp
                Text(
                    text = "CONFIDENTIAL",
                    color = NeuralTheme.Crimson,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier
                        .border(1.dp, NeuralTheme.Crimson.copy(alpha = 0.5f), RoundedCornerShape(2.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // DOSSIER title
            Text(
                text = "DOSSIER",
                color = NeuralTheme.TextPrimary,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )

            // SUBJECT line — the name, prominent
            Text(
                text = "SUBJECT: $subjectName",
                color = NeuralTheme.Cobalt,
                fontFamily = FontFamily.Monospace,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 4.dp)
            )

            Text(
                text = "Prepared $prepDate  ·  Self-audit  ·  Local processing only",
                color = NeuralTheme.TextSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
            )

            HorizontalDivider(color = NeuralTheme.BorderColor, thickness = 1.dp)
            Spacer(modifier = Modifier.height(20.dp))

            // Overall exposure risk index card — calm static border
            Card(
                colors = CardDefaults.cardColors(containerColor = NeuralTheme.CardBackground),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, riskColor.copy(alpha = 0.5f), cardShape),
                shape = cardShape
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "THREAT ASSESSMENT",
                            color = NeuralTheme.TextSecondary,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            fontSize = 11.sp,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = riskLevel.name,
                            color = riskColor,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .background(NeuralTheme.SurfaceDark, RoundedCornerShape(8.dp))
                            .border(1.dp, NeuralTheme.BorderColor, RoundedCornerShape(8.dp))
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "${findings.size} FINDINGS",
                            color = NeuralTheme.Cyan,
                            
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (!aiSummary.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(16.dp))
                ReportSectionHeader("AI Analysis")
                Card(
                    colors = CardDefaults.cardColors(containerColor = NeuralTheme.CardBackground.copy(alpha = 0.85f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, NeuralTheme.BorderColor, cardShape),
                    shape = cardShape
                ) {
                    Text(
                        text = aiSummary!!,
                        color = NeuralTheme.TextPrimary,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        modifier = Modifier.padding(18.dp)
                    )
                }
            }

            // Glassmorphism tab selector
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
                    .background(NeuralTheme.CardBackground.copy(alpha = 0.7f), io.dossier.app.ui.theme.DossierCardShape)
                    .border(1.dp, NeuralTheme.BorderColor, io.dossier.app.ui.theme.DossierCardShape)
                    .padding(4.dp)
            ) {
                TabButton(
                    text = "IDENTITY DOSSIER",
                    isSelected = selectedTab == 0,
                    modifier = Modifier.weight(1f),
                    onClick = { selectedTab = 0 }
                )
                TabButton(
                    text = "EXPOSURE LOGS",
                    isSelected = selectedTab == 1,
                    modifier = Modifier.weight(1f),
                    onClick = { selectedTab = 1 }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (selectedTab == 0) {
                // IDENTITY DOSSIER VIEW
                val profileResults by ScanSession.profileScanResults.collectAsState()

                if (input != null) {
                    val selfieBitmap = rememberUriImageBitmap(input!!.selfieUri?.let { Uri.parse(it) })

                    // Overall scan confidence (average of found profiles with high confidence)
                    val highConfFindings = findings.filter { it.type == FindingType.PlausibleProfileMatch && it.confidence > 0.5f }
                    val avgConfidence = if (highConfFindings.isNotEmpty())
                        highConfFindings.map { it.confidence }.average().toFloat()
                    else 0f
                    val avgConfPct = (avgConfidence * 100).toInt()
                    val avgConfColor = when {
                        avgConfPct >= 80 -> NeuralTheme.Crimson
                        avgConfPct >= 55 -> NeuralTheme.Amber
                        else -> NeuralTheme.Emerald
                    }

                    // Detect name-only mode
                    val isNameOnlyMode = input!!.primaryUsername == null && input!!.usernames.isEmpty()

                    if (isNameOnlyMode) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(NeuralTheme.Cyan.copy(alpha = 0.08f), cardShape)
                                .border(1.dp, NeuralTheme.Cyan.copy(alpha = 0.25f), cardShape)
                                .padding(14.dp)
                        ) {
                            Text(
                                text = "ℹ  Name-Only Mode — Usernames derived from name details. Scores reflect baseline search settings. Provide explicit username signals to increase discovery precision.",
                                color = NeuralTheme.Cyan,
                                
                                fontSize = 12.sp,
                                lineHeight = 18.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    // Editorial User Avatar Card
                    Card(
                        colors = CardDefaults.cardColors(containerColor = NeuralTheme.CardBackground.copy(alpha = 0.85f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, NeuralTheme.BorderColor, cardShape),
                        shape = cardShape
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                // Avatar with a morphing squiggly aura border!
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.size(88.dp)
                                ) {
                                    io.dossier.app.ui.components.SquigglyProgressIndicator(
                                        size = 84.dp,
                                        brush = NeuralTheme.GeminiGradient,
                                        strokeWidth = 2.dp,
                                        waveCount = 4,
                                        amplitudePercent = 0.08f,
                                        speedMs = 3000
                                    )
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(70.dp)
                                            .background(NeuralTheme.SurfaceDark, CircleShape)
                                    ) {
                                        if (selfieBitmap != null) {
                                            Image(
                                                bitmap = selfieBitmap,
                                                contentDescription = "User Selfie",
                                                modifier = Modifier
                                                    .size(66.dp)
                                                    .clip(CircleShape)
                                            )
                                        } else {
                                            Text(
                                                text = input!!.fullName.take(2).uppercase(),
                                                color = NeuralTheme.Cyan,
                                                
                                                fontWeight = FontWeight.ExtraBold,
                                                fontSize = 20.sp
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.width(18.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = input!!.fullName,
                                        color = NeuralTheme.TextPrimary,
                                        
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 20.sp
                                    )
                                    Text(
                                        text = if (input!!.primaryUsername != null) "@${input!!.primaryUsername}" else "derived mode active",
                                        color = NeuralTheme.TextSecondary,
                                        
                                        fontSize = 11.5.sp,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                    if (input!!.aliases.isNotEmpty()) {
                                        Text(
                                            text = "AKA: ${input!!.aliases.joinToString(", ")}",
                                            color = NeuralTheme.TextSecondary,
                                            
                                            fontSize = 11.sp,
                                            modifier = Modifier.padding(top = 2.dp)
                                        )
                                    }
                                }

                                // Avg Confidence Gauge
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.padding(start = 8.dp)
                                ) {
                                    Text(
                                        text = "$avgConfPct%",
                                        color = avgConfColor,
                                        
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 22.sp
                                    )
                                    Text(
                                        text = "CONF",
                                        color = NeuralTheme.TextSecondary,
                                        
                                        fontSize = 9.sp,
                                        letterSpacing = 1.sp
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Compiled PII Grid Header
                    ReportSectionHeader("Compiled Identity Profile")

                    val locations = findings.filter { it.type == FindingType.Location && it.confidence >= 0.55f }.map { it.value }.distinct()
                    val organizations = findings.filter { it.type == FindingType.Organization && it.confidence >= 0.55f }.map { it.value }.distinct()
                    val emails = findings.filter { it.type == FindingType.Email && it.confidence >= 0.55f }.map { it.value }.distinct()
                    val phones = findings.filter { it.type == FindingType.Phone && it.confidence >= 0.55f }.map { it.value }.distinct()
                    
                    Card(
                        colors = CardDefaults.cardColors(containerColor = NeuralTheme.CardBackground.copy(alpha = 0.85f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, NeuralTheme.BorderColor, cardShape),
                        shape = cardShape
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            DossierInfoRow(label = "FULL NAME", value = input!!.fullName)
                            HorizontalDivider(color = NeuralTheme.BorderColor, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 12.dp))
                            
                            DossierInfoRow(
                                label = "DISCOVERED LOCATIONS", 
                                value = locations.ifEmpty { listOf("No location leaks found") }.joinToString(", ")
                            )
                            HorizontalDivider(color = NeuralTheme.BorderColor, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 12.dp))

                            DossierInfoRow(
                                label = "EMPLOYERS / ORGS", 
                                value = organizations.ifEmpty { listOf("No organization associations leaked") }.joinToString(", ")
                            )
                            HorizontalDivider(color = NeuralTheme.BorderColor, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 12.dp))

                            DossierInfoRow(
                                label = "EMAIL ADDRESSES", 
                                value = emails.ifEmpty { listOf("No email exposures leaked") }.joinToString(", ")
                            )
                            HorizontalDivider(color = NeuralTheme.BorderColor, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 12.dp))

                            DossierInfoRow(
                                label = "PHONE NUMBERS", 
                                value = phones.ifEmpty { listOf("No public phone listings") }.joinToString(", ")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Discovered Profile Directory Header
                    ReportSectionHeader("Subject Profile Candidates")
                    Text(
                        text = "Rows marked verified were directly fetched or rendered. Rows marked review came from public search indexes and need manual confirmation.",
                        color = NeuralTheme.TextSecondary,
                        
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
                    )

                    val profiles = findings.filter { it.type == FindingType.PlausibleProfileMatch }.map { it.value }.distinct()
                    DiscoveredProfilesTable(
                        profiles = profiles,
                        faceMatches = faceMatches,
                        findings = findings,
                        profileResults = profileResults,
                        onNavigateToBrowser = onNavigateToBrowser
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // Visual Similarity — honest: no embedding model in this build.
                    ReportSectionHeader("Visual Intelligence")
                    val imageEvidenceResults = profileResults
                        .filter { result ->
                            result.exists &&
                                result.profileImageUrl != null &&
                                result.findings.any { it.type == FindingType.PublicImageEvidence }
                        }

                    Card(
                        colors = CardDefaults.cardColors(containerColor = NeuralTheme.CardBackground),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, NeuralTheme.BorderColor, cardShape),
                        shape = cardShape
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            if (imageEvidenceResults.isNotEmpty()) {
                                Text(
                                    text = "${imageEvidenceResults.size} public image result(s)",
                                    color = NeuralTheme.TextPrimary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                imageEvidenceResults.take(8).forEach { result ->
                                    PublicImageEvidenceRow(
                                        result = result,
                                        onNavigateToBrowser = onNavigateToBrowser
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                            } else {
                                Text(
                                    text = "No public image results found",
                                    color = NeuralTheme.TextPrimary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                            }
                            Text(
                                text = "Image search uses public image-index results from identity terms. It does not upload your selfie and does not perform face matching. Similarity scoring still requires a real face embedding model.",
                                color = NeuralTheme.TextSecondary,
                                fontSize = 12.5.sp,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            } else {
                // RAW EXPOSURE LOGS VIEW
                Spacer(modifier = Modifier.height(16.dp))

                // Profile Image Consistency Section
                if (faceMatches.isNotEmpty()) {
                    ReportSectionHeader("Visual Consistency Log")

                    faceMatches.forEach { match ->
                        val matchLabel = when {
                            match.similarityScore > 0.8f -> "Profile image appears visually similar"
                            match.similarityScore > 0.6f -> "Potential reuse — verify account"
                            else -> "No match found"
                        }
                        
                        val consistencyColor = when {
                            match.similarityScore > 0.8f -> NeuralTheme.Crimson
                            match.similarityScore > 0.6f -> NeuralTheme.Amber
                            else -> NeuralTheme.Emerald
                        }

                        Card(
                            colors = CardDefaults.cardColors(containerColor = NeuralTheme.CardBackground.copy(alpha = 0.85f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, NeuralTheme.BorderColor, cardShape)
                                .padding(bottom = 12.dp),
                            shape = cardShape
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = match.profileUrl,
                                    color = NeuralTheme.Cyan,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    textDecoration = TextDecoration.Underline,
                                    modifier = Modifier.clickable { onNavigateToBrowser(match.profileUrl) }
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "Similarity Index: ",
                                        color = NeuralTheme.TextSecondary,
                                        
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        text = "%.2f".format(match.similarityScore),
                                        color = consistencyColor,
                                        
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                
                                Spacer(modifier = Modifier.height(4.dp))
                                
                                Text(
                                    text = matchLabel,
                                    color = consistencyColor,
                                    
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Exposure Findings Section
                ReportSectionHeader("Exposure Log")

                if (findings.isEmpty()) {
                    Text(
                        text = "No exposure findings detected. Good privacy profile.",
                        color = NeuralTheme.TextSecondary,
                        
                        fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp)
                    )
                } else {
                    findings.forEach { finding ->
                        FindingItemCard(finding, onNavigateToBrowser)
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Remediation Tips Section
            if (remediationTips.isNotEmpty()) {
                ReportSectionHeader("Recommended Actions")
                Card(
                    colors = CardDefaults.cardColors(containerColor = NeuralTheme.CardBackground.copy(alpha = 0.85f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, NeuralTheme.BorderColor, cardShape),
                    shape = cardShape
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        remediationTips.forEach { tip ->
                            Row(
                                modifier = Modifier.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Box(modifier = Modifier.padding(top = 2.dp, end = 10.dp)) {
                                    GeminiSpark(size = 11.dp, glowColor = NeuralTheme.Cyan)
                                }
                                Text(
                                    text = tip,
                                    color = NeuralTheme.TextPrimary,
                                    
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
            }

            // Action Buttons
            // Deep Research — re-scan with personal-website link-following + AI
            // handle extraction to surface non-obvious handles (e.g. ones you only
            // mention on a linked personal site). Shown when results feel thin.
            val profileResults by ScanSession.profileScanResults.collectAsState()
            val confirmedCount = profileResults.count { it.exists && it.verified }
            if (confirmedCount <= 5) {
                OutlinedButton(
                    onClick = onDeepResearch,
                    border = BorderStroke(1.2.dp, NeuralTheme.Cobalt.copy(alpha = 0.7f)),
                    shape = io.dossier.app.ui.theme.DossierButtonShape,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = NeuralTheme.Cobalt
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .padding(bottom = 12.dp)
                ) {
                    Text(
                        text = "⚡ RUN DEEP RESEARCH",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        letterSpacing = 1.sp
                    )
                }
            }

            Button(
                onClick = { exporter.shareReport(findings, input?.fullName?.trim()?.ifBlank { "UNKNOWN SUBJECT" } ?: "UNKNOWN SUBJECT") },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .background(NeuralTheme.GeminiGradient, io.dossier.app.ui.theme.DossierButtonShape)
                    .border(1.dp, Color.White.copy(alpha = 0.2f), io.dossier.app.ui.theme.DossierButtonShape),
                shape = io.dossier.app.ui.theme.DossierButtonShape,
                contentPadding = PaddingValues()
            ) {
                Text(
                    text = "TRANSMIT DOSSIER",
                    
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 14.sp,
                    letterSpacing = 1.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = {
                    ScanSession.purgeSession(context)
                    onReset()
                },
                border = BorderStroke(1.2.dp, NeuralTheme.Crimson.copy(alpha = 0.8f)),
                shape = io.dossier.app.ui.theme.DossierButtonShape,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = NeuralTheme.Crimson
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(
                    text = "PURGE FILE",
                    
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    letterSpacing = 1.sp
                )
            }
            
            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

@Composable
fun FindingItemCard(finding: Finding, onNavigateToBrowser: (String) -> Unit) {
    val riskColor = when (finding.risk) {
        RiskLevel.Low -> NeuralTheme.Emerald
        RiskLevel.Medium -> NeuralTheme.Amber
        RiskLevel.High -> NeuralTheme.Amber
        RiskLevel.Critical -> NeuralTheme.Crimson
    }
    
    val cardShape = RoundedCornerShape(topStart = 20.dp, bottomEnd = 20.dp, topEnd = 4.dp, bottomStart = 4.dp)

    Card(
        colors = CardDefaults.cardColors(containerColor = NeuralTheme.CardBackground.copy(alpha = 0.85f)),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, NeuralTheme.BorderColor, cardShape),
        shape = cardShape
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = finding.type.name.replace("([A-Z])".toRegex(), " $1").trim().uppercase(),
                    color = NeuralTheme.Cyan,
                    
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    modifier = Modifier.weight(1f)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Confidence badge
                    val confPct = (finding.confidence * 100).toInt()
                    val confColor = when {
                        confPct >= 85 -> NeuralTheme.Crimson
                        confPct >= 60 -> NeuralTheme.Amber
                        else -> NeuralTheme.Emerald
                    }
                    Box(
                        modifier = Modifier
                            .background(confColor.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "$confPct% CONF",
                            color = confColor,
                            
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Box(
                        modifier = Modifier
                            .background(riskColor.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = finding.risk.name,
                            color = riskColor,
                            
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(10.dp))
            
            Text(
                text = finding.value,
                color = NeuralTheme.TextPrimary,
                
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )

            if (!finding.sourceUrl.isNullOrBlank()) {
                val isClickableUrl = finding.sourceUrl.startsWith("http://", ignoreCase = true) || 
                                     finding.sourceUrl.startsWith("https://", ignoreCase = true)
                val modifier = if (isClickableUrl) {
                    Modifier
                        .clickable { onNavigateToBrowser(finding.sourceUrl) }
                        .padding(top = 4.dp)
                } else {
                    Modifier.padding(top = 4.dp)
                }
                Text(
                    text = "Source: ${finding.sourceUrl}",
                    color = if (isClickableUrl) NeuralTheme.Cyan else NeuralTheme.TextSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    textDecoration = if (isClickableUrl) TextDecoration.Underline else null,
                    modifier = modifier
                )
            }

            if (!finding.evidenceSnippet.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .background(NeuralTheme.SurfaceDark, RoundedCornerShape(8.dp))
                        .border(1.dp, NeuralTheme.BorderColor, RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = "Evidence: \"${finding.evidenceSnippet}\"",
                        color = NeuralTheme.TextSecondary,
                        
                        fontSize = 12.sp
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = NeuralTheme.BorderColor, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))
            
            Text(
                text = "Remediation: ${finding.remediation}",
                color = NeuralTheme.TextSecondary,
                
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        }
    }
}

@Composable
fun PublicImageEvidenceRow(
    result: ProfileScanResult,
    onNavigateToBrowser: (String) -> Unit
) {
    val thumbnail = rememberRemoteImageBitmap(result.profileImageUrl)
    val confPct = (result.candidate.confidence * 100).toInt()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(NeuralTheme.SurfaceDark, RoundedCornerShape(8.dp))
            .border(1.dp, NeuralTheme.BorderColor, RoundedCornerShape(8.dp))
            .clickable { onNavigateToBrowser(result.candidate.url) }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(58.dp)
                .background(NeuralTheme.CardBackground, RoundedCornerShape(8.dp))
                .border(1.dp, NeuralTheme.BorderColor, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail,
                    contentDescription = "Public image result",
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(8.dp))
                )
            } else {
                Text(
                    text = "IMG",
                    color = NeuralTheme.TextSecondary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = result.displayName ?: result.candidate.url,
                color = NeuralTheme.TextPrimary,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2
            )
            Text(
                text = result.candidate.url.replace("https://", "").replace("http://", ""),
                color = NeuralTheme.Cyan,
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
                textDecoration = TextDecoration.Underline,
                maxLines = 1,
                modifier = Modifier.padding(top = 3.dp)
            )
            result.provenance?.let {
                Text(
                    text = it,
                    color = NeuralTheme.TextSecondary,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = "$confPct%",
            color = when {
                confPct >= 70 -> NeuralTheme.Amber
                confPct >= 45 -> NeuralTheme.Emerald
                else -> NeuralTheme.TextSecondary
            },
            fontSize = 12.sp,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

@Composable
fun TabButton(text: String, isSelected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bg = if (isSelected) NeuralTheme.GeminiGradient else Brush.linearGradient(colors = listOf(Color.Transparent, Color.Transparent))
    
    Box(
        modifier = modifier
            .height(40.dp)
            .background(bg, io.dossier.app.ui.theme.DossierButtonShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (isSelected) Color.White else NeuralTheme.TextSecondary,
            
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            letterSpacing = 1.sp
        )
    }
}

@Composable
fun DossierInfoRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = NeuralTheme.Cyan,
            
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            letterSpacing = 0.5.sp
        )
        Text(
            text = value,
            color = NeuralTheme.TextPrimary,
            
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/** Shared section header for the report — delegates to the HUD label kit. */
@Composable
fun ReportSectionHeader(text: String) {
    // Intelligence-brief section label — monospace, underlined, dossier-style.
    Column(modifier = Modifier.padding(start = 2.dp, bottom = 12.dp)) {
        Text(
            text = text.uppercase(),
            color = NeuralTheme.TextPrimary,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp
        )
        HorizontalDivider(
            color = NeuralTheme.Cobalt.copy(alpha = 0.4f),
            thickness = 1.dp,
            modifier = Modifier
                .padding(top = 4.dp)
                .width(40.dp)
        )
    }
}

@Composable
fun rememberUriImageBitmap(uri: Uri?): ImageBitmap? {
    if (uri == null) return null
    val context = LocalContext.current
    return remember(uri) {
        try {
            val bitmap = if (uri.scheme == "file") {
                BitmapFactory.decodeFile(uri.path)
            } else {
                val inputStream = context.contentResolver.openInputStream(uri)
                val bmp = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()
                bmp
            }
            bitmap?.asImageBitmap()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

@Composable
fun rememberRemoteImageBitmap(url: String?): ImageBitmap? {
    var image by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    val client = remember {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    LaunchedEffect(url) {
        image = null
        val imageUrl = url?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
            ?: return@LaunchedEffect

        image = withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(imageUrl)
                    .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    response.body?.byteStream()?.use { stream ->
                        BitmapFactory.decodeStream(stream)?.asImageBitmap()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    return image
}

@Composable
fun rememberPlatformAvatar(context: Context, platform: Platform): ImageBitmap? {
    return remember(platform) {
        try {
            val file = File(context.cacheDir, "avatar_${platform.name}.jpg")
            if (file.exists()) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                bitmap?.asImageBitmap()
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

@Composable
fun DiscoveredProfilesTable(
    profiles: List<String>,
    faceMatches: List<FaceConsistencyMatch>,
    findings: List<Finding>,
    profileResults: List<ProfileScanResult> = emptyList(),
    onNavigateToBrowser: (String) -> Unit
) {
    val cardShape = io.dossier.app.ui.theme.DossierCardShape

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, NeuralTheme.BorderColor, cardShape)
            .background(NeuralTheme.SurfaceDark, cardShape)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(NeuralTheme.CardBackground.copy(alpha = 0.9f), RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                .border(1.dp, NeuralTheme.BorderColor, RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.width(34.dp))
            Text(
                text = "PLATFORM",
                color = NeuralTheme.Cyan,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                
                modifier = Modifier.width(80.dp)
            )
            Text(
                text = "STATUS",
                color = NeuralTheme.Cyan,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                
                modifier = Modifier.width(76.dp)
            )
            Text(
                text = "CONF %",
                color = NeuralTheme.Cyan,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                
                modifier = Modifier.width(52.dp)
            )
            Text(
                text = "PROFILE",
                color = NeuralTheme.Cyan,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                
                modifier = Modifier.weight(1f)
            )
        }

        if (profiles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No public profile candidates discovered",
                    color = NeuralTheme.TextSecondary,
                    fontSize = 12.sp )
            }
        } else {
            profiles.forEachIndexed { idx, url ->
                val matchedPlatform = PLATFORMS.firstOrNull { template ->
                    val domain = template.urlPattern
                        .replace("https://", "")
                        .replace("www.", "")
                        .split("/").firstOrNull() ?: ""
                    domain.isNotBlank() && url.contains(domain, ignoreCase = true)
                }
                val platformName = matchedPlatform?.platform?.name ?: "Web"

                val profileResult = profileResults.firstOrNull { r -> r.candidate.url == url && r.exists }
                    ?: profileResults.firstOrNull { r -> r.candidate.url == url }
                val isVerified = profileResult?.verified == true
                val isPublicSearchCandidate = profileResult?.provenance?.startsWith("public search") == true
                val avatarBitmap = rememberRemoteImageBitmap(profileResult?.profileImageUrl)

                val hasFaceMatch = faceMatches.any { it.profileUrl == url }
                val hasPiiLeak = findings.any { it.sourceUrl == url && (it.type == FindingType.Email || it.type == FindingType.Phone) }

                val confidencePct = profileResult?.candidate?.confidence?.let { c -> (c * 100).toInt() }
                    ?: findings.firstOrNull { f -> f.sourceUrl == url && f.type == FindingType.PlausibleProfileMatch }
                        ?.confidence?.let { c -> (c * 100).toInt() }
                    ?: 0

                val statusText = when {
                    hasFaceMatch && hasPiiLeak -> "MATCH+LEAK"
                    hasFaceMatch -> "FACE MATCH"
                    hasPiiLeak -> "PII LEAK"
                    isVerified -> "VERIFIED"
                    isPublicSearchCandidate -> "REVIEW"
                    else -> "FOUND"
                }
                val statusLevel = when {
                    hasFaceMatch && hasPiiLeak -> io.dossier.app.ui.components.HudLevel.CRIT
                    hasFaceMatch -> io.dossier.app.ui.components.HudLevel.WARN
                    hasPiiLeak -> io.dossier.app.ui.components.HudLevel.WARN
                    isPublicSearchCandidate -> io.dossier.app.ui.components.HudLevel.WARN
                    else -> io.dossier.app.ui.components.HudLevel.OK
                }
                val confColor = when {
                    confidencePct >= 85 -> NeuralTheme.Crimson
                    confidencePct >= 65 -> NeuralTheme.Amber
                    confidencePct >= 40 -> NeuralTheme.Emerald
                    else -> NeuralTheme.TextSecondary
                }
                val verificationNote = profileResult?.verificationStatus

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToBrowser(url) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.width(34.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (avatarBitmap != null) {
                            Image(
                                bitmap = avatarBitmap,
                                contentDescription = "Public profile image",
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .border(1.dp, NeuralTheme.BorderColor, CircleShape)
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .background(NeuralTheme.CardBackground, CircleShape)
                                    .border(1.dp, NeuralTheme.BorderColor, CircleShape)
                            )
                        }
                    }

                    Text(
                        text = platformName,
                        color = NeuralTheme.TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        
                        modifier = Modifier.width(80.dp)
                    )

                    Box(
                        modifier = Modifier
                            .width(76.dp)
                            .padding(end = 6.dp)
                    ) {
                        io.dossier.app.ui.components.HudStatusPill(
                            text = statusText,
                            level = statusLevel
                        )
                    }

                    Text(
                        text = "$confidencePct%",
                        color = confColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold,
                        
                        modifier = Modifier.width(52.dp)
                    )

                    // Profile URL + in-browser verification badge
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = url.replace("https://", ""),
                            color = NeuralTheme.Cyan,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            textDecoration = TextDecoration.Underline
                        )
                        if (isVerified) {
                            Text(
                                text = "✓ " + (verificationNote ?: "Verified in-browser"),
                                color = NeuralTheme.Emerald,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        } else if (!verificationNote.isNullOrBlank()) {
                            Text(
                                text = verificationNote,
                                color = NeuralTheme.TextSecondary,
                                fontSize = 9.sp,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        // Pivot-discovery provenance: shows how a non-obvious handle
                        // (e.g. one not derived from the name) was found.
                        profileResult?.provenance?.let { prov ->
                            Text(
                                text = "↳ $prov",
                                color = NeuralTheme.Cobalt.copy(alpha = 0.8f),
                                fontSize = 8.5.sp,
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                modifier = Modifier.padding(top = 1.dp)
                            )
                        }
                    }
                }

                if (idx < profiles.lastIndex) {
                    HorizontalDivider(color = NeuralTheme.BorderColor, thickness = 0.5.dp)
                }
            }
        }
    }
}
