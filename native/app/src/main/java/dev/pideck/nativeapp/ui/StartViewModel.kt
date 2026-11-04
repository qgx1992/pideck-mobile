package dev.pideck.nativeapp.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pideck.nativeapp.data.DeviceRepository
import dev.pideck.nativeapp.data.Prefs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 决定冷启动落在哪一页。
 *
 * 目标：打开就是上次那个会话，而不是先给一个设备列表让你点两下。
 * 只有在「确实有配对设备」的时候才直连 —— 设备被删了、或者从没配过，回设备页。
 */
@HiltViewModel
class StartViewModel @Inject constructor(
    repository: DeviceRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    /** null = 还在读盘，先别渲染导航图。 */
    var startRoute by mutableStateOf<String?>(null)
        private set

    init {
        viewModelScope.launch {
            val devices = runCatching { repository.devices.first() }.getOrDefault(emptyList())
            val saved = runCatching { Prefs.lastSession(context) }.getOrNull()
            val deviceId = saved?.first?.takeIf { id -> devices.any { it.id == id } }
                ?: devices.firstOrNull()?.id
            // 设备在但会话记不起来了 → 进这个设备的会话列表，比直接甩设备页近一步
            startRoute = when {
                deviceId == null -> Routes.DEVICES
                saved?.first == deviceId && saved.second.isNotBlank() ->
                    Routes.chat(deviceId, saved.second)

                else -> Routes.sessions(deviceId)
            }
        }
    }
}
