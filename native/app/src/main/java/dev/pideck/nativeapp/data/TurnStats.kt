package dev.pideck.nativeapp.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.util.Locale
import kotlin.math.roundToLong

/**
 * 一轮回复的节奏数据：首字等待、总耗时、输出 tokens、两档速度。
 *
 * 口径照搬桌面端（`out/main/index.js` 的 perf 结算注释）：
 * - `ttftMs` = 正文首个增量到达的时刻 − 请求发出时刻（思考期间没有正文时退回首增量）；
 * - `totalMs` = 终态 − 请求发出时刻；
 * - `tps` = outputTokens ÷ 扣掉首字等待后的生成耗时；
 * - `endToEndTps` = outputTokens ÷ `totalMs`（含首字等待）。
 *
 * 桌面端的这组数**不在消息 meta 里**：主进程在流式过程中现测现算，走
 * `agentsRuntimeState` 边沿推送。手机上同理，只不过秒表由客户端自己打点
 * （[TurnClock]），tokens 数仍旧取自落盘后的 `meta.usage.outputTokens`
 * —— 流里没有 usage（`webExternalStream` 的帧只有 text/reasoning/tool 增量）。
 */
data class TurnStats(
    val ttftMs: Long? = null,
    val totalMs: Long? = null,
    val outputTokens: Int? = null,
    val tps: Double? = null,
    val endToEndTps: Double? = null,
) {
    val isEmpty: Boolean get() = ttftMs == null && totalMs == null && outputTokens == null
}

/**
 * 客户端秒表（epoch 毫秒，0 表示还没发生）。
 *
 * [startedAt] 取「按下发送」那一刻，比宿主的 `startedAt` 早一点点（排队、建连），
 * 与桌面端「请求发出」的口径一致 —— 用户感知的等待就是从按下开始。
 */
data class TurnClock(
    val startedAt: Long = 0L,
    val firstContentAt: Long = 0L,
    val endedAt: Long = 0L,
)

/**
 * 秒表 + tokens 数 → 展示用数据。
 *
 * [clock] 为空（或没有 [TurnClock.endedAt]）表示这一轮不是本机跑完的（翻历史 / 换了设备），
 * 此时只能靠 meta 里的 `durationMs`（只有 pi 后端有）算端到端速度，首字无从得知。
 */
fun deriveTurnStats(clock: TurnClock?, outputTokens: Int?, metaTotalMs: Long? = null): TurnStats {
    val started = clock?.startedAt ?: 0L
    val measured = if (started > 0L && (clock?.endedAt ?: 0L) > started) {
        clock!!.endedAt - started
    } else {
        null
    }
    val total = measured ?: metaTotalMs?.takeIf { it > 0L }
    val ttft = if (started > 0L && (clock?.firstContentAt ?: 0L) > started && total != null) {
        (clock!!.firstContentAt - started).coerceIn(0L, total)
    } else {
        null
    }
    val tokens = outputTokens?.takeIf { it > 0 }
    val decodeMs = if (total != null && ttft != null) (total - ttft).coerceAtLeast(0L) else null
    return TurnStats(
        ttftMs = ttft,
        totalMs = total,
        outputTokens = tokens,
        tps = if (tokens != null && decodeMs != null && decodeMs >= 100L) {
            tokens / (decodeMs / 1000.0)
        } else {
            null
        },
        endToEndTps = if (tokens != null && total != null && total >= 100L) {
            tokens / (total / 1000.0)
        } else {
            null
        },
    )
}

/**
 * 落盘后的 assistant `meta` → tokens 与（pi 才有的）耗时。
 *
 * 形状（实测）：pi = `{usage:{inputTokens,outputTokens,cacheReadTokens,…}, durationMs, startedAt, endedAt}`；
 * dsh = `{usage:{…}}`（**没有任何耗时字段**）；acp = `null`。
 */
fun turnStatsFromMeta(meta: JsonObject?, clock: TurnClock?): TurnStats {
    val usage = runCatching { meta?.get("usage")?.jsonObject }.getOrNull()
    val tokens = runCatching { (usage?.get("outputTokens") as? JsonPrimitive)?.intOrNull }.getOrNull()
    val duration = runCatching {
        (meta?.get("durationMs") as? JsonPrimitive)?.doubleOrNull?.roundToLong()
    }.getOrNull()
    val span = runCatching {
        val start = (meta?.get("startedAt") as? JsonPrimitive)?.doubleOrNull
        val end = (meta?.get("endedAt") as? JsonPrimitive)?.doubleOrNull
        if (start != null && end != null && end > start) (end - start).roundToLong() else null
    }.getOrNull()
    return deriveTurnStats(clock, tokens, duration ?: span)
}

/**
 * 统计行文案；没有可说的数就返回 null（空行会把输入条往下顶）。
 *
 * 示例：`首字 1.2s · 483 tok · 生成 104 tok/s · 全程 24 tok/s`
 */
fun formatTurnStats(stats: TurnStats, streaming: Boolean = false): String? {
    if (streaming) {
        val ttft = stats.ttftMs ?: return null
        return "首字 ${formatMs(ttft)} · 生成中…"
    }
    val parts = mutableListOf<String>()
    stats.ttftMs?.let { parts += "首字 ${formatMs(it)}" }
    stats.outputTokens?.let { parts += "$it tok" }
    stats.tps?.let { parts += "生成 ${formatTps(it)} tok/s" }
    // 已经报了生成速度时，端到端只作补充（两者大小关系反直觉：全程一定更低）。
    stats.endToEndTps?.let {
        parts += if (stats.tps == null) "端到端 ${formatTps(it)} tok/s" else "全程 ${formatTps(it)} tok/s"
    }
    if (parts.isEmpty()) stats.totalMs?.let { parts += formatMs(it) }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

private fun formatMs(ms: Long): String = when {
    ms < 1_000L -> "$ms ms"
    ms < 10_000L -> String.format(Locale.ROOT, "%.1fs", ms / 1000.0)
    else -> "${(ms / 1000.0).roundToLong()}s"
}

private fun formatTps(value: Double): String = when {
    value >= 100.0 -> value.roundToLong().toString()
    value >= 10.0 -> String.format(Locale.ROOT, "%.0f", value)
    else -> String.format(Locale.ROOT, "%.1f", value)
}
