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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.dossier.app.domain.scanner.ScanSession
import io.dossier.app.ui.theme.DossierCardShape
import io.dossier.app.ui.theme.NeuralTheme

/**
 * Calm, restrained component kit — flat surfaces, clean typography, no glow /
 * brackets / blinking. Motion only on interaction or loading.
 */

enum class HudLevel { OK, WARN, CRIT, INFO }

@Composable
fun hudLevelColor(level: HudLevel): Color = when (level) {
    HudLevel.OK -> NeuralTheme.Emerald
    HudLevel.WARN -> NeuralTheme.Amber
    HudLevel.CRIT -> NeuralTheme.Crimson
    HudLevel.INFO -> NeuralTheme.Cobalt
}

/**
 * Clean flat card — subtle border, 14dp corners, generous padding. No corner
 * brackets, no pulsing glow. Just a calm surface.
 */
@Composable
fun HudCard(
    modifier: Modifier = Modifier,
    glow: Boolean = false, // accepted for compat, ignored — no glow
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

/**
 * Section label — sans-serif, weight 600, muted. No `»` marker, no blinking dot.
 */
@Composable
fun HudLabel(
    text: String,
    modifier: Modifier = Modifier,
    marker: String = "",   // accepted for compat, ignored
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

/** Clean status badge — static, color by level. No pulsing dot. */
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
        fontSize = 9.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = FontFamily.Monospace,
        modifier = modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(5.dp))
            .border(0.6.dp, color.copy(alpha = 0.3f), RoundedCornerShape(5.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    )
}

/** Simple animated loading bar (only when loading). */
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
        val totalPx = with(density) { 300.dp.toPx() }
        Canvas(modifier = Modifier.fillMaxSize()) {
            val pos = x * (size.width - barWidthPx).coerceAtLeast(0f)
            drawRoundRect(
                color = barColor,
                topLeft = androidx.compose.ui.geometry.Offset(pos, 0f),
                size = androidx.compose.ui.geometry.Size(barWidthPx.coerceAtMost(size.width), size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2, size.height / 2)
            )
        }
    }
}

/** Clean thin divider. No spark accent. */
@Composable
fun HudDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        color = NeuralTheme.BorderColor,
        thickness = 0.7.dp,
        modifier = modifier.fillMaxWidth().padding(vertical = 10.dp)
    )
}

/**
 * Persistent Deep Research toggle — shown on all search panels. Reads/writes
 * [ScanSession.deepResearchEnabled]. Clean switch, no glow.
 */
@Composable
fun DeepResearchToggle(modifier: Modifier = Modifier) {
    val enabled by ScanSession.deepResearchEnabled.collectAsState()
    val border = if (enabled) NeuralTheme.Cobalt.copy(alpha = 0.5f) else NeuralTheme.BorderColor
    val bg = if (enabled) NeuralTheme.Cobalt.copy(alpha = 0.06f) else NeuralTheme.CardBackground

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(bg, DossierCardShape)
            .border(1.dp, border, DossierCardShape)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Deep Research",
                color = if (enabled) NeuralTheme.Cobalt else NeuralTheme.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = if (enabled) "Following linked sites & digging deeper"
                       else "Follows linked personal sites for more handles",
                color = NeuralTheme.TextSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        androidx.compose.material3.Switch(
            checked = enabled,
            onCheckedChange = { ScanSession.setDeepResearch(it) },
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = androidx.compose.ui.graphics.Color.White,
                checkedTrackColor = NeuralTheme.Cobalt,
                uncheckedThumbColor = NeuralTheme.TextSecondary,
                uncheckedTrackColor = NeuralTheme.BorderColor
            )
        )
    }
}
