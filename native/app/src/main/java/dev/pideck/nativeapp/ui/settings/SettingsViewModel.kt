package dev.pideck.nativeapp.ui.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pideck.nativeapp.data.CrashLog
import dev.pideck.nativeapp.data.Device
import dev.pideck.nativeapp.data.DeviceRepository
import dev.pideck.nativeapp.data.Prefs
import dev.pideck.nativeapp.data.StateCache
import dev.pideck.nativeapp.data.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: DeviceRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val deviceId: String = savedStateHandle["deviceId"] ?: ""

    var theme by mutableStateOf(Prefs.theme)
        private set

    var notifyDone by mutableStateOf(Prefs.notifyDone)
        private set

    /** 重启后恢复上次的收起状态（false = 一律全部收起）。 */
    var collapseRestore by mutableStateOf(Prefs.collapseRestore)
        private set

    /** 连续的过程条目要不要合并成「过程组」（桌面端同名开关）。 */
    var processGroupDisplay by mutableStateOf(Prefs.processGroupDisplay)
        private set

    /** 流式对话时要不要自动展开最新那一组。 */
    var expandInterimStream by mutableStateOf(Prefs.expandInterimStream)
        private set

    var device by mutableStateOf<Device?>(null)
        private set

    var hostVersion by mutableStateOf<String?>(null)
        private set

    var deviceCount by mutableStateOf(0)
        private set

    var sessionCount by mutableStateOf(0)
        private set

    /** 上一次崩溃的栈（`filesDir/last-crash.txt`）；没有就是 null。声明须在 init 之前。 */
    var crashLog by mutableStateOf<String?>(null)
        private set

    init {
        // Prefs 的镜像值可能还没读完（Application 里是异步灌的），先取一次再订
        viewModelScope.launch {
            Prefs.awaitLoaded()
            theme = Prefs.themeFlow.first()
            notifyDone = Prefs.notifyFlow.first()
            collapseRestore = Prefs.collapseRestore
            processGroupDisplay = Prefs.processGroupFlow.first()
            expandInterimStream = Prefs.expandInterimFlow.first()
        }
        viewModelScope.launch {
            val devices = runCatching { repository.devices.first() }.getOrDefault(emptyList())
            deviceCount = devices.size
            device = devices.firstOrNull { it.id == deviceId } ?: devices.firstOrNull()
            val cached = device?.let { StateCache.get(it.id) }
            hostVersion = cached?.version
            sessionCount = cached?.sessions?.size ?: 0
        }
        crashLog = runCatching { CrashLog.read(context) }.getOrNull()
    }

    fun clearCrashLog() {
        CrashLog.clear(context)
        crashLog = null
    }

    fun chooseTheme(mode: ThemeMode) {
        theme = mode
        viewModelScope.launch { Prefs.setTheme(context, mode) }
    }

    fun toggleNotifyDone(enabled: Boolean) {
        notifyDone = enabled
        viewModelScope.launch { Prefs.setNotifyDone(context, enabled) }
    }

    fun chooseCollapseRestore(enabled: Boolean) {
        collapseRestore = enabled
        viewModelScope.launch { Prefs.setCollapseRestore(context, enabled) }
    }

    fun chooseProcessGroupDisplay(enabled: Boolean) {
        processGroupDisplay = enabled
        viewModelScope.launch { Prefs.setProcessGroupDisplay(context, enabled) }
    }

    fun chooseExpandInterimStream(enabled: Boolean) {
        expandInterimStream = enabled
        viewModelScope.launch { Prefs.setExpandInterimStream(context, enabled) }
    }

    fun removeDevice(id: String) {
        viewModelScope.launch { repository.remove(id) }
    }
}
