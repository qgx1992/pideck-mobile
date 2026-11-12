package dev.pideck.nativeapp

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.SideEffect
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.pideck.nativeapp.data.Prefs
import dev.pideck.nativeapp.data.ThemeMode
import dev.pideck.nativeapp.ui.AppNav
import dev.pideck.nativeapp.ui.theme.PiDeckTheme
import dev.pideck.nativeapp.watch.NotificationNav
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val notifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 拒绝就不弹通知，功能照跑 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        // API 35 起系统强制 edge-to-edge；这里显式开，交给 Compose 的 insets 处理。
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 只问一次：拒了之后不再每次冷启动都走一遍（怎么开回来写在设置页「通知」里）。
        lifecycleScope.launch {
            if (!Prefs.shouldAskNotificationPermission(this@MainActivity)) return@launch
            Prefs.markNotificationPermissionAsked(this@MainActivity)
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        takeNotificationTarget(intent)
        setContent {
            val mode by Prefs.themeFlow.collectAsState()
            val dark = when (mode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            // 状态栏 / 导航栏跟着主题走：
            // 图标明暗一次设好，窗口底色跟着换 —— API 35 强制 edge-to-edge 之后
            // statusBarColor 不再生效，系统栏那块露的就是窗口背景，所以必须改它。
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
                window.setBackgroundDrawable(
                    ColorDrawable(if (dark) 0xFF121316.toInt() else 0xFFF7F8FA.toInt()),
                )
            }
            PiDeckTheme(darkTheme = dark) {
                AppNav()
            }
        }
    }

    /**
     * 点「回复完成」通知进来：把目标会话交给 [AppNav]（冷启动当起点，热启动直接跳）。
     *
     * 读完就把 extras 摘掉：Activity 因配置变更重建时会拿同一份 Intent 再走一次
     * `onCreate`，不清掉的话转屏一次就又被拽到那个会话去。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeNotificationTarget(intent)
    }

    private fun takeNotificationTarget(intent: Intent?) {
        if (intent == null) return
        val deviceId = intent.getStringExtra(NotificationNav.EXTRA_DEVICE_ID)
        val sessionId = intent.getStringExtra(NotificationNav.EXTRA_SESSION_ID)
        intent.removeExtra(NotificationNav.EXTRA_DEVICE_ID)
        intent.removeExtra(NotificationNav.EXTRA_SESSION_ID)
        if (deviceId.isNullOrBlank() || sessionId.isNullOrBlank()) return
        NotificationNav.post(NotificationNav.Target(deviceId, sessionId))
    }
}
