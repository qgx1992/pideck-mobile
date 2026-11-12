package dev.pideck.nativeapp.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.math.roundToInt

/**
 * 缓存命中率。
 *
 * 口径与桌面端 `getSessionCacheHitStats`（以及 pi CLI footer 的 `latestCacheHitRate`）一致：
 *
 *     cacheRead / (input + cacheRead + cacheWrite) × 100
 *
 * 注意分母里三个字段都要算：`inputTokens` 只是**未命中**的部分，命中部分落在 `cacheReadTokens`，
 * 写缓存落在 `cacheWriteTokens`。只除 input 会算出大于 100% 的怪数。
 *
 * 数据来自每条 assistant 消息已落盘的 `meta.usage`，不需要运行时、也不需要流式数据。
 * `pi` 三个字段都报；`dsh` 通常只有 usage 里的一部分；`acp` 的 meta 是 null —— 都没有时整项不显示。
 */
data class CacheStat(
    /** 最近一条带 usage 的 assistant 消息的命中率。 */
    val latestPercent: Int? = null,
    /** 该会话全部 assistant 消息命中率的**简单平均**（与桌面 `average` 同口径，不按 token 加权）。 */
    val averagePercent: Int? = null,
    /** 参与平均的轮数。 */
    val samples: Int = 0,
) {
    val known: Boolean get() = latestPercent != null
}

/** 单条 assistant 消息的命中率（0..100）。字段缺失、或分母为 0（没有输入也没有缓存读写）时返回 null。 */
fun cacheHitOf(meta: JsonObject?): Double? {
    val usage = attempt { meta?.get("usage")?.jsonObject }.getOrNull() ?: return null

    fun tokens(key: String): Double =
        (usage[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0.0 } ?: 0.0

    val read = tokens("cacheReadTokens")
    val input = tokens("inputTokens")
    val write = tokens("cacheWriteTokens")
    val denominator = input + read + write
    if (denominator <= 0.0) return null
    return read / denominator * 100.0
}

/**
 * 翻页累加器。
 *
 * 分成逐页喂入而不是一次性传整个列表，是因为「最近一轮」必须取**最新一页**的最后一条 ——
 * 历史接口从新到旧翻页，把各页拼成一个数组后，末尾其实是会话最早的那条。
 */
class CacheStatBuilder {
    private val rates = mutableListOf<Double>()
    private var latest: Double? = null

    fun addPage(messages: List<ChatMessage>) {
        var pageLatest: Double? = null
        for (message in messages) {
            if (message.role != "assistant") continue
            val rate = cacheHitOf(message.meta) ?: continue
            rates += rate
            pageLatest = rate
        }
        if (latest == null) latest = pageLatest
    }

    fun build(): CacheStat {
        if (rates.isEmpty()) return CacheStat()
        return CacheStat(
            latestPercent = latest?.roundToInt()?.coerceIn(0, 100),
            averagePercent = (rates.sum() / rates.size).roundToInt().coerceIn(0, 100),
            samples = rates.size,
        )
    }
}

/** 单页场景的便捷入口（测试与「只有一页历史」的写法用它，等价于只 `addPage` 一次）。 */
fun cacheStatFrom(messages: List<ChatMessage>): CacheStat =
    CacheStatBuilder().apply { addPage(messages) }.build()
