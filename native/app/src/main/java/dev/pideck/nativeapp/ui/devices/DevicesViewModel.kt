package dev.pideck.nativeapp.ui.devices

import kotlinx.coroutines.CancellationException
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
                val parsed = PairingLink.parse(link) ?: throw PideckException(
                    if (PairingLink.looksLikeToken(link)) {
                        "这串看着是令牌，不是地址。地址填 http://<电脑IP>:8765（比如 http://10.10.0.172:8765），令牌粘到下面那格。"
                    } else {
                        "这个地址看不懂。要的是 http://<电脑IP>:8765/?token=<令牌> 这一整条" +
                            "（桌面端「设置 → 连接面板」里可以直接复制，含 ?token= 那段）。"
                    },
                )
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
                if (e is CancellationException) throw e

                error = describeConnectionError(e)
            } finally {
                busy = false
            }
        }
    }

    fun rename(deviceId: String, name: String) {
        viewModelScope.launch { repository.rename(deviceId, name) }
    }

    fun remove(deviceId: String) {
        viewModelScope.launch { repository.remove(deviceId) }
    }
}
