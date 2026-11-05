package dev.pideck.nativeapp.watch

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「点通知要跳进哪个会话」的交接台。
 *
 * 通知是 [RunWatchService] 发的，导航在 Compose 里做，两边不共享 ViewModel：
 * `MainActivity` 在 `onCreate` / `onNewIntent` 里把 Intent 里的目标丢进来，
 * `AppNav` 取走 —— 冷启动用 [take] 直接把它当起点（不闪一下首页），
 * 热启动靠 [flow] 收到就跳。
 */
object NotificationNav {

    /** 通知 Intent 里带的两个 extra（值必须与 [Notifications] 里写下的键一致）。 */
    const val EXTRA_DEVICE_ID = "pideckDeviceId"
    const val EXTRA_SESSION_ID = "pideckSessionId"

    data class Target(val deviceId: String, val sessionId: String)

    /**
     * 同一会话复用同一个 id，避免堆一屏。
     *
     * 必须带上 deviceId：只用 sessionId 会让两台设备的同名会话（克隆过的工作区很常见）
     * 互相覆盖，点回来还会开错机器。
     */
    fun notificationId(deviceId: String, sessionId: String): Int =
        "$deviceId|$sessionId".hashCode()

    private val _flow = MutableStateFlow<Target?>(null)
    val flow: StateFlow<Target?> = _flow.asStateFlow()

    fun post(target: Target) {
        _flow.value = target
    }

    /** 取走并清空：同一份 Intent 在 Activity 重建时不该再跳一次。 */
    fun take(): Target? = _flow.value.also { _flow.value = null }

    fun clear() {
        _flow.value = null
    }
}
