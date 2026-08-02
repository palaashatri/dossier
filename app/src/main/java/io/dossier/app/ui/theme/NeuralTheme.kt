package io.dossier.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Calm, restrained warm palette with explicit foreground roles. */
data class DossierColors(
    val background: Color,
    val surface: Color,
    val cardBackground: Color,
    val accentSurface: Color,
    val cobalt: Color,
    val onAccent: Color,
    val accentDim: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val borderColor: Color,
    val emerald: Color,
    val amber: Color,
    val crimson: Color,
    val violet: Color,
    val magenta: Color,
    val cyan: Color,
    val lavender: Color,
    val subtleGlow: Color,
    val borderGlow: Color,
    val accentGradient: Brush,
    val backgroundGradient: Brush,
    val threatGradient: Brush
)

// Text/status colors in the light palette are deliberately darker than the
// original coral set so normal-size labels remain legible on warm cream cards.
private val LightColors = DossierColors(
    background = Color(0xFFFAF9F5),
    surface = Color(0xFFFFFFFF),
    cardBackground = Color(0xFFF5F4EE),
    accentSurface = Color(0xFFFDF8F5),
    cobalt = Color(0xFFA94E35),
    onAccent = Color.White,
    accentDim = Color(0xFF8F3F2E),
    textPrimary = Color(0xFF1A1A18),
    textSecondary = Color(0xFF6B6B65),
    textMuted = Color(0xFF6F6F69),
    borderColor = Color(0xFFE0DED6),
    emerald = Color(0xFF34765B),
    amber = Color(0xFF8F5F20),
    crimson = Color(0xFFA23F3F),
    violet = Color(0xFF8F3F2E),
    magenta = Color(0xFFA94E35),
    cyan = Color(0xFFA94E35),
    lavender = Color(0xFF6F6F69),
    subtleGlow = Color(0xFFF5E6DE),
    borderGlow = Color(0xFFA94E35),
    accentGradient = Brush.horizontalGradient(
        listOf(Color(0xFFA94E35), Color(0xFF8F3F2E))
    ),
    backgroundGradient = Brush.verticalGradient(
        listOf(Color(0xFFFAF9F5), Color(0xFFF5F4EE))
    ),
    threatGradient = Brush.horizontalGradient(
        listOf(Color(0xFFA23F3F), Color(0xFF7D3030))
    )
)

private val DarkColors = DossierColors(
    background = Color(0xFF1C1C1A),
    surface = Color(0xFF262624),
    cardBackground = Color(0xFF2A2A27),
    accentSurface = Color(0xFF2E2724),
    cobalt = Color(0xFFD97757),
    // Filled coral surfaces need a dark foreground; white on this coral does
    // not provide enough contrast for normal-size button labels.
    onAccent = Color(0xFF1A1A18),
    accentDim = Color(0xFFB85F42),
    textPrimary = Color(0xFFF5F4EE),
    textSecondary = Color(0xFFA0A09A),
    textMuted = Color(0xFF85857E),
    borderColor = Color(0xFF454540),
    emerald = Color(0xFF79BE9D),
    amber = Color(0xFFE0B467),
    crimson = Color(0xFFF08080),
    violet = Color(0xFFCC8064),
    magenta = Color(0xFFD97757),
    cyan = Color(0xFFD97757),
    lavender = Color(0xFFB2B2AA),
    subtleGlow = Color(0xFF3A2A24),
    borderGlow = Color(0xFFD97757),
    accentGradient = Brush.horizontalGradient(
        listOf(Color(0xFFD97757), Color(0xFFB85F42))
    ),
    backgroundGradient = Brush.verticalGradient(
        listOf(Color(0xFF1C1C1A), Color(0xFF222220))
    ),
    threatGradient = Brush.horizontalGradient(
        listOf(Color(0xFFF08080), Color(0xFFA74343))
    )
)

val LocalDossierColors = staticCompositionLocalOf { LightColors }

@Composable
fun DossierTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors
    CompositionLocalProvider(LocalDossierColors provides colors, content = content)
}

val DossierCardShape = RoundedCornerShape(14.dp)
val DossierButtonShape = RoundedCornerShape(12.dp)

/** Compatibility shim while legacy call sites move to semantic Material roles. */
object NeuralTheme {
    val colors: DossierColors
        @Composable @ReadOnlyComposable get() = LocalDossierColors.current

    val BackgroundStart: Color @Composable get() = colors.background
    val BackgroundMid: Color @Composable get() = colors.surface
    val BackgroundEnd: Color @Composable get() = colors.background
    val CardBackground: Color @Composable get() = colors.cardBackground
    val SurfaceDark: Color @Composable get() = colors.surface
    val AccentSurface: Color @Composable get() = colors.accentSurface

    val Cobalt: Color @Composable get() = colors.cobalt
    val OnAccent: Color @Composable get() = colors.onAccent
    val AccentDim: Color @Composable get() = colors.accentDim

    val Violet: Color @Composable get() = colors.violet
    val Magenta: Color @Composable get() = colors.magenta
    val Cyan: Color @Composable get() = colors.cyan
    val Lavender: Color @Composable get() = colors.lavender
    val SubtleGlow: Color @Composable get() = colors.subtleGlow
    val BorderGlow: Color @Composable get() = colors.borderGlow

    val Emerald: Color @Composable get() = colors.emerald
    val Amber: Color @Composable get() = colors.amber
    val Crimson: Color @Composable get() = colors.crimson

    val TextPrimary: Color @Composable get() = colors.textPrimary
    val TextSecondary: Color @Composable get() = colors.textSecondary
    val TextMuted: Color @Composable get() = colors.textMuted
    val BorderColor: Color @Composable get() = colors.borderColor

    val HudGlow: Color @Composable get() = colors.cobalt
    val HudGlowDim: Color @Composable get() = colors.accentDim
    val ScanlineColor: Color @Composable get() = colors.borderColor
    val CornerBracketColor: Color @Composable get() = colors.borderColor
    val ReadoutColor: Color @Composable get() = colors.cobalt
    val HudCardShape = DossierCardShape

    val GeminiGradient: Brush @Composable get() = colors.accentGradient
    val GeminiSweep: Brush @Composable get() = colors.accentGradient
    val HudGradient: Brush @Composable get() = colors.accentGradient
    val HudSweep: Brush @Composable get() = colors.accentGradient
    val CyberCyanGradient: Brush @Composable get() = colors.accentGradient
    val ThreatGradient: Brush @Composable get() = colors.threatGradient
    val BackgroundGradient: Brush @Composable get() = colors.backgroundGradient
}
