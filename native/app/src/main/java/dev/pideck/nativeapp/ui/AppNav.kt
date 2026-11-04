package dev.pideck.nativeapp.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.pideck.nativeapp.ui.devices.DevicesScreen
import dev.pideck.nativeapp.ui.sessions.SessionsScreen

object Routes {
    const val DEVICES = "devices"
    const val SESSIONS = "sessions/{deviceId}"
    fun sessions(deviceId: String) = "sessions/$deviceId"
}

@Composable
fun AppNav() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.DEVICES) {
        composable(Routes.DEVICES) {
            DevicesScreen(
                onOpenDevice = { device -> navController.navigate(Routes.sessions(device.id)) },
            )
        }
        composable(
            route = Routes.SESSIONS,
            arguments = listOf(navArgument("deviceId") { type = NavType.StringType }),
        ) {
            SessionsScreen(onBack = { navController.popBackStack() })
        }
    }
}
