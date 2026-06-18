package io.dossier.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.dossier.app.ui.theme.NeuralTheme

/**
 * Static decorative four-pointed star accent. No infinite rotation or pulse —
 * it's a calm logo mark, not a moving widget. Motion is reserved for loading
 * states and transitions only.
 */
@Composable
fun GeminiSpark(
    size: Dp = 48.dp,
    modifier: Modifier = Modifier,
    glowColor: Color = NeuralTheme.AccentDim,
    @Suppress("UNUSED_PARAMETER") animateSpeedMs: Int = 3000 // accepted for compat, ignored
) {
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        // Read theme colors in composable scope (they're @Composable getters).
        val cobalt = NeuralTheme.Cobalt
        val accentDim = NeuralTheme.AccentDim
        Canvas(modifier = Modifier.size(size)) {
            val cx = size.toPx() / 2f
            val cy = size.toPx() / 2f
            val r = size.toPx() / 2f * 0.9f

            val sparkPath = Path().apply {
                moveTo(cx, cy - r)
                quadraticBezierTo(cx, cy, cx + r, cy)
                quadraticBezierTo(cx, cy, cx, cy + r)
                quadraticBezierTo(cx, cy, cx - r, cy)
                quadraticBezierTo(cx, cy, cx, cy - r)
                close()
            }

            drawPath(
                path = sparkPath,
                brush = Brush.radialGradient(
                    colors = listOf(Color.White, cobalt, accentDim),
                    center = Offset(cx, cy),
                    radius = r
                )
            )
            drawPath(
                path = sparkPath,
                brush = Brush.horizontalGradient(
                    colors = listOf(cobalt, accentDim)
                ),
                style = Stroke(width = 1.5.dp.toPx())
            )
        }
    }
}

@Composable
fun GeminiSparkCluster(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        GeminiSpark(size = 64.dp)
    }
}
