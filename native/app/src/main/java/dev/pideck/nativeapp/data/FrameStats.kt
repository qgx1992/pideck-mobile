package dev.pideck.nativeapp.data

import android.view.Choreographer

/**
 * 帧率计算（纯函数，便于单测）。
 *
 * 输入是相邻两帧的间隔（毫秒）。`FPS` 取最近窗口的平均，`1% Low` 取最差的 1% 帧
 * （把间隔从大到小排，取前 max(1, n/100) 条求平均再倒数）—— 与 PC 上那些
 * 「1% Low FPS」的口径一致：它反映卡顿，而不是总体流畅度。
 */
object FrameRateMath {

    /** 平均帧率；间隔为空或全 0 时返回 0。 */
    fun fps(intervalsMs: LongArray): Int {
        val valid = intervalsMs.filter { it > 0 }
        if (valid.isEmpty()) return 0
        val mean = valid.sum().toDouble() / valid.size
        return (1_000.0 / mean).toInt()
    }

    /** 1% Low：最差的那 1% 帧对应的帧率（间隔越大帧率越低）。 */
    fun onePercentLow(intervalsMs: LongArray): Int {
        val valid = intervalsMs.filter { it > 0 }.sortedDescending()
        if (valid.isEmpty()) return 0
        val count = maxOf(1, valid.size / 100)
        val worst = valid.take(count).sum().toDouble() / count
        return (1_000.0 / worst).toInt()
    }
}

/**
 * 逐帧采样器：`Choreographer` 的 postFrameCallback 递归注册，每 [windowMs] 汇总一次。
 *
 * 只在性能浮层打开时才启动（每帧一次回调，虽然很轻，但没必要常开）。
 */
internal class FrameSampler(private val windowMs: Long = 500L) {

    private val intervals = ArrayDeque<Long>()
    private var lastFrameAt = 0L
    private var lastReportAt = 0L
    private var running = false

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val now = frameTimeNanos / 1_000_000
            if (lastFrameAt > 0L) {
                val delta = now - lastFrameAt
                // 丢帧严重时可能一次跳几百毫秒：照收，它正是「1% Low」要看的东西。
                if (delta in 1..2_000) {
                    intervals.addLast(delta)
                    while (intervals.size > MAX_SAMPLES) intervals.removeFirst()
                }
            }
            lastFrameAt = now
            if (lastReportAt == 0L) lastReportAt = now
            if (now - lastReportAt >= windowMs) {
                lastReportAt = now
                val snapshot = LongArray(intervals.size) { intervals[it] }
                onReport?.invoke(FrameRateMath.fps(snapshot), FrameRateMath.onePercentLow(snapshot))
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private var onReport: ((Int, Int) -> Unit)? = null

    fun start(onReport: (fps: Int, onePercentLow: Int) -> Unit) {
        if (running) return
        this.onReport = onReport
        running = true
        lastFrameAt = 0L
        lastReportAt = 0L
        intervals.clear()
        Choreographer.getInstance().postFrameCallback(callback)
    }

    fun stop() {
        running = false
        onReport = null
        Choreographer.getInstance().removeFrameCallback(callback)
        intervals.clear()
    }

    private companion object {
        /** 500ms 汇总一次，约 30 帧；留 240 条够「1%」有 2 个样本。 */
        const val MAX_SAMPLES = 240
    }
}
