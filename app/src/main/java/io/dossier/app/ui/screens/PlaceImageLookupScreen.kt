package io.dossier.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.dossier.app.data.ai.HybridAiClient
import io.dossier.app.data.place.ExifParser
import io.dossier.app.data.place.FaceAnalyzer
import io.dossier.app.domain.place.PlaceImageScanner
import io.dossier.app.domain.model.PlaceScanResult
import io.dossier.app.domain.scanner.ScanSession
import io.dossier.app.ui.theme.NeuralTheme
import io.dossier.app.ui.components.*
import kotlinx.coroutines.launch

@Composable
fun PlaceImageLookupScreen(onNext: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    var selectedUri by remember { mutableStateOf<Uri?>(ScanSession.getPlaceImage()) }
    var scanResult by remember { mutableStateOf<PlaceScanResult?>(null) }
    var isAnalyzing by remember { mutableStateOf(false) }

    val placeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedUri = uri
            ScanSession.setPlaceImage(uri)
        }
    }

    LaunchedEffect(selectedUri) {
        val uri = selectedUri
        if (uri != null) {
            isAnalyzing = true
            coroutineScope.launch {
                try {
                    val faceAnalyzer = FaceAnalyzer(context)
                    val exifParser = ExifParser(context)
                    val hybridAiClient = HybridAiClient(context)
                    val scanner = PlaceImageScanner(context, faceAnalyzer, exifParser, hybridAiClient)
                    val result = scanner.scanPlaceImage(uri)
                    scanResult = result
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    isAnalyzing = false
                }
            }
        } else {
            scanResult = null
        }
    }

    val buttonGradient = Brush.horizontalGradient(
        colors = listOf(NeuralTheme.Cobalt, NeuralTheme.Violet)
    )

    val cardShape = io.dossier.app.ui.theme.DossierCardShape

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedObsidianBackground(showGrid = true)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 12.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = "EXIF ANALYSIS & SAFETY GATING",
                color = NeuralTheme.Cyan,
                
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            Text(
                text = "Place Image Lookup",
                color = NeuralTheme.TextPrimary,
                
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
            )
            Text(
                text = "Scan image metadata for geo exposures. Face detection acts as an automatic safety filter.",
                color = NeuralTheme.TextSecondary,
                
                fontSize = 12.5.sp,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            HorizontalDivider(color = NeuralTheme.BorderColor, thickness = 1.dp)
            Spacer(modifier = Modifier.height(20.dp))

            MediaSelectorRow(
                label = "Target Place Image",
                selectedUri = selectedUri,
                onSelect = { placeLauncher.launch("image/*") }
            )

            Spacer(modifier = Modifier.height(24.dp))

            if (isAnalyzing) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularWavyProgressIndicator(
                        size = 32.dp,
                        brush = NeuralTheme.GeminiGradient,
                        strokeWidth = 2.5.dp,
                        waveCount = 5,
                        amplitude = 2.5.dp
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "Executing safety sweep on NPU...",
                        color = NeuralTheme.Cyan,
                        
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            scanResult?.let { result ->
                Text(
                    text = "Safety Gate Status",
                    color = NeuralTheme.Cyan,
                    
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                )

                if (result.faceSkipped) {
                    // Pulsing Threat Glow Warning Card
                    val warningTransition = rememberInfiniteTransition(label = "warnPulse")
                    val warnAlpha by warningTransition.animateFloat(
                        initialValue = 0.4f,
                        targetValue = 0.8f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1000, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "warnAlpha"
                    )

                    Card(
                        colors = CardDefaults.cardColors(containerColor = NeuralTheme.CardBackground),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(NeuralTheme.Crimson.copy(alpha = 0.12f), cardShape)
                            .border(1.2.dp, NeuralTheme.Crimson.copy(alpha = warnAlpha), cardShape),
                        shape = cardShape
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(18.dp)) {
                                    GeminiSpark(size = 14.dp, glowColor = NeuralTheme.Crimson)
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "WARNING: FACE DETECTED",
                                    color = NeuralTheme.Crimson,
                                    
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = result.faceWarning ?: "",
                                color = NeuralTheme.TextPrimary,
                                
                                fontSize = 13.sp,
                                lineHeight = 19.sp
                            )
                        }
                    }
                } else {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = NeuralTheme.CardBackground),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(NeuralTheme.Emerald.copy(alpha = 0.12f), cardShape)
                            .border(1.dp, NeuralTheme.Emerald.copy(alpha = 0.8f), cardShape),
                        shape = cardShape
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Text(
                                text = "✓ SAFETY GATE CLEANED",
                                color = NeuralTheme.Emerald,
                                
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "No visual face signals detected. Identity search is safely bypassed. Proceeding with location marker audit.",
                                color = NeuralTheme.TextPrimary,
                                
                                fontSize = 13.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Compiled Metadata Clues",
                    color = NeuralTheme.Cyan,
                    
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                )
                
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
                                text = "EXIF GPS Coordinates",
                                color = NeuralTheme.TextSecondary,
                                
                                fontSize = 13.sp
                            )
                            Text(
                                text = result.gps ?: "NOT DETECTED",
                                color = if (result.gps != null) NeuralTheme.Crimson else NeuralTheme.Emerald,
                                
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        
                        if (!result.extractedText.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = "Extracted Image Text (OCR):",
                                color = NeuralTheme.TextSecondary,
                                
                                fontSize = 11.5.sp
                            )
                            Text(
                                text = result.extractedText,
                                color = NeuralTheme.TextPrimary,
                                
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }

                        if (result.detectedLandmarks.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = "On-Device Visual Landmark Matches:",
                                color = NeuralTheme.TextSecondary,
                                
                                fontSize = 11.5.sp
                            )
                            Text(
                                text = result.detectedLandmarks.joinToString(", "),
                                color = NeuralTheme.Cyan,
                                
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        
                        result.locationQuery?.let { query ->
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = "Resolved Maps Search Query:",
                                color = NeuralTheme.TextSecondary,
                                
                                fontSize = 11.5.sp
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp)
                                    .background(NeuralTheme.CardBackground)
                                    .border(1.dp, NeuralTheme.BorderColor, RoundedCornerShape(8.dp))
                                    .padding(12.dp)
                            ) {
                                Text(
                                    text = query,
                                    color = NeuralTheme.Cyan,
                                    
                                    fontSize = 11.5.sp
                                )
                            }
                        }
                    }
                }
            }

            }

            // Pinned navigation buttons at the bottom
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Button(
                    onClick = onBack,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = NeuralTheme.TextPrimary
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .border(1.dp, NeuralTheme.BorderColor, io.dossier.app.ui.theme.DossierButtonShape),
                    shape = io.dossier.app.ui.theme.DossierButtonShape
                ) {
                    Text("Back",  fontWeight = FontWeight.Bold)
                }
                
                Spacer(modifier = Modifier.width(16.dp))

                Button(
                    onClick = onNext,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .background(buttonGradient, io.dossier.app.ui.theme.DossierButtonShape)
                        .border(1.dp, Color.White.copy(alpha = 0.2f), io.dossier.app.ui.theme.DossierButtonShape),
                    shape = io.dossier.app.ui.theme.DossierButtonShape,
                    contentPadding = PaddingValues()
                ) {
                    Text("Proceed to Scan",  fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }
}
