package dev.pideck.nativeapp.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 统计行（首字时间 / tok/s）的口径与格式。
 *
 * 口径对齐桌面端 `out/main/index.js` 的 perf 结算：
 * `ttftMs = 首字 − 请求发出`、`totalMs = 终态 − 请求发出`、
 * `tps = outputTokens ÷ (total − ttft)`、`endToEndTps = outputTokens ÷ total`。
 */
class TurnStatsTest {

    private fun clock(startedAt: Long, firstContentAt: Long, endedAt: Long) =
        TurnClock(startedAt = startedAt, firstContentAt = firstContentAt, endedAt = endedAt)

    private fun meta(json: String): JsonObject =
        Json.parseToJsonElement(json) as JsonObject

    @Test
    fun `本机跑的回合：首字、总耗时、两档速度都算出来`() {
        // 1.0s 首字，5.0s 收尾 → 生成耗时 4.0s；1200 tok → 300 tok/s 与 240 tok/s
        val stats = deriveTurnStats(
            clock = clock(1_000L, 2_000L, 6_000L),
            outputTokens = 1_200,
        )
        assertEquals(1_000L, stats.ttftMs)
        assertEquals(5_000L, stats.totalMs)
        assertEquals(1_200, stats.outputTokens)
        assertEquals(300.0, stats.tps!!, 0.001)
        assertEquals(240.0, stats.endToEndTps!!, 0.001)
    }

    @Test
    fun `翻历史：没有秒表就用 meta 的 durationMs，只报端到端`() {
        val stats = deriveTurnStats(clock = null, outputTokens = 500, metaTotalMs = 10_000L)
        assertNull(stats.ttftMs)
        assertEquals(10_000L, stats.totalMs)
        assertEquals(50.0, stats.endToEndTps!!, 0.001)
        assertNull("没有首字时刻就不该报生成速度", stats.tps)
    }

    @Test
    fun `acp 那种什么都不报的回合整行不渲染`() {
        val stats = turnStatsFromMeta(meta = null, clock = null)
        assertTrue(stats.isEmpty)
        assertNull(formatTurnStats(stats))
    }

    @Test
    fun `pi 的 meta 带 durationMs 与 usage`() {
        val stats = turnStatsFromMeta(
            meta(
                """{"usage":{"inputTokens":347,"outputTokens":483,"cacheReadTokens":81792},
                   "durationMs":4311,"startedAt":1792241907880,"endedAt":1792241912191}""",
            ),
            clock = null,
        )
        assertEquals(483, stats.outputTokens)
        assertEquals(4_311L, stats.totalMs)
        assertEquals(112.0, stats.endToEndTps!!, 0.5)
    }

    @Test
    fun `dsh 的 meta 只有 usage —— 有 tokens 没耗时`() {
        val stats = turnStatsFromMeta(
            meta("""{"usage":{"inputTokens":10,"outputTokens":90,"cacheReadTokens":0}}"""),
            clock = null,
        )
        assertEquals(90, stats.outputTokens)
        assertNull(stats.totalMs)
        assertNull(stats.tps)
        assertEquals("90 tok", formatTurnStats(stats))
    }

    @Test
    fun `流式期间只报首字等待`() {
        val stats = TurnStats(ttftMs = 1_240L)
        assertEquals("首字 1.2s · 生成中…", formatTurnStats(stats, streaming = true))
        // 收尾式调用时没有 tokens / 速度，只报首字
        assertEquals("首字 1.2s", formatTurnStats(stats))
    }

    @Test
    fun `完整文案`() {
        val stats = TurnStats(
            ttftMs = 1_000L,
            totalMs = 5_000L,
            outputTokens = 1_200,
            tps = 300.0,
            endToEndTps = 240.0,
        )
        assertEquals(
            "首字 1.0s · 1200 tok · 生成 300 tok/s · 全程 240 tok/s",
            formatTurnStats(stats),
        )
    }

    @Test
    fun `毫秒与长耗时各用各的单位`() {
        assertEquals("首字 640 ms", formatTurnStats(TurnStats(ttftMs = 640L)))
        assertEquals("首字 12s", formatTurnStats(TurnStats(ttftMs = 12_300L)))
        // 一位小数的速度
        assertEquals(
            "96 tok · 生成 9.6 tok/s",
            formatTurnStats(TurnStats(outputTokens = 96, tps = 9.55)),
        )
    }

    @Test
    fun `首字时刻晚于收尾时被夹到总耗时内，不出现负的生成耗时`() {
        // 契约：coerceIn(0, total)，绝不产出负的 decodeMs（否则 tps 变负、展示成笑话）
        val stats = deriveTurnStats(clock = clock(1_000L, 9_000L, 4_000L), outputTokens = 100)
        assertEquals(3_000L, stats.totalMs)
        assertEquals(3_000L, stats.ttftMs)
        assertNull(stats.tps)
    }
}
