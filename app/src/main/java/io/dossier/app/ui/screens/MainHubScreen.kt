package io.dossier.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.dossier.app.ui.theme.NeuralTheme

private enum class HubTab(val label: String) {
    DOSSIER("Dossier"),
    IMAGE_LOOKUP("Image Lookup"),
    BREACH("Breach"),
    CASES("Cases"),
    MODELS("Models")
}

/**
 * Top-level hub: a persistent bottom navigation bar with three tabs.
 *  - DOSSIER: nested flow (identity → username discovery → scan → report)
 *  - IMAGE_LOOKUP: standalone reverse image lookup
 *  - MODELS: on-device AI engine configuration
 *
 * `onNavigateToBrowser` is threaded in from the top-level NavHost so any tab can
 * open the in-built WebBrowser screen.
 */
@Composable
fun MainHubScreen(onNavigateToBrowser: (String) -> Unit) {
    var selectedTab by rememberSaveable { mutableStateOf(HubTab.DOSSIER) }
    val dossierNavController: NavHostController = rememberNavController()

    // Lottie transition overlay — plays a tagged animation when the dossier
    // flow navigates between screens.
    var transitionTag by remember { mutableStateOf<String?>(null) }
    val currentRoute = dossierNavController.currentBackStackEntryAsState().value?.destination?.route
    LaunchedEffect(currentRoute) {
        // Trigger a transition on each destination change (debounced: only when
        // a real route is present and we aren't already mid-transition).
        val tag = io.dossier.app.ui.components.transitionTagForRoute(currentRoute)
        if (tag != null && transitionTag == null) {
            transitionTag = tag
        }
    }
    io.dossier.app.ui.components.LottieTransitionOverlay(
        activeTag = transitionTag,
        onFinished = { transitionTag = null }
    )

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
                NavigationBar(
                    containerColor = NeuralTheme.CardBackground
                ) {
                    HubTab.entries.forEach { tab ->
                        val selected = selectedTab == tab
                        NavigationBarItem(
                            selected = selected,
                            onClick = { selectedTab = tab },
                                icon = {
                                    Icon(
                                        imageVector = when (tab) {
                                            HubTab.DOSSIER -> Icons.Default.AccountBox
                                            HubTab.IMAGE_LOOKUP -> Icons.Default.Search
                                            HubTab.BREACH -> Icons.Default.Lock
                                            HubTab.CASES -> Icons.Default.DateRange
                                            HubTab.MODELS -> Icons.Default.Settings
                                        },
                                        contentDescription = tab.label
                                    )
                                },
                            label = {
                                Text(
                                    tab.label,
                                    fontSize = 11.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = NeuralTheme.BackgroundStart,
                                selectedTextColor = NeuralTheme.Cobalt,
                                unselectedIconColor = NeuralTheme.TextSecondary,
                                unselectedTextColor = NeuralTheme.TextSecondary,
                                indicatorColor = NeuralTheme.Cobalt
                            )
                        )
                    }
                }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent)
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                HubTab.DOSSIER -> DossierNavGraph(
                    navController = dossierNavController,
                    onNavigateToBrowser = onNavigateToBrowser
                )
                HubTab.IMAGE_LOOKUP -> ReverseImageLookupScreen(onNavigateToBrowser = onNavigateToBrowser)
                HubTab.BREACH -> BreachCheckScreen(onNavigateToBrowser = onNavigateToBrowser)
                HubTab.CASES -> CaseComparisonScreen()
                HubTab.MODELS -> ModelsScreen()
            }
        }
    }
}

/**
 * The nested Dossier flow: identity → username discovery → scan → report.
 * Lives entirely inside the DOSSIER tab. Place image lookup moved to its own tab.
 */
@Composable
private fun DossierNavGraph(
    navController: NavHostController,
    onNavigateToBrowser: (String) -> Unit
) {
    NavHost(navController = navController, startDestination = "identity") {
        composable("identity") {
            IdentityScreen(onNext = { navController.navigate("username_discovery") })
        }
        composable("username_discovery") {
            UsernameDiscoveryScreen(
                onNext = { navController.navigate("scan") },
                onBack = { navController.popBackStack() }
            )
        }
        composable("scan") {
            ScanScreen(onScanComplete = {
                try {
                    android.util.Log.d("MainHub", "ScanScreen.onScanComplete() called, navigating to report")
                    android.util.Log.d("MainHub", "Current back stack: ${navController.currentBackStackEntry?.destination?.route}")
                    navController.navigate("report") {
                        // Don't pop anything — just push report onto the stack
                        launchSingleTop = false
                    }
                    android.util.Log.d("MainHub", "Navigation to report succeeded")
                } catch (e: Exception) {
                    android.util.Log.e("MainHub", "Navigation to report failed: ${e.message}")
                }
            })
        }
        composable("report") {
            ReportScreen(
                onReset = {
                    navController.navigate("identity") {
                        popUpTo("identity") { inclusive = true }
                    }
                },
                onNavigateToBrowser = onNavigateToBrowser,
                onDeepResearch = {
                    // Turn the persistent toggle on (so it reflects on the Identity
                    // panel after re-run) then re-scan from the report.
                    io.dossier.app.domain.scanner.ScanSession.setDeepResearch(true)
                    navController.navigate("scan") {
                        popUpTo("scan") { inclusive = true }
                    }
                }
            )
        }
    }
}
