package dev.pideck.nativeapp.ui

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
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
fun AppNav(startViewModel: StartViewModel = hiltViewModel()) {
    // 冷启动要读一次磁盘才知道停在哪个会话 —— 读完之前不渲染导航图，免得先闪一下设备页
    val startRoute = startViewModel.startRoute ?: return
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = startRoute) {
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
        ) { entry ->
            val deviceId = entry.arguments?.getString("deviceId").orEmpty()
            ChatScreen(
                deviceId = deviceId,
                onOpenSession = { sessionId ->
                    // 换会话=替换当前聊天条目，返回键不会在会话之间来回跳
                    navController.navigate(Routes.chat(deviceId, sessionId)) {
                        popUpTo(Routes.CHAT) { inclusive = true }
                    }
                },
                onOpenSessions = {
                    navController.navigate(Routes.sessions(deviceId))
                },
                onOpenSettings = { navController.navigate(Routes.settings(deviceId)) },
                onOpenDevices = {
                    navController.navigate(Routes.DEVICES) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }
        composable(
            route = Routes.SETTINGS,
            arguments = listOf(navArgument("deviceId") { type = NavType.StringType }),
        ) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onManageDevices = {
                    // 现在的首页可能是聊天页，设备页不一定在栈里 —— 弹不回去就新建一层
                    val popped = navController.popBackStack(Routes.DEVICES, inclusive = false)
                    if (!popped) {
                        navController.navigate(Routes.DEVICES) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                },
            )
        }
    }
}
