package dev.pideck.nativeapp.data

/**
 * 流式正文的「渐显」步进。
 *
 * 宿主的 `text-delta` 一条只有 3~4 个字（实测 127 条 / 454 字，p50 间隔 **0ms**、p90 87ms），
 * 也就是说文字是**一阵一阵**到的。直接把它铺到屏幕上，看起来就是「一个字一个字蹦」，
 * 而不是桌面端那种连续流出来的感觉（桌面端自己有一层逐字渐显）。
 *
 * 这里让可见字数按时间匀速追赶：正常情况下 [MIN_RATE] 字/秒；积压超过
 * [CATCH_UP_THRESHOLD] 字就按 [CATCH_UP_RATE] 快放（避免长篇回答结束时还在慢慢挤牙膏）。
 *
 * @param shown 当前已显示的字数
 * @param total 已经收到的总字数
 * @param dtMillis 距上一帧的毫秒数
 * @return 这一帧之后应当显示的字数（永远 <= total）
 */
fun nextReveal(
    shown: Int,
    total: Int,
    dtMillis: Long,
    minRate: Float = 60f,
    catchUpThreshold: Int = 160,
    catchUpRate: Float = 900f,
): Int {
    if (shown >= total) return total
    if (dtMillis <= 0L) return shown
    val pending = total - shown
    val rate = if (pending > catchUpThreshold) catchUpRate else minRate
    val step = (dtMillis / 1000f * rate).toInt()
    // 再小的 dt 也至少推进 1 个字：否则 60Hz 下每帧 1 字都不到，会永远卡在原地
    val advanced = shown + if (step < 1) 1 else step
    return if (advanced > total) total else advanced
}
