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
import dev.pideck.nativeapp.ui.settings.SettingsScreen

object Routes {
    const val DEVICES = "devices"
    const val SESSIONS = "sessions/{deviceId}"
    const val CHAT = "chat/{deviceId}/{sessionId}"
    const val SETTINGS = "settings/{deviceId}"

    fun sessions(deviceId: String) = "sessions/$deviceId"

    fun chat(deviceId: String, sessionId: String) = "chat/$deviceId/$sessionId"

    fun settings(deviceId: String) = "settings/$deviceId"
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
                onOpenSettings = {
                    val deviceId = it.arguments?.getString("deviceId").orEmpty()
                    navController.navigate(Routes.settings(deviceId))
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
        composable(
            route = Routes.SETTINGS,
            arguments = listOf(navArgument("deviceId") { type = NavType.StringType }),
        ) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onManageDevices = {
                    // 设备页是栈底，直接弹回去比再压一层干净
                    navController.popBackStack(Routes.DEVICES, inclusive = false)
                },
            )
        }
    }
}
