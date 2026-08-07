package io.dossier.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.dossier.app.domain.discovery.ScanCoordinatorRuntime
import io.dossier.app.domain.discovery.ScanRunState
import io.dossier.app.ui.theme.NeuralTheme

/**
 * Compatibility shell that keeps the existing face-consent-aware ScanScreen
 * intact while making its real ScanSession state available through the M2 typed
 * event bus. The compact HUD is derived from coordinator events/state only.
 */
@Composable
fun CoordinatedScanScreen(
    onScanComplete: () -> Unit,
    onScanCancelled: () -> Unit,
    onInvalidInput: () -> Unit = onScanCancelled
) {
    LaunchedEffect(Unit) {
        ScanCoordinatorRuntime.ensureMonitoring()
    }
    val snapshot by ScanCoordinatorRuntime.snapshot.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        ScanScreen(
            onScanComplete = onScanComplete,
            onScanCancelled = onScanCancelled,
            onInvalidInput = onInvalidInput
        )

        if (snapshot.state == ScanRunState.Running) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .safeDrawingPadding()
                    .padding(top = 8.dp, end = 12.dp)
                    .background(
                        NeuralTheme.CardBackground.copy(alpha = 0.94f),
                        RoundedCornerShape(9.dp)
                    )
                    .border(1.dp, NeuralTheme.BorderColor, RoundedCornerShape(9.dp))
                    .semantics {
                        contentDescription = buildString {
                            append("${snapshot.mode.name} scan. ")
                            append("${snapshot.directProfileProviders} direct profile providers. ")
                            append("${snapshot.profileCount} profile results. ")
                            append("${snapshot.entityCount} graph entities. ")
                            append("${snapshot.findingCount} findings.")
                        }
                    }
                    .padding(horizontal = 9.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = snapshot.mode.name.uppercase(),
                    color = NeuralTheme.Cobalt,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.width(7.dp))
                LiveMetric("P", snapshot.profileCount)
                Spacer(Modifier.width(5.dp))
                LiveMetric("G", snapshot.entityCount)
                Spacer(Modifier.width(5.dp))
                LiveMetric("E", snapshot.findingCount)
            }
        }
    }
}

@Composable
private fun LiveMetric(label: String, value: Int) {
    Text(
        text = "$label:$value",
        color = NeuralTheme.TextSecondary,
        fontSize = 9.5.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium
    )
}
