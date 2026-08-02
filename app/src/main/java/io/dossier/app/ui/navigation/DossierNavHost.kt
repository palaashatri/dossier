package io.dossier.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
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
    NavHost(navController = navController, startDestination = Screen.Consent.route) {
        composable(Screen.Consent.route) {
            ConsentScreen(
                onAccepted = {
                    navController.navigate(Screen.MainHub.route) {
                        // Consent is a session gate, not a destination users
                        // should return to when pressing Back from the app.
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
