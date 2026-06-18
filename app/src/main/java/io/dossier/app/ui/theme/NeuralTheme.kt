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

/**
 * Calm, warm, restrained palette — inspired by Claude.ai's inviting minimalism.
 * Warm coral accent on warm-neutral bases. Two modes (light + dark). No glow,
 * no scanline, no cyberpunk — just serene, functional warmth.
 *
 * NOTE: [NeuralTheme] (the old object) is kept as a compatibility shim that
 * reads these colors, so the 172 existing call sites keep working while the
 * palette is now theme-aware.
 */
data class DossierColors(
    // Bases
    val background: Color,
    val surface: Color,
    val cardBackground: Color,
    val accentSurface: Color,
    // Accent — warm coral (the single brand color, used sparingly)
    val cobalt: Color,        // == accent (legacy alias name kept)
    val accentDim: Color,
    // Text
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    // Borders
    val borderColor: Color,
    // Status (muted, not saturated)
    val emerald: Color,
    val amber: Color,
    val crimson: Color,
    // Legacy aliases (resolve to accent/warm tones)
    val violet: Color,
    val magenta: Color,
    val cyan: Color,
    val lavender: Color,
    val subtleGlow: Color,
    val borderGlow: Color,
    // Gradients
    val accentGradient: Brush,
    val backgroundGradient: Brush,
    val threatGradient: Brush
)

private val LightColors = DossierColors(
    background = Color(0xFFFAF9F5),     // warm cream
    surface = Color(0xFFFFFFFF),
    cardBackground = Color(0xFFF5F4EE),  // soft warm card
    accentSurface = Color(0xFFFDF8F5),
    cobalt = Color(0xFFD97757),          // coral
    accentDim = Color(0xFFC26B4D),
    textPrimary = Color(0xFF1A1A18),
    textSecondary = Color(0xFF6B6B65),
    textMuted = Color(0xFF9A9A92),
    borderColor = Color(0xFFE8E6DF),
    emerald = Color(0xFF4A8B6F),
    amber = Color(0xFFB8843A),
    crimson = Color(0xFFC25555),
    violet = Color(0xFFC26B4D),
    magenta = Color(0xFFD97757),
    cyan = Color(0xFFD97757),
    lavender = Color(0xFF8A8278),
    subtleGlow = Color(0xFFF5E6DE),
    borderGlow = Color(0xFFD97757),
    accentGradient = Brush.horizontalGradient(listOf(Color(0xFFD97757), Color(0xFFC26B4D))),
    backgroundGradient = Brush.verticalGradient(listOf(Color(0xFFFAF9F5), Color(0xFFF5F4EE))),
    threatGradient = Brush.horizontalGradient(listOf(Color(0xFFC25555), Color(0xFF9A3D3D)))
)

private val DarkColors = DossierColors(
    background = Color(0xFF1C1C1A),     // warm charcoal
    surface = Color(0xFF262624),
    cardBackground = Color(0xFF2A2A27),
    accentSurface = Color(0xFF2E2724),
    cobalt = Color(0xFFD97757),          // coral (works on dark)
    accentDim = Color(0xFFB85F42),
    textPrimary = Color(0xFFF5F4EE),
    textSecondary = Color(0xFFA0A09A),
    textMuted = Color(0xFF6E6E68),
    borderColor = Color(0xFF3A3A36),
    emerald = Color(0xFF6BAF8E),
    amber = Color(0xFFD9A856),
    crimson = Color(0xFFE07070),
    violet = Color(0xFFB85F42),
    magenta = Color(0xFFD97757),
    cyan = Color(0xFFD97757),
    lavender = Color(0xFFA0A09A),
    subtleGlow = Color(0xFF3A2A24),
    borderGlow = Color(0xFFD97757),
    accentGradient = Brush.horizontalGradient(listOf(Color(0xFFD97757), Color(0xFFB85F42))),
    backgroundGradient = Brush.verticalGradient(listOf(Color(0xFF1C1C1A), Color(0xFF222220))),
    threatGradient = Brush.horizontalGradient(listOf(Color(0xFFE07070), Color(0xFF8A3838)))
)

val LocalDossierColors = staticCompositionLocalOf { LightColors }

/**
 * Root theme wrapper. Provides the correct palette based on system dark-mode
 * setting. Wrap the entire app in this.
 */
@Composable
fun DossierTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors
    CompositionLocalProvider(LocalDossierColors provides colors, content = content)
}

/** Unified card shape used across the app — calm, consistent 14dp corners. */
val DossierCardShape = RoundedCornerShape(14.dp)
val DossierButtonShape = RoundedCornerShape(12.dp)

/**
 * Compatibility shim — the old `NeuralTheme` object. Every property now reads
 * from [LocalDossierColors] so existing call sites (172 refs) stay valid while
 * becoming theme-aware. Legacy glow/scanline properties resolve to accent/border
 * (they're no longer used by the stripped-down components).
 */
object NeuralTheme {
    val colors: DossierColors
        @Composable @ReadOnlyComposable get() = LocalDossierColors.current

    // ---- Bases (delegate to current theme colors) ----
    val BackgroundStart: Color @Composable get() = colors.background
    val BackgroundMid: Color @Composable get() = colors.surface
    val BackgroundEnd: Color @Composable get() = colors.background
    val CardBackground: Color @Composable get() = colors.cardBackground
    val SurfaceDark: Color @Composable get() = colors.surface
    val AccentSurface: Color @Composable get() = colors.accentSurface

    // ---- Accent ----
    val Cobalt: Color @Composable get() = colors.cobalt
    val AccentDim: Color @Composable get() = colors.accentDim

    // ---- Legacy aliases ----
    val Violet: Color @Composable get() = colors.violet
    val Magenta: Color @Composable get() = colors.magenta
    val Cyan: Color @Composable get() = colors.cyan
    val Lavender: Color @Composable get() = colors.lavender
    val SubtleGlow: Color @Composable get() = colors.subtleGlow
    val BorderGlow: Color @Composable get() = colors.borderGlow

    // ---- Status ----
    val Emerald: Color @Composable get() = colors.emerald
    val Amber: Color @Composable get() = colors.amber
    val Crimson: Color @Composable get() = colors.crimson

    // ---- Text ----
    val TextPrimary: Color @Composable get() = colors.textPrimary
    val TextSecondary: Color @Composable get() = colors.textSecondary
    val TextMuted: Color @Composable get() = colors.textMuted

    // ---- Borders ----
    val BorderColor: Color @Composable get() = colors.borderColor

    // ---- HUD helpers — repointed to accent/border (the glow aesthetic is gone) ----
    val HudGlow: Color @Composable get() = colors.cobalt
    val HudGlowDim: Color @Composable get() = colors.accentDim
    val ScanlineColor: Color @Composable get() = colors.borderColor
    val CornerBracketColor: Color @Composable get() = colors.borderColor
    val ReadoutColor: Color @Composable get() = colors.cobalt

    val HudCardShape = DossierCardShape

    // ---- Gradients ----
    val GeminiGradient: Brush @Composable get() = colors.accentGradient
    val GeminiSweep: Brush @Composable get() = colors.accentGradient
    val HudGradient: Brush @Composable get() = colors.accentGradient
    val HudSweep: Brush @Composable get() = colors.accentGradient
    val CyberCyanGradient: Brush @Composable get() = colors.accentGradient
    val ThreatGradient: Brush @Composable get() = colors.threatGradient
    val BackgroundGradient: Brush @Composable get() = colors.backgroundGradient
}
