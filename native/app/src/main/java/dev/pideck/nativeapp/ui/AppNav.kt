package dev.pideck.nativeapp.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.pideck.nativeapp.ui.chat.ChatScreen
import dev.pideck.nativeapp.ui.devices.DevicesScreen
import dev.pideck.nativeapp.ui.sessions.SessionsScreen

object Routes {
    const val DEVICES = "devices"
    const val SESSIONS = "sessions/{deviceId}"
    const val CHAT = "chat/{deviceId}/{sessionId}"

    fun sessions(deviceId: String) = "sessions/$deviceId"

    fun chat(deviceId: String, sessionId: String) = "chat/$deviceId/$sessionId"
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
            SessionsScreen(
                onBack = { navController.popBackStack() },
                onOpenSession = { sessionId ->
                    val deviceId = it.arguments?.getString("deviceId").orEmpty()
                    navController.navigate(Routes.chat(deviceId, sessionId))
                },
            )
        }
        composable(
            route = Routes.CHAT,
            arguments = listOf(
                navArgument("deviceId") { type = NavType.StringType },
                navArgument("sessionId") { type = NavType.StringType },
            ),
        ) {
            ChatScreen(onBack = { navController.popBackStack() })
        }
    }
}
