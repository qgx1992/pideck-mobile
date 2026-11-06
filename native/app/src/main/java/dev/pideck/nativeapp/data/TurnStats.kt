package dev.pideck.nativeapp.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.util.Locale
import kotlin.math.roundToInt
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
    /** 流式直播中的估算值（宿主只在落盘后的 meta 里给真实 usage，流里没有）。 */
    val estimated: Boolean = false,
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

/** 一轮里所有 assistant 步骤的 output tokens 合计。 */
data class TurnUsage(val outputTokens: Int, val steps: Int, val spanMs: Long? = null)

/**
 * 从消息列表里取「本轮」的 output tokens —— **一轮可能有很多步**。
 *
 * 带工具调用的一轮会有好几条 assistant 消息（每步一条：先说一句 → 调工具 → 再说 → 调工具 …），
 * 每条 `meta.usage.outputTokens` 只是**那一步**的量。只取最后一条会把 12k 的一轮显示成 881，
 * 生成速度与全程速度随之一起偏小十几倍（0.5.36 修的就是这个）。
 *
 * 口径：最后一条 user 消息之后的所有 assistant 步骤求和 —— 与桌面端 perf 里
 * `outputTokens`（整轮累计）一致。翻页时可能拿不到那条 user 消息，就从最后一个非 assistant
 * 消息之后开始；两种都没有（整页都是 assistant）才把整页当一轮。
 */
fun turnUsageOf(messages: List<ChatMessage>): TurnUsage? {
    val lastUser = messages.indexOfLast { it.role == "user" }
    val start = if (lastUser >= 0) {
        lastUser + 1
    } else {
        messages.indexOfLast { it.role != "assistant" } + 1
    }
    var tokens = 0
    var steps = 0
    var firstStart = 0L
    var lastEnd = 0L
    for (message in messages.drop(start)) {
        if (message.role != "assistant") continue
        val usage = runCatching { message.meta?.get("usage")?.jsonObject }.getOrNull() ?: continue
        val out = runCatching { (usage["outputTokens"] as? JsonPrimitive)?.intOrNull }.getOrNull() ?: continue
        tokens += out
        steps += 1
        // 整轮的起止：翻历史时没有秒表，拿不到宿主 perf，也要有一个**整轮**的时长，
        // 不能拿最后一步的 durationMs 去当整轮（那会让全程速度凭空翻好几倍）。
        val startAt = runCatching { (message.meta?.get("startedAt") as? JsonPrimitive)?.doubleOrNull }
            .getOrNull()?.toLong() ?: 0L
        val endAt = runCatching { (message.meta?.get("endedAt") as? JsonPrimitive)?.doubleOrNull }
            .getOrNull()?.toLong() ?: 0L
        if (startAt > 0L && (firstStart == 0L || startAt < firstStart)) firstStart = startAt
        if (endAt > lastEnd) lastEnd = endAt
    }
    val span = if (firstStart > 0L && lastEnd > firstStart) lastEnd - firstStart else null
    return if (steps == 0) null else TurnUsage(outputTokens = tokens, steps = steps, spanMs = span)
}

/**
 * 秒表 + tokens 数 → 展示用数据。
 *
 * [clock] 为空（或没有 [TurnClock.endedAt]）表示这一轮不是本机跑完的（翻历史 / 换了设备），
 * 此时只能靠 meta 里的 `durationMs`（只有 pi 后端有）算端到端速度，首字无从得知。
 */
fun deriveTurnStats(
    clock: TurnClock?,
    outputTokens: Int?,
    metaTotalMs: Long? = null,
    host: HostTurnPerf? = null,
): TurnStats {
    val started = clock?.startedAt ?: 0L
    val ended = clock?.endedAt ?: 0L
    val content = clock?.firstContentAt ?: 0L
    // 首字起点优先用「请求发出」那一刻（≈ 宿主的 startedAt），没有才退回按下发送。
    val requestAt = clock?.requestSentAt ?: 0L
    val base = if (requestAt > 0L) requestAt else started
    val measured = if (started > 0L && ended > started) ended - started else null
    // 时间一律优先用宿主自己结算的那份（`runtime/state` 的 perf），它才是桌面端屏幕上那个数；
    // 秒表只是宿主没给时的退路（翻历史、刚启动、dsh 之类）。
    val total = host?.totalMs?.takeIf { it > 0L } ?: metaTotalMs?.takeIf { it > 0L } ?: measured
    val ttft = host?.ttftMs?.takeIf { it >= 0L } ?: if (base > 0L && content > base && total != null) {
        (content - base).coerceIn(0L, total)
    } else {
        null
    }
    // 宿主给了 perf 就以它为准，连 tokens 也从它反推：`e2e = tokens ÷ totalMs` 是它自己算的，
    // 反解出来就是它屏幕上那个数。否则「本端整轮求和 + 宿主单步耗时」会配成一个假的更大速度。
    val hostTokens = host?.let { h ->
        val t = h.totalMs ?: 0L
        val e = h.endToEndTps
        if (t > 0L && e != null) (e * t / 1000.0).roundToInt().takeIf { it > 0 } else null
    }
    val tokens = hostTokens ?: outputTokens?.takeIf { it > 0 }
    // 生成速度只看「已经出字之后」那段，别把排队/建连/首字等待稀释进去。
    // 起点用首个任意增量（含思考）：桌面端 `firstDeltaAt` 就是这么算的，用正文字会快 5~10%。
    val delta = clock?.firstDeltaAt ?: 0L
    val decodeStart = if (delta > 0L) delta else content
    val decodeMs = if (decodeStart > 0L && ended > decodeStart) ended - decodeStart else null
    return TurnStats(
        ttftMs = ttft,
        totalMs = total,
        outputTokens = tokens,
        // 宿主自己算的生成速度优先：它和桌面端屏幕上那个数同源，本端秒表只能算近似。
        tps = host?.tps?.takeIf { it > 0.0 }
            ?: if (tokens != null && decodeMs != null && decodeMs >= 100L && ttft != null) {
                tokens / (decodeMs / 1000.0)
            } else {
                null
            },
        // 分子分母都用同一份 tokens 与同一个 total：宿主给了 total 时，这一行与桌面端同轮同值。
        endToEndTps = if (tokens != null && total != null && total >= 100L) {
            tokens / (total / 1000.0)
        } else {
            host?.endToEndTps
        },
    )
}

/**
 * 落盘后的 assistant `meta` → tokens 与（pi 才有的）耗时。
 *
 * 形状（实测）：pi = `{usage:{inputTokens,outputTokens,cacheReadTokens,…}, durationMs, startedAt, endedAt}`；
 * dsh = `{usage:{…}}`（**没有任何耗时字段**）；acp = `null`。
 */
fun turnStatsFromMeta(
    meta: JsonObject?,
    clock: TurnClock?,
    host: HostTurnPerf? = null,
    tokens: Int? = null,
    turnSpanMs: Long? = null,
): TurnStats {
    val usage = runCatching { meta?.get("usage")?.jsonObject }.getOrNull()
    val tokens = tokens
        ?: runCatching { (usage?.get("outputTokens") as? JsonPrimitive)?.intOrNull }.getOrNull()
    val duration = runCatching {
        (meta?.get("durationMs") as? JsonPrimitive)?.doubleOrNull?.roundToLong()
    }.getOrNull()
    val span = runCatching {
        val start = (meta?.get("startedAt") as? JsonPrimitive)?.doubleOrNull
        val end = (meta?.get("endedAt") as? JsonPrimitive)?.doubleOrNull
        if (start != null && end != null && end > start) (end - start).roundToLong() else null
    }.getOrNull()
    // 有时长的来源排序：整轮跨度（翻历史时唯一可靠的那个）→ 这一步的 durationMs → 这一步的起止
    return deriveTurnStats(clock, tokens, turnSpanMs ?: duration ?: span, host)
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
    if (streaming) {
        val ttft = stats?.ttftMs?.let(::formatMs) ?: "—"
        val tokens = stats?.outputTokens
        val tps = stats?.tps?.let(::formatTps)
        if (tokens == null || tps == null) return "首字 $ttft · 生成中…"
        // 直播：这里的 tokens 是按字数估的（宿主只在落盘后才给真 usage），所以标 ≈。
        val e2e = stats.endToEndTps?.let(::formatTps) ?: "—"
        return "首字 $ttft · ≈$tokens tok · 生成 $tps tok/s · 全程 $e2e tok/s"
    }
    val ttft = stats?.ttftMs?.let(::formatMs) ?: "—"
    val tokens = stats?.outputTokens?.toString() ?: "—"
    val tps = stats?.tps?.let(::formatTps) ?: "—"
    val e2e = stats?.endToEndTps?.let(::formatTps) ?: "—"
    return "首字 $ttft · $tokens tok · 生成 $tps tok/s · 全程 $e2e tok/s"
}

/**
 * 流式期间的 tokens 估算。
 *
 * 流里只有 text/thinking/tool 增量，**没有 usage**（`webExternalStream` 的帧不带），
 * 真值要等落盘后的 `meta.usage.outputTokens`。所以直播期间按字符估：
 * 系数来自本会话 66 条 assistant 消息的最小二乘拟合（CJK ≈0.9 token/字，其余 ≈0.17），
 * 工具调用的入参也是模型自己生成的输出，一并计入。
 *
 * 中位误差约 15%（长英文块、代码块偏差大），所以直播时文案带 `≈`，收尾后换成真值。
 */
fun estimateOutputTokens(parts: List<ReplyPart>): Int {
    var total = 0.0
    for (part in parts) {
        total += when (part) {
            is ReplyPart.Text -> estimateChars(part.text)
            is ReplyPart.Reasoning -> estimateChars(part.text)
            is ReplyPart.Tool -> estimateChars(part.input?.toString().orEmpty())
        }
    }
    return total.roundToInt()
}

private const val CJK_TOKENS_PER_CHAR = 0.9
private const val OTHER_TOKENS_PER_CHAR = 0.17

private fun estimateChars(text: String): Double {
    if (text.isEmpty()) return 0.0
    var cjk = 0
    for (ch in text) if (ch.code in 0x4E00..0x9FFF) cjk++
    val other = text.length - cjk
    return cjk * CJK_TOKENS_PER_CHAR + other * OTHER_TOKENS_PER_CHAR
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
