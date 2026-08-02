package io.dossier.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.dossier.app.domain.scanner.ScanSession
import io.dossier.app.ui.theme.DossierCardShape
import io.dossier.app.ui.theme.NeuralTheme

enum class HudLevel { OK, WARN, CRIT, INFO }

@Composable
fun hudLevelColor(level: HudLevel): Color = when (level) {
    HudLevel.OK -> NeuralTheme.Emerald
    HudLevel.WARN -> NeuralTheme.Amber
    HudLevel.CRIT -> NeuralTheme.Crimson
    HudLevel.INFO -> NeuralTheme.Cobalt
}

@Composable
fun HudCard(
    modifier: Modifier = Modifier,
    glow: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = NeuralTheme.CardBackground),
        shape = DossierCardShape,
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, NeuralTheme.BorderColor, DossierCardShape)
    ) {
        Column(modifier = Modifier.padding(20.dp), content = content)
    }
}

@Composable
fun HudLabel(
    text: String,
    modifier: Modifier = Modifier,
    marker: String = "",
    blinkDot: Boolean = false,
    dotLevel: HudLevel = HudLevel.INFO
) {
    Text(
        text = text.uppercase(),
        color = NeuralTheme.TextSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        modifier = modifier.padding(start = 2.dp, bottom = 10.dp)
    )
}

@Composable
fun HudStatusPill(
    text: String,
    level: HudLevel,
    modifier: Modifier = Modifier
) {
    val color = hudLevelColor(level)
    Text(
        text = text,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = FontFamily.Monospace,
        modifier = modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .border(0.8.dp, color.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp)
    )
}

@Composable
fun ScanlineStrip(
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 3.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "loadingBar")
    val x by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "loadingBarX"
    )
    val density = LocalDensity.current
    val barColor = NeuralTheme.Cobalt
    val trackColor = NeuralTheme.BorderColor
    Box(
        modifier = modifier
            .height(height)
            .background(trackColor.copy(alpha = 0.5f), RoundedCornerShape(height / 2))
    ) {
        val barWidthPx = with(density) { 90.dp.toPx() }
        Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
            val pos = x * (size.width - barWidthPx).coerceAtLeast(0f)
            drawRoundRect(
                color = barColor,
                topLeft = androidx.compose.ui.geometry.Offset(pos, 0f),
                size = androidx.compose.ui.geometry.Size(
                    barWidthPx.coerceAtMost(size.width),
                    size.height
                ),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                    size.height / 2,
                    size.height / 2
                )
            )
        }
    }
}

@Composable
fun HudDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        color = NeuralTheme.BorderColor,
        thickness = 0.7.dp,
        modifier = modifier.fillMaxWidth().padding(vertical = 10.dp)
    )
}

/**
 * Persistent expansion toggle. The whole card is one 48dp+ switch target and
 * discloses the extra network work rather than describing it only as "deeper".
 */
@Composable
fun DeepResearchToggle(modifier: Modifier = Modifier) {
    val enabled by ScanSession.deepResearchEnabled.collectAsState()
    val border = if (enabled) NeuralTheme.Cobalt.copy(alpha = 0.65f) else NeuralTheme.BorderColor
    val bg = if (enabled) NeuralTheme.Cobalt.copy(alpha = 0.08f) else NeuralTheme.CardBackground

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(bg, DossierCardShape)
            .border(1.dp, border, DossierCardShape)
            .toggleable(
                value = enabled,
                role = Role.Switch,
                onValueChange = ScanSession::setDeepResearch
            )
            .semantics {
                stateDescription = if (enabled) "Enabled" else "Disabled"
            }
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Follow linked sites",
                color = if (enabled) NeuralTheme.Cobalt else NeuralTheme.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = if (enabled) {
                    "Enabled for search tools: follows a bounded set of public links. Scans may take longer and make more network requests."
                } else {
                    "Optional. Uses linked public pages to find additional handles and context."
                },
                color = NeuralTheme.TextSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = enabled,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = NeuralTheme.OnAccent,
                checkedTrackColor = NeuralTheme.Cobalt,
                uncheckedThumbColor = NeuralTheme.TextSecondary,
                uncheckedTrackColor = NeuralTheme.BorderColor
            )
        )
    }
}
