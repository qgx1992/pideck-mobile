package dev.pideck.nativeapp.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 「这台设备 + 它的令牌」。
 *
 * 四个 ViewModel（启动页 / 首页 / 会话列表 / 聊天页）以前各写一遍
 * 「`devices.first { … }` 找到设备 → `tokenOf()` 拿令牌 → 失败就静默」，
 * 于是同一个坑修了四次也没修干净：设备被删掉（或 DataStore 迟迟不吐）时
 * `first { … }` **永远不会返回**，ViewModel 的 `init` 就停在那里 ——
 * 表现是「会话页一直转圈 / 首页空的 / 输入框发不出去」，日志里什么都没有。
 *
 * 现在统一走 [ConnectionResolver]：5 秒拿不到设备就当它不存在，调用方按「回设备页」处理。
 */
data class Connection(val device: Device, val token: String?)

/**
 * [ConnectionResolver] 真正需要的能力就这两条。
 *
 * 抽成接口是为了**能单测**：`DeviceRepository` 要 `android.content.Context`（DataStore +
 * Keystore），在 JVM 单测里造不出来；而「找不到设备 / 没有令牌 / 读盘超时」这三条正是要验的。
 */
interface DeviceDirectory {
    val devices: Flow<List<Device>>

    fun tokenOf(deviceId: String): String?
}

@javax.inject.Singleton
class ConnectionResolver @javax.inject.Inject constructor(
    private val directory: DeviceDirectory,
) {

    companion object {
        /** 读盘（DataStore 首次加载）+ 解密令牌的上限。超过了就当成「这台设备不在了」。 */
        const val DEFAULT_TIMEOUT_MS = 5_000L
    }

    /**
     * @return 设备在 + 至少拿到了设备记录；令牌解不出来时 [Connection.token] 为 null
     *         （调用方照常用 null 令牌发请求 —— 桌面端关了鉴权时这就是正常情况）。
     *         设备不存在 / 超时 → null。
     */
    suspend fun resolve(deviceId: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Connection? {
        // 两个 null 都要当成「没有这台设备」：超时（withTimeoutOrNull）与
        // `first { }` 在流结束仍没匹配时抛的 NoSuchElementException（runCatching 兜住）。
        val found = withTimeoutOrNull(timeoutMs) {
            attempt {
                directory.devices.first { list -> list.any { it.id == deviceId } }
                    .firstOrNull { it.id == deviceId }
            }.getOrNull()
        } ?: return null
        val token = attempt { directory.tokenOf(found.id) }.getOrNull()
        return Connection(found, token)
    }
}
