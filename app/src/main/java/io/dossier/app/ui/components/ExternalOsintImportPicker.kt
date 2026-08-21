package io.dossier.app.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.dossier.app.data.web.ExternalOsintImportSession
import io.dossier.app.ui.theme.DossierCardShape
import io.dossier.app.ui.theme.NeuralTheme
import kotlinx.coroutines.launch

/**
 * Local picker for reports produced by supported external OSINT tooling.
 * Files stay in memory, are never uploaded, and are filtered against the active
 * audit's explicit seeds when the scan executes.
 */
@Composable
fun ExternalOsintImportPicker() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            status = when (val result = ExternalOsintImportSession.add(context, uri)) {
                is ExternalOsintImportSession.ImportResult.Added ->
                    "Imported ${result.summary.displayName} locally as ${result.summary.source.displayName}; usable rows remain candidates until Dossier verifies their public evidence."
                is ExternalOsintImportSession.ImportResult.Rejected -> result.reason
            }
            revision++
        }
    }

    val imports = remember(revision) { ExternalOsintImportSession.summaries() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(NeuralTheme.CardBackground, DossierCardShape)
            .border(1.dp, NeuralTheme.BorderColor, DossierCardShape)
            .padding(14.dp)
    ) {
        Text(
            "External OSINT report imports",
            color = NeuralTheme.TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            "Optional local interoperability for SpiderFoot, Recon-ng, theHarvester, Maigret, Sherlock, Holehe summaries, Pushshift, OSINTgram, Instaloader, Social Analyzer, public Facebook/LinkedIn exports, PhoneInfoga, Amass, Censys/Shodan reports and other supported public-report formats. Passwords, hashes, cookies, tokens and session secrets are never imported.",
            color = NeuralTheme.TextSecondary,
            fontSize = 11.5.sp,
            lineHeight = 16.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
        )

        OutlinedButton(
            onClick = {
                picker.launch(
                    arrayOf(
                        "application/json",
                        "application/x-ndjson",
                        "text/csv",
                        "text/tab-separated-values",
                        "text/plain",
                        "application/octet-stream"
                    )
                )
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = NeuralTheme.Cobalt)
        ) {
            Text("Import OSINT report", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
        }

        if (imports.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            imports.forEach { item ->
                Text(
                    text = "• ${item.source.displayName}: ${item.displayName} · ${item.byteCount / 1024} KiB · ${item.sha256.take(10)}…",
                    color = NeuralTheme.TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
            OutlinedButton(
                onClick = {
                    ExternalOsintImportSession.clear()
                    status = "Cleared local external OSINT imports."
                    revision++
                },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = NeuralTheme.TextSecondary),
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text("Clear reports", fontSize = 11.sp)
            }
        }

        status?.let { message ->
            Text(
                text = message,
                color = NeuralTheme.TextSecondary,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
