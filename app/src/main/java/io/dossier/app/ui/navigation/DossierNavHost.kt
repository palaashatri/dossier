package io.dossier.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import io.dossier.app.data.local.UsageNoticeStore
import io.dossier.app.ui.screens.ConsentScreen
import io.dossier.app.ui.screens.MainHubScreen
import io.dossier.app.ui.screens.WebBrowserScreen

sealed class Screen(val route: String) {
    object Consent : Screen("consent")
    object MainHub : Screen("main_hub")
    object WebBrowser : Screen("web_browser/{url}") {
        fun createRoute(url: String): String =
            "web_browser/${java.net.URLEncoder.encode(url, Charsets.UTF_8.name())}"
    }
}

@Composable
fun DossierNavHost(navController: NavHostController) {
    val context = LocalContext.current
    val startDestination = remember(context) {
        if (UsageNoticeStore.isAccepted(context)) Screen.MainHub.route else Screen.Consent.route
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Screen.Consent.route) {
            ConsentScreen(
                onAccepted = {
                    UsageNoticeStore.accept(context)
                    navController.navigate(Screen.MainHub.route) {
                        // One-time onboarding is not part of the normal back stack.
                        popUpTo(Screen.Consent.route) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }
        composable(Screen.MainHub.route) {
            MainHubScreen(
                onNavigateToBrowser = { url ->
                    navController.navigate(Screen.WebBrowser.createRoute(url))
                }
            )
        }
        composable(
            route = Screen.WebBrowser.route,
            arguments = listOf(navArgument("url") { type = NavType.StringType })
        ) { backStackEntry ->
            val encodedUrl = backStackEntry.arguments?.getString("url").orEmpty()
            val url = java.net.URLDecoder.decode(encodedUrl, Charsets.UTF_8.name())
            WebBrowserScreen(
                url = url,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
