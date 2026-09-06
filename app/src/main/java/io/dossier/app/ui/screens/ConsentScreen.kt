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
import androidx.compose.ui.draw.clipToBounds
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
 * One-time usage notice. This is not identity verification and deliberately asks
 * for no documents, account linking, selfies, employer proof, or target proof.
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
                    .verticalScroll(rememberScrollState())
                    .clipToBounds(),
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
                    text = "OSINT & attack-surface intelligence",
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
                            text = "One-time usage notice",
                            color = NeuralTheme.TextPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Use Dossier for identities, organizations, infrastructure, cases, or research you are authorized to assess. Dossier does not ask you to prove that authorization or prove that you are the subject. Results are evidence leads and should be reviewed in context.",
                            color = NeuralTheme.TextSecondary,
                            fontSize = 13.sp,
                            lineHeight = 19.sp
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))

                ConsentItem(
                    title = "Public-source collection",
                    detail = "Queries may be sent to public profiles, search engines, image indexes, archives, breach-metadata services, and other configured public sources."
                )
                ConsentItem(
                    title = "Optional external services",
                    detail = "Features such as HIBP or configured remote AI are contacted only when their corresponding feature is enabled."
                )
                ConsentItem(
                    title = "Local analysis",
                    detail = "Graph analysis, behavioral post-processing, image comparison, OCR, EXIF parsing, and optional local face correlation run on-device where implemented."
                )
                ConsentItem(
                    title = "Storage is explicit",
                    detail = "Background/resume state may be stored locally. A completed investigation is not promoted to a saved encrypted Case unless you choose to save it."
                )
                ConsentItem(
                    title = "No required Dossier cloud",
                    detail = "Dossier has no required backend and no product analytics telemetry."
                )
                // Keep the final notice clear of the sticky footer when the user scrolls
                // to the end. The scroll viewport is clipped so content never paints
                // underneath the CTA while retaining the footer as a stable action.
                Spacer(Modifier.height(24.dp))
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
                    text = "CONTINUE",
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
            .padding(vertical = 5.dp),
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
