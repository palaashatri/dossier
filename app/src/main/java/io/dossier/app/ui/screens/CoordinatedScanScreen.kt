package io.dossier.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
 * Compatibility shell that exposes the existing scan through the typed event bus.
 * Durable WorkManager execution now means the user can explicitly leave this screen
 * without cancelling the scan; the worker continues independently of Compose.
 */
@Composable
fun CoordinatedScanScreen(
    onScanComplete: () -> Unit,
    onScanFailed: () -> Unit,
    onScanCancelled: () -> Unit,
    onInvalidInput: () -> Unit = onScanCancelled,
    onScanBackgrounded: () -> Unit = onScanCancelled
) {
    LaunchedEffect(Unit) {
        ScanCoordinatorRuntime.ensureMonitoring()
    }
    val snapshot by ScanCoordinatorRuntime.snapshot.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        ScanScreen(
            onScanComplete = onScanComplete,
            onScanFailed = onScanFailed,
            onScanCancelled = onScanCancelled,
            onInvalidInput = onInvalidInput,
            onScanBackgrounded = onScanBackgrounded
        )

        if (snapshot.state == ScanRunState.Running) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .safeDrawingPadding()
                    .padding(top = 8.dp, end = 12.dp),
                horizontalAlignment = Alignment.End
            ) {
                Row(
                    modifier = Modifier
                        .background(
                            NeuralTheme.CardBackground.copy(alpha = 0.94f),
                            RoundedCornerShape(9.dp)
                        )
                        .border(1.dp, NeuralTheme.BorderColor, RoundedCornerShape(9.dp))
                        .semantics {
                            contentDescription = buildString {
                                append("${snapshot.mode.name} scan. ")
                                append("Providers scheduled: ${snapshot.scheduledProviderCount}. ")
                                append("Providers completed: ${snapshot.completedProviderCount}. ")
                                append("Providers unavailable: ${snapshot.unavailableProviderCount}. ")
                                append("Profile results: ${snapshot.profileCount}. ")
                                append("Graph entities: ${snapshot.entityCount}. ")
                                append("Findings: ${snapshot.findingCount}. ")
                                append("Pivots admitted: ${snapshot.pivotAdmittedCount}. ")
                                append("Pivots rejected: ${snapshot.pivotRejectedCount}. ")
                                append("Pivots pending: ${snapshot.pivotPendingCount}.")
                                snapshot.pivotLastDecision?.let { decision ->
                                    append(" Last pivot ${if (decision.admitted) "admitted" else "rejected"}. ")
                                    append("${decision.signalType} at depth ${decision.depth}: ${decision.reason}.")
                                }
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
                    LiveMetric("SCH", snapshot.scheduledProviderCount)
                    Spacer(Modifier.width(5.dp))
                    LiveMetric("DONE", snapshot.completedProviderCount)
                    Spacer(Modifier.width(5.dp))
                    LiveMetric("UNAV", snapshot.unavailableProviderCount)
                    Spacer(Modifier.width(5.dp))
                    LiveMetric("RES", snapshot.profileCount)
                    Spacer(Modifier.width(5.dp))
                    LiveMetric("G", snapshot.entityCount)
                    Spacer(Modifier.width(5.dp))
                    LiveMetric("E", snapshot.findingCount)
                }

                if (snapshot.pivotMaxDepth > 0) {
                    val pendingByDepth = snapshot.pivotPendingByDepth
                        .mapIndexed { index, count -> "d${index + 1}:$count" }
                        .joinToString(" ")
                    val latest = snapshot.pivotLastDecision
                    val diagnosticText = buildString {
                        append("PIVOTS  +${snapshot.pivotAdmittedCount} admitted  ")
                        append("-${snapshot.pivotRejectedCount} rejected  ")
                        append("${snapshot.pivotPendingCount} pending")
                        if (pendingByDepth.isNotBlank()) append(" ($pendingByDepth)")
                        latest?.let { decision ->
                            append(" | [${if (decision.admitted) "ADMITTED" else "REJECTED"}] ")
                            append("${decision.signalType} d${decision.depth}: ${decision.reason}")
                        }
                    }
                    Text(
                        text = diagnosticText,
                        color = NeuralTheme.TextSecondary,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 3,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier
                            .widthIn(max = 360.dp)
                            .padding(top = 4.dp)
                            .background(
                                NeuralTheme.CardBackground.copy(alpha = 0.94f),
                                RoundedCornerShape(7.dp)
                            )
                            .border(1.dp, NeuralTheme.BorderColor, RoundedCornerShape(7.dp))
                            .semantics {
                                contentDescription = diagnosticText
                            }
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    )
                }

                if (snapshot.recoveryStage.isNotBlank()) {
                    val recoveryText = buildString {
                        append("Recovery stage: ${snapshot.recoveryStage}. ")
                        append(
                            if (snapshot.recoveryCheckpointAvailable) {
                                "Checkpoint available. "
                            } else {
                                "No checkpoint available. "
                            }
                        )
                        append("Reused ${snapshot.recoveryReusedCount}; reran ${snapshot.recoveryRerunCount}.")
                    }
                    Text(
                        text = recoveryText,
                        color = NeuralTheme.TextSecondary,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier
                            .widthIn(max = 360.dp)
                            .padding(top = 4.dp)
                            .background(
                                NeuralTheme.CardBackground.copy(alpha = 0.94f),
                                RoundedCornerShape(7.dp)
                            )
                            .border(1.dp, NeuralTheme.BorderColor, RoundedCornerShape(7.dp))
                            .semantics {
                                contentDescription = recoveryText
                            }
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    )
                }
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
