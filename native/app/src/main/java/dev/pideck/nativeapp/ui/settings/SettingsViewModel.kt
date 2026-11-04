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

    var device by mutableStateOf<Device?>(null)
        private set

    var hostVersion by mutableStateOf<String?>(null)
        private set

    var deviceCount by mutableStateOf(0)
        private set

    var sessionCount by mutableStateOf(0)
        private set

    init {
        // Prefs 的镜像值可能还没读完（Application 里是异步灌的），先取一次再订
        viewModelScope.launch {
            theme = Prefs.themeFlow.first()
            notifyDone = Prefs.notifyFlow.first()
        }
        viewModelScope.launch {
            val devices = repository.devices.first()
            deviceCount = devices.size
            device = devices.firstOrNull { it.id == deviceId } ?: devices.firstOrNull()
            val cached = device?.let { StateCache.get(it.id) }
            hostVersion = cached?.version
            sessionCount = cached?.sessions?.size ?: 0
        }
    }

    fun chooseTheme(mode: ThemeMode) {
        theme = mode
        viewModelScope.launch { Prefs.setTheme(context, mode) }
    }

    fun toggleNotifyDone(enabled: Boolean) {
        notifyDone = enabled
        viewModelScope.launch { Prefs.setNotifyDone(context, enabled) }
    }

    fun removeDevice(id: String) {
        viewModelScope.launch { repository.remove(id) }
    }
}
