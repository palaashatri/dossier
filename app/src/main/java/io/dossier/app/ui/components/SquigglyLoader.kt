package io.dossier.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.dossier.app.ui.theme.NeuralTheme

/**
 * Calm loading indicators. Replaces the prior morphing-squiggly-wave components
 * (22+ infinite animations: nebula glow, counter-rotating rings, pulsing core,
 * ghost arcs) with clean, minimal spinners — purposeful motion only when loading.
 */

/**
 * Clean circular spinner — a rotating arc. Used for loading states.
 * Indeterminate when progress=null; determinate arc when a Float is given.
 */
@Composable
fun CircularWavyProgressIndicator(
    progress: Float? = null,
    modifier: Modifier = Modifier,
    size: Dp = 100.dp,
    @Suppress("UNUSED_PARAMETER") brush: Brush = NeuralTheme.GeminiGradient,
    strokeWidth: Dp = 4.dp,
    @Suppress("UNUSED_PARAMETER") amplitude: Dp = 4.dp,
    @Suppress("UNUSED_PARAMETER") waveCount: Int = 6,
    speedMs: Int = 2000,
    @Suppress("UNUSED_PARAMETER") trackBrush: Brush = Brush.linearGradient(listOf(androidx.compose.ui.graphics.Color.Transparent, androidx.compose.ui.graphics.Color.Transparent))
) {
    val transition = rememberInfiniteTransition(label = "spinner")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(speedMs, easing = LinearEasing), RepeatMode.Restart),
        label = "spinnerAngle"
    )
    val accent = NeuralTheme.Cobalt
    val track = NeuralTheme.BorderColor
    Canvas(modifier = modifier.size(size)) {
        val sw = strokeWidth.toPx()
        val r = (size.toPx() - sw) / 2f
        val center = Offset(size.toPx() / 2f, size.toPx() / 2f)
        drawCircle(color = track, radius = r, center = center, style = Stroke(width = sw))
        if (progress == null) {
            rotate(degrees = angle, pivot = center) {
                drawArc(
                    color = accent,
                    startAngle = -90f,
                    sweepAngle = 140f,
                    useCenter = false,
                    topLeft = Offset(center.x - r, center.y - r),
                    size = Size(r * 2, r * 2),
                    style = Stroke(width = sw, cap = StrokeCap.Round)
                )
            }
        } else {
            drawArc(
                color = accent,
                startAngle = -90f,
                sweepAngle = 360f * progress.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = Offset(center.x - r, center.y - r),
                size = Size(r * 2, r * 2),
                style = Stroke(width = sw, cap = StrokeCap.Round)
            )
        }
    }
}

/**
 * Clean linear progress — coral fill on a muted track. Determinate or indeterminate.
 */
@Composable
fun LinearWavyProgressIndicator(
    progress: Float? = null,
    modifier: Modifier = Modifier,
    height: Dp = 8.dp,
    @Suppress("UNUSED_PARAMETER") brush: Brush = NeuralTheme.GeminiGradient,
    @Suppress("UNUSED_PARAMETER") strokeWidth: Dp = 3.dp,
    @Suppress("UNUSED_PARAMETER") amplitude: Dp = 3.dp,
    @Suppress("UNUSED_PARAMETER") wavelength: Dp = 22.dp,
    speedMs: Int = 1500,
    @Suppress("UNUSED_PARAMETER") trackBrush: Brush = Brush.linearGradient(listOf(androidx.compose.ui.graphics.Color.Transparent, androidx.compose.ui.graphics.Color.Transparent))
) {
    val transition = rememberInfiniteTransition(label = "linearProg")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(speedMs, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "linearSweep"
    )
    val accent = NeuralTheme.Cobalt
    val track = NeuralTheme.BorderColor
    Canvas(modifier = modifier.height(height).fillMaxWidth()) {
        val w = size.width
        val cy = size.height / 2f
        val h = height.toPx()
        drawLine(track.copy(alpha = 0.5f), Offset(0f, cy), Offset(w, cy), h, StrokeCap.Round)
        val (start, end) = if (progress == null) {
            val sw = w * 0.3f
            ((w - sw) * sweep) to ((w - sw) * sweep + sw)
        } else 0f to (w * progress.coerceIn(0f, 1f))
        if (end > start) drawLine(accent, Offset(start, cy), Offset(end, cy), h, StrokeCap.Round)
    }
}

/**
 * Circular progress ring — used as an avatar aura / hero element.
 * Indeterminate when progress=null.
 */
@Composable
fun SquigglyProgressIndicator(
    size: Dp = 120.dp,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") brush: Brush = NeuralTheme.GeminiGradient,
    strokeWidth: Dp = 3.dp,
    @Suppress("UNUSED_PARAMETER") waveCount: Int = 5,
    @Suppress("UNUSED_PARAMETER") amplitudePercent: Float = 0.08f,
    speedMs: Int = 2500,
    progress: Float? = null
) {
    val transition = rememberInfiniteTransition(label = "ring")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(speedMs, easing = LinearEasing), RepeatMode.Restart),
        label = "ringAngle"
    )
    val accent = NeuralTheme.Cobalt
    val track = NeuralTheme.BorderColor
    Canvas(modifier = modifier.size(size)) {
        val sw = strokeWidth.toPx()
        val r = (size.toPx() - sw) / 2f
        val center = Offset(size.toPx() / 2f, size.toPx() / 2f)
        drawCircle(color = track, radius = r, center = center, style = Stroke(width = sw))
        if (progress == null) {
            rotate(degrees = angle, pivot = center) {
                drawArc(
                    color = accent,
                    startAngle = -90f,
                    sweepAngle = 120f,
                    useCenter = false,
                    topLeft = Offset(center.x - r, center.y - r),
                    size = Size(r * 2, r * 2),
                    style = Stroke(width = sw, cap = StrokeCap.Round)
                )
            }
        } else {
            drawArc(
                color = accent,
                startAngle = -90f,
                sweepAngle = 360f * progress.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = Offset(center.x - r, center.y - r),
                size = Size(r * 2, r * 2),
                style = Stroke(width = sw, cap = StrokeCap.Round)
            )
        }
    }
}

/**
 * Hero element for the ScanScreen — a calm indeterminate ring. Replaces the
 * 22-animation morphing orb (nebula glow, counter-rotating rings, pulsing core).
 */
@Composable
fun GeminiMorphingOrb(size: Dp = 150.dp, modifier: Modifier = Modifier) {
    SquigglyProgressIndicator(size = size, modifier = modifier, progress = null)
}
