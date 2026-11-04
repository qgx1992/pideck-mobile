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
 * 决定冷启动落在哪一页（启动动画期间完成）。
 *
 * 规则：有配对过的设备 → 直接进它的首页（不再甩设备列表让你点两下）；
 * 没有 → 设备页，也就是添加设备页。会话历史不参与决策 —— 首页才是默认落点，
 * 上次那个会话要进就自己从「最近的会话」点。
 */
@HiltViewModel
class StartViewModel @Inject constructor(
    repository: DeviceRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    /** null = 还在读盘，先别渲染导航图（启动动画盖着）。 */
    var startRoute by mutableStateOf<String?>(null)
        private set

    init {
        viewModelScope.launch {
            val devices = runCatching { repository.devices.first() }.getOrDefault(emptyList())
            val remembered = runCatching { Prefs.lastSession(context) }.getOrNull()?.first
            val device = devices.firstOrNull { it.id == remembered } ?: devices.firstOrNull()
            startRoute = if (device == null) Routes.DEVICES else Routes.home(device.id)
        }
    }
}
