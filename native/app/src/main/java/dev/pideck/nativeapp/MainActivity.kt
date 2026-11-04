package dev.pideck.nativeapp

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
            PiDeckTheme {
                AppNav()
            }
        }
    }
}
