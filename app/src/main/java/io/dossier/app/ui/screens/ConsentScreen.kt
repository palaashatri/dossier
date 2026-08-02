package io.dossier.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.dossier.app.ui.components.AnimatedObsidianBackground
import io.dossier.app.ui.components.GeminiSpark
import io.dossier.app.ui.theme.DossierButtonShape
import io.dossier.app.ui.theme.DossierCardShape
import io.dossier.app.ui.theme.NeuralTheme

/**
 * Session consent gate. It distinguishes local processing, public-network
 * discovery, optional third-party services, and explicit local persistence.
 */
@Composable
fun ConsentScreen(onAccepted: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedObsidianBackground(showGrid = false)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(32.dp))
                GeminiSpark(size = 52.dp)
                Spacer(Modifier.height(20.dp))

                Text(
                    text = "Dossier",
                    color = NeuralTheme.TextPrimary,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "Personal public-footprint audit",
                    color = NeuralTheme.TextSecondary,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(top = 3.dp)
                )

                Spacer(Modifier.height(28.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = NeuralTheme.CardBackground),
                    shape = DossierCardShape
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Before you begin",
                            color = NeuralTheme.TextPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Use Dossier only for your own information, a consenting subject, or an authorized research demonstration. Results are evidence leads and require manual review; an absent result does not prove that information never existed online.",
                            color = NeuralTheme.TextSecondary,
                            fontSize = 13.sp,
                            lineHeight = 19.sp
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))

                ConsentItem(
                    title = "Public-network discovery",
                    detail = "Names, handles, emails, profile URLs, and extracted text clues may be sent to public profiles, search engines, image indexes, and archives."
                )
                ConsentItem(
                    title = "Optional third-party services",
                    detail = "HIBP and configured remote AI providers are contacted only for the features you enable. Their own policies apply."
                )
                ConsentItem(
                    title = "Local visual processing",
                    detail = "Reverse-image verification and optional YuNet/SFace correlation run on-device. Strong face correlation requires a separate per-scan choice."
                )
                ConsentItem(
                    title = "Local storage",
                    detail = "A resumable scan input may be stored locally. Reports remain in memory unless you explicitly save an encrypted case or export evidence."
                )
                ConsentItem(
                    title = "No Dossier backend or telemetry",
                    detail = "The project does not operate a required server and the app does not send analytics telemetry."
                )
                Spacer(Modifier.height(8.dp))
            }

            Button(
                onClick = onAccepted,
                colors = ButtonDefaults.buttonColors(containerColor = NeuralTheme.Cobalt),
                shape = DossierButtonShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                Text(
                    text = "I understand — continue",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ConsentItem(title: String, detail: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 7.dp)
                .size(7.dp)
                .background(NeuralTheme.Cobalt, CircleShape)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = NeuralTheme.TextPrimary,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = detail,
                color = NeuralTheme.TextSecondary,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}
