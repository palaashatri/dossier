package io.dossier.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.compose.rememberNavController
import io.dossier.app.ui.navigation.DossierNavHost
import io.dossier.app.ui.theme.DossierTheme
import io.dossier.app.ui.theme.NeuralTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val darkTheme = isSystemInDarkTheme()
            DossierTheme(darkTheme = darkTheme) {
                val colors = NeuralTheme.colors
                val colorScheme = if (darkTheme) {
                    darkColorScheme(
                        primary = colors.cobalt,
                        onPrimary = colors.onAccent,
                        primaryContainer = colors.accentSurface,
                        onPrimaryContainer = colors.textPrimary,
                        background = colors.background,
                        onBackground = colors.textPrimary,
                        surface = colors.surface,
                        onSurface = colors.textPrimary,
                        surfaceVariant = colors.cardBackground,
                        onSurfaceVariant = colors.textSecondary,
                        outline = colors.borderColor,
                        secondary = colors.accentDim,
                        error = colors.crimson
                    )
                } else {
                    lightColorScheme(
                        primary = colors.cobalt,
                        onPrimary = colors.onAccent,
                        primaryContainer = colors.accentSurface,
                        onPrimaryContainer = colors.textPrimary,
                        background = colors.background,
                        onBackground = colors.textPrimary,
                        surface = colors.surface,
                        onSurface = colors.textPrimary,
                        surfaceVariant = colors.cardBackground,
                        onSurfaceVariant = colors.textSecondary,
                        outline = colors.borderColor,
                        secondary = colors.accentDim,
                        error = colors.crimson
                    )
                }
                MaterialTheme(colorScheme = colorScheme) {
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(NeuralTheme.BackgroundGradient),
                        color = Color.Transparent
                    ) {
                        DossierNavHost(navController = rememberNavController())
                    }
                }
            }
        }
    }
}
