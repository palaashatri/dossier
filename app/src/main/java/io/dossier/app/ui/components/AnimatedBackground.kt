package io.dossier.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.dossier.app.ui.theme.NeuralTheme

/**
 * Calm, serene warm backdrop. A simple static gradient — no particles, no
 * scanlines, no grid, no orbs. The visual quietude is the point: it lets the
 * content breathe instead of competing with it.
 *
 * (Replaces the prior 19-animation particle/scanline/orb/grid background that
 * made the app feel busy and "cheap." Motion is now reserved for loading
 * states and transitions — never idle decoration.)
 */
@Composable
fun AnimatedObsidianBackground(showGrid: Boolean = true) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NeuralTheme.BackgroundGradient)
    )
}
