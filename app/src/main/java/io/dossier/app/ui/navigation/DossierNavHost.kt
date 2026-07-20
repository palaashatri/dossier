package io.dossier.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import io.dossier.app.ui.screens.ConsentScreen
import io.dossier.app.ui.screens.MainHubScreen
import io.dossier.app.ui.screens.WebBrowserScreen
import androidx.navigation.NavType
import androidx.navigation.navArgument

sealed class Screen(val route: String) {
    object Consent : Screen("consent")
    object MainHub : Screen("main_hub")
    object WebBrowser : Screen("web_browser/{url}") {
        fun createRoute(url: String): String = "web_browser/${java.net.URLEncoder.encode(url, "UTF-8")}"
    }
}

@Composable
fun DossierNavHost(navController: NavHostController) {
    // TEMP: Skip consent, go directly to MainHub for testing
    NavHost(navController = navController, startDestination = Screen.MainHub.route) {
        composable(Screen.Consent.route) {
            ConsentScreen(
                onAccepted = { navController.navigate(Screen.MainHub.route) }
            )
        }
        composable(Screen.MainHub.route) {
            MainHubScreen(
                onNavigateToBrowser = { url -> navController.navigate(Screen.WebBrowser.createRoute(url)) }
            )
        }
        composable(
            route = Screen.WebBrowser.route,
            arguments = listOf(navArgument("url") { type = NavType.StringType })
        ) { backStackEntry ->
            val encodedUrl = backStackEntry.arguments?.getString("url") ?: ""
            val url = java.net.URLDecoder.decode(encodedUrl, "UTF-8")
            WebBrowserScreen(
                url = url,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
