package dev.pideck.nativeapp.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pideck.nativeapp.data.AuthNotice
import dev.pideck.nativeapp.data.DeviceRepository
import dev.pideck.nativeapp.data.SessionStateStore
import dev.pideck.nativeapp.data.Prefs
import dev.pideck.nativeapp.data.PideckException
import dev.pideck.nativeapp.data.StateFetch
import dev.pideck.nativeapp.data.UpdateCenter
import dev.pideck.nativeapp.data.PideckApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    private val repository: DeviceRepository,
    private val api: PideckApi,
    private val stateStore: SessionStateStore,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    /** null = 还在读盘，先别渲染导航图（启动动画盖着）。 */
    var startRoute by mutableStateOf<String?>(null)
        private set

    init {
        // 启动时查一次新版：不阻塞落地、失败静默（结果放 UpdateCenter，首页横条与设置页各自读）。
        // 与「落在哪一页」完全无关，所以先起协程再决定路由。
        viewModelScope.launch {
            Prefs.awaitLoaded()
            if (Prefs.autoCheckUpdate) UpdateCenter.check()
        }
        viewModelScope.launch {
            // 同 ConnectionResolver 的理由：读盘异常 / 迟迟不吐时不能把启动页卡死在这里
            val devices = withTimeoutOrNull(3_000) {
                runCatching { repository.devices.first() }.getOrDefault(emptyList())
            } ?: emptyList()
            val remembered = runCatching { Prefs.lastSession(context) }.getOrNull()?.first
            val device = devices.firstOrNull { it.id == remembered } ?: devices.firstOrNull()
            if (device == null) {
                startRoute = Routes.DEVICES
                return@launch
            }
            // 令牌读不出来（Keystore 主密钥被重建 / 换签名重装）＝ 本机的授权已经没了，
            // 这不是网络问题，验也没得验：直接落设备页让他重配。
            if (repository.tokenReadFailed) {
                AuthNotice.show(AuthNotice.TOKEN_LOST)
                startRoute = Routes.DEVICES
                return@launch
            }
            // 冷启动先验一次授权：令牌被撤销的话，进首页只会是一屏红字，退出重进还是一样。
            // 验不过（401/403）就落到设备页，并把「授权失效」写成常驻提示 —— 用户才知道要重新配对。
            when (probe(device)) {
                Probe.Unauthorized -> {
                    AuthNotice.show()
                    startRoute = Routes.DEVICES
                }
                // 连不上（桌面端没开、不在同一网络）：照样进首页，那儿有更具体的连接报错
                else -> startRoute = Routes.home(device.id)
            }
        }
    }

    private enum class Probe { Ok, Unauthorized, Unreachable }

    /** 一次 `/api/state`：只关心「授权还对不对」。3 秒拿不到就按连不上处理，不拖慢启动。 */
    private suspend fun probe(device: dev.pideck.nativeapp.data.Device): Probe {
        val token = runCatching { repository.tokenOf(device.id) }.getOrNull()
        val result = withTimeoutOrNull(3_000) {
            runCatching { api.state(device, token) }
        } ?: return Probe.Unreachable
        val error = result.exceptionOrNull()
        if (error is PideckException && (error.code == 401 || error.code == 403)) return Probe.Unauthorized
        if (result.isSuccess) {
            val fresh = result.getOrNull()
            if (fresh is StateFetch.Fresh) {
                stateStore.put(device.id, fresh.state)
            }
            AuthNotice.clear()
        }
        return Probe.Ok
    }
}
