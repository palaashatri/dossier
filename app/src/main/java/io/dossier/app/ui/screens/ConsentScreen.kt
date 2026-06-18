package io.dossier.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.dossier.app.ui.components.AnimatedObsidianBackground
import io.dossier.app.ui.components.GeminiSpark
import io.dossier.app.ui.theme.NeuralTheme

/**
 * Consent screen — calm, warm, inviting first impression. Clean typography,
 * generous whitespace, a single coral CTA. No pulsing, no sweep borders, no
 * monospace badges, no rotating bullets.
 */
@Composable
fun ConsentScreen(onAccepted: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedObsidianBackground(showGrid = false)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 28.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(40.dp))

                // Calm static spark mark
                GeminiSpark(size = 56.dp)

                Spacer(Modifier.height(24.dp))

                Text(
                    text = "Dossier",
                    color = NeuralTheme.TextPrimary,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "Privacy Exposure Audit",
                    color = NeuralTheme.TextSecondary,
                    fontSize = 15.sp,
                    fontStyle = FontStyle.Italic,
                    modifier = Modifier.padding(top = 2.dp)
                )

                Spacer(Modifier.height(32.dp))

                // Consent card — clean, flat, readable
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = NeuralTheme.CardBackground),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(22.dp)) {
                        Text(
                            text = "How this works",
                            color = NeuralTheme.TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(bottom = 10.dp)
                        )
                        Text(
                            text = "This app audits your own public exposure using signals you supply. " +
                                "It checks public profile pages for your name and handles, extracts " +
                                "exposed PII, and estimates where images were taken. All processing " +
                                "is on-device. No telemetry, no backend, no third-party identification.",
                            color = NeuralTheme.TextSecondary,
                            fontSize = 13.sp,
                            lineHeight = 20.sp
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                // Safety points — static, clean
                ConsentItem("Self-consented targets only")
                ConsentItem("100% local, in-memory storage")
                ConsentItem("Face matching is local and self-consented")
                ConsentItem("EXIF GPS location safety gate")
            }

            // Single calm CTA
            Button(
                onClick = onAccepted,
                colors = ButtonDefaults.buttonColors(containerColor = NeuralTheme.Cobalt),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .padding(top = 12.dp, bottom = 8.dp)
            ) {
                Text(
                    text = "Accept & Start",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun ConsentItem(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(NeuralTheme.Cobalt, CircleShape)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = text,
            color = NeuralTheme.TextSecondary,
            fontSize = 13.5.sp
        )
    }
}
