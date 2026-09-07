package io.dossier.app.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import io.dossier.app.data.web.LegacyOsintExportParser
import io.dossier.app.data.web.LegacyOsintImportSession
import io.dossier.app.ui.theme.DossierCardShape
import io.dossier.app.ui.theme.NeuralTheme
import kotlinx.coroutines.launch

/**
 * Local document-picker UI for legacy Twint/snscrape exports.
 * Selected bytes remain in the in-memory import session and are never uploaded.
 */
@Composable
fun LegacyOsintImportPicker() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }

    fun importResult(source: LegacyOsintExportParser.Source, uri: android.net.Uri?) {
        if (uri == null) return
        scope.launch {
            status = when (val result = LegacyOsintImportSession.add(context, uri, source)) {
                is LegacyOsintImportSession.ImportResult.Added ->
                    "Imported ${result.summary.displayName} locally; records remain candidates until independently verified."
                is LegacyOsintImportSession.ImportResult.Rejected -> result.reason
            }
            revision++
        }
    }

    val twintPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        importResult(LegacyOsintExportParser.Source.TwintJson, uri)
    }
    val snscrapePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        importResult(LegacyOsintExportParser.Source.SnscrapeJsonl, uri)
    }

    // revision intentionally participates in this read so the UI refreshes after
    // an asynchronous import without persisting the raw export in Compose state.
    val imports = remember(revision) { LegacyOsintImportSession.summaries() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(NeuralTheme.CardBackground, DossierCardShape)
            .border(1.dp, NeuralTheme.BorderColor, DossierCardShape)
            .padding(14.dp)
    ) {
        Text(
            "Legacy public-activity imports",
            color = NeuralTheme.TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            "Optional. Import a Twint JSON or snscrape JSON/JSONL export you are authorized to audit. Dossier does not run either scraper, and imported rows are not treated as verified ownership.",
            color = NeuralTheme.TextSecondary,
            fontSize = 11.5.sp,
            lineHeight = 16.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    twintPicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = NeuralTheme.Cobalt)
            ) {
                Text("Twint JSON", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(
                onClick = {
                    snscrapePicker.launch(arrayOf("application/json", "application/x-ndjson", "text/plain", "application/octet-stream"))
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = NeuralTheme.Cobalt)
            ) {
                Text("snscrape", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        if (imports.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            imports.forEach { item ->
                Text(
                    text = "• ${item.displayName} · ${item.byteCount / 1024} KiB · ${item.sha256.take(10)}…",
                    color = NeuralTheme.TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
            OutlinedButton(
                onClick = {
                    LegacyOsintImportSession.clear()
                    status = "Cleared local legacy imports."
                    revision++
                },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = NeuralTheme.TextSecondary),
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text("Clear imports", fontSize = 11.sp)
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
