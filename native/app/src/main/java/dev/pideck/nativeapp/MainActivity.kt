package dev.pideck.nativeapp

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
            PiDeckTheme(darkTheme = dark) {
                AppNav()
            }
        }
    }
}
