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
 * 口径参照桌面端（`out/main/index.js` 的 perf 结算注释），两处刻意不同（见 [TurnClock]）：
 * - `ttftMs` = 正文首个增量到达 − **本端 `/api/chat` 请求发出时刻**（思考期间没有正文时退回首增量）；
 * - `totalMs` = 宿主结算的 `durationMs`，没有就用「按下发送 → 收尾」；
 * - `tps` = outputTokens ÷ 首字 → 收尾；
 * - `endToEndTps` = outputTokens ÷ `totalMs`（含首字等待与发出前的排队）。
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
 * 桌面端的秒表起在宿主 `sendPrompt` 那一刻（`notePromptRequested`），手机没有那个时刻，
 * 只能用「本端 `/api/chat` 请求发出」当代理 —— 两者相差通常几十毫秒（实测 27ms），
 * 所以两端口径能对上、四个数也能相加吻合（首字 + 解码 ≈ 全程）。
 * - [startedAt]：按下发送（只用于「全程」的兜底，以及没有 [requestSentAt] 时的退路）；
 * - [requestSentAt]：`/api/chat` 请求**发出**的时刻 —— 首字的计时起点；
 * - [firstContentAt]：第一个**正文**增量的到达时刻（首字的分母，与桌面 `firstTextAt ?: firstDeltaAt` 同口径）；
 * - [firstDeltaAt]：第一个**任意**增量的到达时刻（含思考）—— 生成速度的分母，与桌面 `firstDeltaAt` 同口径。
 *   两分开是因为思考会先行几十到几百毫秒：桌面 `流式` 从思考算起，若这里也用正文，
 *   同一条回复会比桌面快 5~10%（实测 161 vs 150）。
 *
 * 曾经把起点定在「SSE 首帧到达」，结果把宿主收到请求后的排队（实测 2.4s）从首字里剔了出去：
 * 手机显示 2.7s 而桌面显示 5.1s，同一轮对不上。现在改回请求发出时刻。
 */
data class TurnClock(
    val startedAt: Long = 0L,
    val requestSentAt: Long = 0L,
    val firstContentAt: Long = 0L,
    val firstDeltaAt: Long = 0L,
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
    val ended = clock?.endedAt ?: 0L
    val content = clock?.firstContentAt ?: 0L
    // 首字起点优先用「请求发出」那一刻（≈ 宿主的 startedAt），没有才退回按下发送。
    val requestAt = clock?.requestSentAt ?: 0L
    val base = if (requestAt > 0L) requestAt else started
    val measured = if (started > 0L && ended > started) ended - started else null
    val total = metaTotalMs?.takeIf { it > 0L } ?: measured
    val ttft = if (base > 0L && content > base && total != null) {
        (content - base).coerceIn(0L, total)
    } else {
        null
    }
    val tokens = outputTokens?.takeIf { it > 0 }
    // 生成速度只看「已经出字之后」那段，别把排队/建连/首字等待稀释进去。
    // 起点用首个任意增量（含思考）：桌面端 `firstDeltaAt` 就是这么算的，用正文字会快 5~10%。
    val delta = clock?.firstDeltaAt ?: 0L
    val decodeStart = if (delta > 0L) delta else content
    val decodeMs = if (decodeStart > 0L && ended > decodeStart) ended - decodeStart else null
    return TurnStats(
        ttftMs = ttft,
        totalMs = total,
        outputTokens = tokens,
        tps = if (tokens != null && decodeMs != null && decodeMs >= 100L && ttft != null) {
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
 * 统计行文案。
 *
 * 示例：`首字 1.2s · 483 tok · 生成 104 tok/s · 全程 24 tok/s`
 *
 * 统计行常驻：缺项用破折号占位，不隐藏。
 *
 * 之前是「没有数就整行不渲染」，代价是这一行忽隐忽现 —— 聊到 acp / 没耗时的回合时它消失，
 * 输入条跟着往上跳一格。现在固定占一行（全空就是 `首字 — · — tok · 生成 — tok/s · 全程 — tok/s`），
 * 位置和高度都不再变。
 */
fun formatTurnStats(stats: TurnStats?, streaming: Boolean = false): String {
    if (streaming) return "首字 ${stats?.ttftMs?.let(::formatMs) ?: "—"} · 生成中…"
    val ttft = stats?.ttftMs?.let(::formatMs) ?: "—"
    val tokens = stats?.outputTokens?.toString() ?: "—"
    val tps = stats?.tps?.let(::formatTps) ?: "—"
    val e2e = stats?.endToEndTps?.let(::formatTps) ?: "—"
    return "首字 $ttft · $tokens tok · 生成 $tps tok/s · 全程 $e2e tok/s"
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
