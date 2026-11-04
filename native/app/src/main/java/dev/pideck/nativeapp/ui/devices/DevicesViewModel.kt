package dev.pideck.nativeapp.ui.devices

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pideck.nativeapp.data.Device
import dev.pideck.nativeapp.data.DeviceRepository
import dev.pideck.nativeapp.data.PairingLink
import dev.pideck.nativeapp.data.PideckApi
import dev.pideck.nativeapp.data.PideckException
import dev.pideck.nativeapp.data.describeConnectionError
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DevicesViewModel @Inject constructor(
    private val repository: DeviceRepository,
    private val api: PideckApi,
) : ViewModel() {

    val devices = repository.devices.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    var busy by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    fun clearError() {
        error = null
    }

    /**
     * 添加设备：先真的连一次 `/api/state`，连得上才落盘。
     * 配对这件事在本机就有确定答案，不会出现「加进去了但一直是空的」。
     */
    fun add(link: String, token: String, onAdded: (Device) -> Unit) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            error = null
            try {
                val parsed = PairingLink.parse(link) ?: throw PideckException("看不懂这个地址：$link")
                val effectiveToken = PairingLink.mergeToken(token, parsed.token)
                val draft = Device(
                    id = "",
                    name = parsed.label ?: parsed.baseUrl.removePrefix("https://").removePrefix("http://"),
                    baseUrl = parsed.baseUrl,
                )
                api.state(draft, effectiveToken)
                val saved = repository.upsert(draft, effectiveToken)
                onAdded(saved)
            } catch (e: Exception) {
                error = describeConnectionError(e)
            } finally {
                busy = false
            }
        }
    }

    fun remove(deviceId: String) {
        viewModelScope.launch { repository.remove(deviceId) }
    }
}
