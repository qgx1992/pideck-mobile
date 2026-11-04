package dev.pideck.nativeapp

import android.Manifest
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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.pideck.nativeapp.data.Prefs
import dev.pideck.nativeapp.data.ThemeMode
import dev.pideck.nativeapp.ui.AppNav
import dev.pideck.nativeapp.ui.theme.PiDeckTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val notifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 拒绝就不弹通知，功能照跑 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        // API 35 起系统强制 edge-to-edge；这里显式开，交给 Compose 的 insets 处理。
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
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
}
