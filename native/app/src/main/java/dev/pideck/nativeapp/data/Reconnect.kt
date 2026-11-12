package dev.pideck.nativeapp.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** 首轮失败后先等这么久再重连（别把宿主打爆）。 */
internal const val RECONNECT_FIRST_DELAY_MS = 1_500L

/** 退避的起点。 */
internal const val RECONNECT_BACKOFF_START_MS = 3_000L

/** 退避的上限：到这一步说明网络确实不通，15 秒一次足够发现恢复。 */
internal const val RECONNECT_BACKOFF_MAX_MS = 15_000L

/**
 * 这一轮该等多久：第一次失败只歇 1.5 秒，之后按上一轮的退避值。
 *
 * @param backoff 上一轮算出来的退避，首轮传 0
 */
internal fun reconnectDelay(backoff: Long): Long =
    if (backoff == 0L) RECONNECT_FIRST_DELAY_MS else backoff

/**
 * 下一轮的退避：连错一次就 3 秒，之后翻倍封顶 15 秒。
 *
 * @param failures 连续失败轮数（收到帧时由调用方归零）
 */
internal fun nextBackoff(failures: Int, current: Long): Long =
    if (failures <= 1) RECONNECT_BACKOFF_START_MS else (current * 2).coerceAtMost(RECONNECT_BACKOFF_MAX_MS)

/**
 * 「断了就退避重连」的通用外壳 —— 聊天页看护流与会话列表 `/api/events` 共用一份。
 *
 * 以前这两处各抄一遍同样的循环（连 `1_500 → 3_000 → 15_000` 的常量都一样），改一处忘另一处时
 * 表现是「列表会重连、聊天页不会」。判定顺序也和 `attempt` 一致：**取消照常上抛**，
 * 其它异常算一轮失败，交给 [onFailure] 决定要不要提示。
 *
 * `connect` 返回 null 表示「这个设备没了，别再重连」（调用方通常在那边收尾自己的 Job 字段）。
 *
 * @param connect 每轮重新取一次连接（令牌可能被重配，设备可能被删）
 * @param stream 连上之后阻塞收流，正常结束也算一轮失败（服务端关了连接）；
 *   每收到一帧要调一次第二个参数的 `onFrame`，否则退避永远不归零（一次抖动就直接跳 15 秒）
 * @param onFailure 失败轮数 + 原因；连续计数由这里维护
 */
suspend fun reconnectingStream(
    connect: suspend () -> Connection?,
    stream: suspend (Connection, onFrame: () -> Unit) -> Unit,
    onFailure: (failures: Int, cause: Throwable) -> Unit = { _, _ -> },
) {
    var backoff = 0L
    var failures = 0
    while (true) {
        val current = connect() ?: return
        var failure: Throwable? = null
        try {
            stream(current) { failures = 0 }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            failure = e
        }
        failures += 1
        failure?.let { onFailure(failures, it) }
        delay(reconnectDelay(backoff))
        backoff = nextBackoff(failures, backoff)
    }
}
