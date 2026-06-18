package io.dossier.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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
            DossierTheme {
                // Material3 color scheme wired to our palette so M3 components
                // (Switch, NavigationBar, etc.) match.
                val colors = NeuralTheme.colors
                val colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) {
                    darkColorScheme(
                        primary = colors.cobalt,
                        background = colors.background,
                        surface = colors.surface,
                        onPrimary = Color.White,
                        onBackground = colors.textPrimary,
                        onSurface = colors.textPrimary,
                        outline = colors.borderColor,
                        secondary = colors.accentDim
                    )
                } else {
                    lightColorScheme(
                        primary = colors.cobalt,
                        background = colors.background,
                        surface = colors.surface,
                        onPrimary = Color.White,
                        onBackground = colors.textPrimary,
                        onSurface = colors.textPrimary,
                        outline = colors.borderColor,
                        secondary = colors.accentDim
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
