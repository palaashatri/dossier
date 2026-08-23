package io.dossier.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.dossier.app.domain.discovery.DiscoveryScanPreferences
import io.dossier.app.domain.discovery.ScanMode
import io.dossier.app.domain.scanner.BackgroundScanManager
import io.dossier.app.domain.scanner.ScanSession
import io.dossier.app.ui.theme.NeuralTheme

private enum class HubTab(val label: String) {
    DOSSIER("Dossier"),
    IMAGE_LOOKUP("Images"),
    BREACH("Breaches"),
    CASES("Cases"),
    MODELS("Engines")
}

@Composable
fun MainHubScreen(onNavigateToBrowser: (String) -> Unit) {
    var selectedTab by rememberSaveable { mutableStateOf(HubTab.DOSSIER) }
    val stateHolder = rememberSaveableStateHolder()
    val dossierNavController: NavHostController = rememberNavController()
    val currentDossierRoute = dossierNavController
        .currentBackStackEntryAsState()
        .value
        ?.destination
        ?.route

    LaunchedEffect(currentDossierRoute) {
        if (currentDossierRoute in listOf("identity", "username_discovery", "scan", "analysis", "report")) {
            selectedTab = HubTab.DOSSIER
        }
    }

    BackHandler(enabled = selectedTab != HubTab.DOSSIER) {
        selectedTab = HubTab.DOSSIER
    }

    var transitionTag by remember { mutableStateOf<String?>(null) }
    var initialRouteObserved by remember { mutableStateOf(false) }
    LaunchedEffect(currentDossierRoute) {
        if (!initialRouteObserved) {
            initialRouteObserved = true
        } else {
            transitionTag = io.dossier.app.ui.components.transitionTagForRoute(currentDossierRoute)
        }
    }
    io.dossier.app.ui.components.LottieTransitionOverlay(
        activeTag = transitionTag,
        onFinished = { transitionTag = null }
    )

    val scanInForeground = selectedTab == HubTab.DOSSIER && currentDossierRoute == "scan"

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            if (!scanInForeground) {
                NavigationBar(containerColor = NeuralTheme.CardBackground) {
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
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
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
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent)
                .padding(innerPadding)
        ) {
            stateHolder.SaveableStateProvider(selectedTab.name) {
                when (selectedTab) {
                    HubTab.DOSSIER -> DossierNavGraph(
                        navController = dossierNavController,
                        onNavigateToBrowser = onNavigateToBrowser
                    )
                    HubTab.IMAGE_LOOKUP -> ReverseImageLookupScreen(
                        onNavigateToBrowser = onNavigateToBrowser
                    )
                    HubTab.BREACH -> BreachCheckScreen(
                        onNavigateToBrowser = onNavigateToBrowser
                    )
                    HubTab.CASES -> CaseComparisonScreen()
                    HubTab.MODELS -> ModelsScreen()
                }
            }
        }
    }
}

@Composable
private fun DossierNavGraph(
    navController: NavHostController,
    onNavigateToBrowser: (String) -> Unit
) {
    val context = LocalContext.current
    val initialRoute = remember(context) {
        if (
            BackgroundScanManager.hasActiveMarker(context) ||
            BackgroundScanManager.latestResult(context) != null
        ) "analysis" else "identity"
    }
    NavHost(navController = navController, startDestination = initialRoute) {
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
            CoordinatedScanScreen(
                onScanComplete = {
                    navController.navigate("report") {
                        popUpTo("scan") { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onScanFailed = {
                    navController.navigate("analysis") {
                        popUpTo("scan") { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onScanBackgrounded = {
                    navController.navigate("analysis") {
                        popUpTo("scan") { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onScanCancelled = {
                    val returned = navController.popBackStack("username_discovery", inclusive = false)
                    if (!returned) {
                        navController.navigate("identity") {
                            popUpTo("identity") { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                },
                onInvalidInput = {
                    navController.navigate("identity") {
                        popUpTo("identity") { inclusive = false }
                        launchSingleTop = true
                    }
                }
            )
        }
        composable("analysis") {
            AnalysisScreen(
                onOpenReport = {
                    navController.navigate("report") {
                        popUpTo("analysis") { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onBackToSetup = {
                    navController.navigate("identity") {
                        popUpTo("identity") { inclusive = false }
                        launchSingleTop = true
                    }
                }
            )
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
                    DiscoveryScanPreferences.setMode(ScanMode.Deep)
                    ScanSession.setDeepResearch(true)
                    navController.navigate("scan") {
                        popUpTo("scan") { inclusive = true }
                    }
                }
            )
        }
    }
}
