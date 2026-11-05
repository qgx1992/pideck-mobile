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
 * 口径参照桌面端 `out/main/index.js` 的 perf 结算：首字起点是 **本端发出 `/api/chat` 那一刻**
 * （≈ 宿主的 `startedAt`），生成速度只算「首字 → 收尾」那段。
 */
class TurnStatsTest {

    private fun clock(
        startedAt: Long,
        firstContentAt: Long,
        endedAt: Long,
        requestSentAt: Long = 0L,
        firstDeltaAt: Long = 0L,
    ) = TurnClock(
        startedAt = startedAt,
        requestSentAt = requestSentAt,
        firstContentAt = firstContentAt,
        firstDeltaAt = firstDeltaAt,
        endedAt = endedAt,
    )

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
    fun `首字从请求发出那一刻起算，按下后拼请求的本地耗时不计入`() {
        // 按下 1.0s → 请求发出 3.0s（本地拼 payload 2s）→ 首字 4.0s → 收尾 9.0s；500 tok
        val stats = deriveTurnStats(
            clock = clock(1_000L, 4_000L, 9_000L, requestSentAt = 3_000L),
            outputTokens = 500,
        )
        assertEquals("首字 = 首字 − 请求发出", 1_000L, stats.ttftMs)
        assertEquals("全程仍是按下到结束", 8_000L, stats.totalMs)
        assertEquals(100.0, stats.tps!!, 0.001)          // 500 ÷ 5.0s
        assertEquals(62.5, stats.endToEndTps!!, 0.001)   // 500 ÷ 8.0s
    }

    @Test
    fun `首字 + 解码 ≈ 全程（与桌面端同口径，四个数能相加吻合）`() {
        // 实测那一轮：宿主 12.036s、1476 tok、首字 2.7s，但宿主排队 2.4s 要算进首字
        val sent = 1_000L
        val content = sent + 5_100L      // 首字 5.1s = 排队 2.4 + 模型 2.7
        val ended = sent + 12_036L
        val stats = deriveTurnStats(
            clock = clock(sent, content, ended, requestSentAt = sent),
            outputTokens = 1_476,
        )
        assertEquals(5_100L, stats.ttftMs)
        assertEquals(12_036L, stats.totalMs)
        assertEquals(212.8, stats.tps!!, 0.1)            // 1476 ÷ 6.936s
        assertEquals(122.6, stats.endToEndTps!!, 0.1)    // 1476 ÷ 12.036s
        assertEquals("首字 + 解码 = 全程", stats.totalMs, stats.ttftMs!! + (ended - content))
    }

    @Test
    fun `没有请求发出时刻时退回按下发送`() {
        val stats = deriveTurnStats(clock = clock(1_000L, 4_000L, 6_000L), outputTokens = 100)
        assertEquals(3_000L, stats.ttftMs)
    }

    @Test
    fun `生成速度的分母从首个增量（含思考）起算，与桌面流式同口径`() {
        // 实测那一轮：请求 1.0s 发出 → 思考首帧 3.5s → 正文首帧 4.0s → 收尾 9.0s；1000 tok
        // 桌面报「流式」用 firstDeltaAt（3.5s），报「首 token」用 firstTextAt（4.0s）
        val stats = deriveTurnStats(
            clock = clock(1_000L, 4_000L, 9_000L, requestSentAt = 1_000L, firstDeltaAt = 3_500L),
            outputTokens = 1_000,
        )
        assertEquals("首字算到正文，不是思考", 3_000L, stats.ttftMs)
        assertEquals(181.8, stats.tps!!, 0.1)          // 1000 ÷ 5.5s
        assertEquals(125.0, stats.endToEndTps!!, 0.001) // 1000 ÷ 8.0s
    }

    @Test
    fun `没有首个增量时刻时用正文起点算生成速度`() {
        val stats = deriveTurnStats(
            clock = clock(1_000L, 4_000L, 9_000L, requestSentAt = 1_000L),
            outputTokens = 500,
        )
        assertEquals(100.0, stats.tps!!, 0.001) // 500 ÷ 5.0s
    }

    @Test
    fun `直播估算：中文按 0_9 token 每字、其余按 0_17`() {
        // 2 个汉字 = 1.8，10 个半角 = 1.7，合计 3.5 → 四舍五入 4
        assertEquals(4, estimateOutputTokens(listOf(ReplyPart.Text("你好abcdefghij"))))
        // 空内容不报数（否则直播里会出现 ≈0 tok）
        assertEquals(0, estimateOutputTokens(listOf(ReplyPart.Text(""))))
        assertEquals(0, estimateOutputTokens(emptyList()))
    }

    @Test
    fun `直播估算：思考与工具入参也算输出 tokens`() {
        val parts = listOf(
            ReplyPart.Reasoning("先想一下", done = true),
            ReplyPart.Text("答案"),
            ReplyPart.Tool("t1", "bash", "input-available", input = kotlinx.serialization.json.JsonPrimitive("abcdefghij")),
        )
        val estimated = estimateOutputTokens(parts)
        // 4 汉字≈3.6 + 2 汉字≈1.8 + 12 个带引号的半角字符≈2.04 → 7.44 → 7
        assertEquals(7, estimated)
    }

    @Test
    fun `直播文案：带 ≈ 且四段都出，无估算值时退回生成中`() {
        val live = TurnStats(
            ttftMs = 2_800L,
            totalMs = 6_000L,
            outputTokens = 640,
            tps = 152.0,
            endToEndTps = 96.0,
            estimated = true,
        )
        assertEquals(
            "首字 2.8s · ≈640 tok · 生成 152 tok/s · 全程 96 tok/s",
            formatTurnStats(live, streaming = true),
        )
        assertEquals(
            "首字 2.8s · 生成中…",
            formatTurnStats(TurnStats(ttftMs = 2_800L, totalMs = 6_000L), streaming = true),
        )
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
    fun `acp 那种什么都没有的回合也留一行占位，不隐藏`() {
        val stats = turnStatsFromMeta(meta = null, clock = null)
        assertTrue(stats.isEmpty)
        assertEquals("首字 — · — tok · 生成 — tok/s · 全程 — tok/s", formatTurnStats(stats))
        assertEquals("统计行本身为空也不该消失", "首字 — · — tok · 生成 — tok/s · 全程 — tok/s", formatTurnStats(null))
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
        assertEquals("首字 — · 90 tok · 生成 — tok/s · 全程 — tok/s", formatTurnStats(stats))
    }

    @Test
    fun `流式期间只报首字等待`() {
        val stats = TurnStats(ttftMs = 1_240L)
        assertEquals("首字 1.2s · 生成中…", formatTurnStats(stats, streaming = true))
        // 收尾式调用时没有 tokens / 速度，缺项用破折号占位
        assertEquals("首字 1.2s · — tok · 生成 — tok/s · 全程 — tok/s", formatTurnStats(stats))
        // 还没收到首帧（ttft 为空）时也在流式态，不能把整行抹掉
        assertEquals("首字 — · 生成中…", formatTurnStats(null, streaming = true))
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
        assertEquals("首字 640 ms · — tok · 生成 — tok/s · 全程 — tok/s", formatTurnStats(TurnStats(ttftMs = 640L)))
        assertEquals("首字 12s · — tok · 生成 — tok/s · 全程 — tok/s", formatTurnStats(TurnStats(ttftMs = 12_300L)))
        // 一位小数的速度
        assertEquals(
            "首字 — · 96 tok · 生成 9.6 tok/s · 全程 — tok/s",
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
