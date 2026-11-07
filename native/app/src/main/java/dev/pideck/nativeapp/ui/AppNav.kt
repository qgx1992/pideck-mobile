package dev.pideck.nativeapp.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.pideck.nativeapp.ui.chat.ChatScreen
import dev.pideck.nativeapp.ui.devices.DevicesScreen
import dev.pideck.nativeapp.ui.home.HomeScreen
import dev.pideck.nativeapp.ui.sessions.SessionsScreen
import dev.pideck.nativeapp.ui.settings.SettingsScreen
import dev.pideck.nativeapp.ui.settings.SkillsExtensionsScreen
import dev.pideck.nativeapp.ui.splash.SplashScreen
import dev.pideck.nativeapp.watch.NotificationNav
import kotlinx.coroutines.delay

object Routes {
    const val DEVICES = "devices"
    const val HOME = "home/{deviceId}"
    const val SESSIONS = "sessions/{deviceId}"
    const val CHAT = "chat/{deviceId}/{sessionId}?draft={draft}"
    const val SETTINGS = "settings/{deviceId}"
    const val SKILLS = "skills/{deviceId}"

    fun home(deviceId: String) = "home/$deviceId"

    fun sessions(deviceId: String) = "sessions/$deviceId"

    /** [draft] 非空时，聊天页加载完历史会立刻把它发出去（首页「选工作区 → 说事」的交接）。 */
    fun chat(deviceId: String, sessionId: String, draft: String = "") =
        "chat/$deviceId/$sessionId?draft=${Uri.encode(draft)}"

    fun settings(deviceId: String) = "settings/$deviceId"

    fun skills(deviceId: String) = "skills/$deviceId"
}

/** 启动动画最短停留时间：读盘再快也要让过渡看得见，否则等于没有。 */
private const val SPLASH_MIN_MS = 900L

@Composable
fun AppNav(startViewModel: StartViewModel = hiltViewModel()) {
    val startRoute = startViewModel.startRoute
    var splashDone by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(SPLASH_MIN_MS)
        splashDone = true
    }
    // 读盘（决定落哪一页）与最短动画时间任一没完成，都停在启动页
    if (startRoute == null || !splashDone) {
        SplashScreen()
        return
    }

    val navController = rememberNavController()
    // 冷启动点了「回复完成」通知：直接把那个会话当起点，别先闪一下首页再跳。
    // take() 顺带清空，下面的 LaunchedEffect 只负责「App 已经在跑时又点了通知」。
    val coldTarget = remember { NotificationNav.take() }
    val pendingTarget by NotificationNav.flow.collectAsState()
    LaunchedEffect(pendingTarget) {
        val target = pendingTarget ?: return@LaunchedEffect
        NotificationNav.clear()
        val current = navController.currentBackStackEntry
        val alreadyThere = current?.destination?.route == Routes.CHAT &&
            current.arguments?.getString("deviceId") == target.deviceId &&
            current.arguments?.getString("sessionId") == target.sessionId
        if (alreadyThere) return@LaunchedEffect
        navController.navigate(Routes.chat(target.deviceId, target.sessionId)) {
            if (current?.destination?.route == Routes.CHAT) {
                // 从别的会话点通知过来＝替换当前条目，返回键不会在会话之间来回退
                popUpTo(Routes.CHAT) { inclusive = true }
            } else {
                popUpTo(navController.graph.findStartDestination().id) { inclusive = false }
                launchSingleTop = true
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = coldTarget?.let { Routes.chat(it.deviceId, it.sessionId) } ?: startRoute,
    ) {
        composable(Routes.DEVICES) {
            DevicesScreen(
                onOpenDevice = { device -> navController.navigate(Routes.home(device.id)) },
            )
        }
        composable(
            route = Routes.HOME,
            arguments = listOf(navArgument("deviceId") { type = NavType.StringType }),
        ) { entry ->
            val deviceId = entry.arguments?.getString("deviceId").orEmpty()
            HomeScreen(
                onOpenChat = { sessionId, draft ->
                    navController.navigate(Routes.chat(deviceId, sessionId, draft)) {
                        // 首页留着当返回目标：从会话退回来还是「选工作区」
                        launchSingleTop = true
                    }
                },
                onOpenSessions = { navController.navigate(Routes.sessions(deviceId)) },
                onOpenSettings = { navController.navigate(Routes.settings(deviceId)) },
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
                navArgument("draft") {
                    type = NavType.StringType
                    defaultValue = ""
                },
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
                onOpenHome = {
                    navController.navigate(Routes.home(deviceId)) {
                        popUpTo(0) { inclusive = true }
                    }
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
        ) { entry ->
            val deviceId = entry.arguments?.getString("deviceId").orEmpty()
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onManageDevices = {
                    navController.navigate(Routes.DEVICES) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onOpenSkills = { navController.navigate(Routes.skills(deviceId)) },
            )
        }
        composable(
            route = Routes.SKILLS,
            arguments = listOf(navArgument("deviceId") { type = NavType.StringType }),
        ) {
            SkillsExtensionsScreen(onBack = { navController.popBackStack() })
        }
    }
}
