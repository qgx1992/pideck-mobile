package dev.pideck.nativeapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.pideck.nativeapp.ui.AppNav
import dev.pideck.nativeapp.ui.theme.PiDeckTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // API 35 起系统强制 edge-to-edge；这里显式开，交给 Compose 的 insets 处理，
        // 不再需要 WebView 时代那套注入安全区的补丁。
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            PiDeckTheme {
                AppNav()
            }
        }
    }
}
