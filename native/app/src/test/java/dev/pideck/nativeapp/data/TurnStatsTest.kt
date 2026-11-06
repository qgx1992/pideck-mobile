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
    fun `命中率放在行尾，与上下文弹层同一口径`() {
        val stats = TurnStats(
            ttftMs = 2_800L,
            totalMs = 6_000L,
            outputTokens = 640,
            tps = 152.0,
            endToEndTps = 96.0,
            averageTps = 197.4,
            cacheHit = 99.44,
        )
        assertEquals(
            "首字 2.8s · 流式 152 tok/s · 匀速 197 tok/s · 命中 99%",
            formatTurnStats(stats),
        )
        // 小数位只给小数值（0.4% 那种），整数部分不四舍五入成 0%
        assertEquals(
            "首字 — · 流式 — tok/s · 匀速 — tok/s · 命中 0.4%",
            formatTurnStats(TurnStats(cacheHit = 0.42)),
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
        assertEquals("首字 — · 流式 — tok/s · 匀速 — tok/s · 命中 —", formatTurnStats(stats))
        assertEquals("统计行本身为空也不该消失", "首字 — · 流式 — tok/s · 匀速 — tok/s · 命中 —", formatTurnStats(null))
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
        assertEquals("首字 — · 流式 — tok/s · 匀速 — tok/s · 命中 —", formatTurnStats(stats))
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
            "首字 1.0s · 流式 300 tok/s · 匀速 — tok/s · 命中 —",
            formatTurnStats(stats),
        )
    }

    @Test
    fun `毫秒与长耗时各用各的单位`() {
        assertEquals("首字 640 ms · 流式 — tok/s · 匀速 — tok/s · 命中 —", formatTurnStats(TurnStats(ttftMs = 640L)))
        assertEquals("首字 12s · 流式 — tok/s · 匀速 — tok/s · 命中 —", formatTurnStats(TurnStats(ttftMs = 12_300L)))
        // 一位小数的速度
        assertEquals(
            "首字 — · 流式 9.6 tok/s · 匀速 — tok/s · 命中 —",
            formatTurnStats(TurnStats(outputTokens = 96, tps = 9.55)),
        )
    }

    /** 带起止时刻的一条 assistant（翻历史那条路径要靠它算整轮跨度）。 */
    private fun timed(id: String, tokens: Int, start: Long, end: Long) = ChatMessage(
        id = id,
        role = "assistant",
        text = "x",
        meta = meta("""{"usage":{"outputTokens":$tokens},"startedAt":$start,"endedAt":$end}"""),
    )

    @Test
    fun `翻历史时用整轮跨度当总耗时，不拿最后一步的时长`() {
        // 两条 assistant（中间夹一条 tool），整轮 2s → 12s = 10s，token 100+300=400
        val turn = listOf(
            message("u1", "user"),
            timed("a1", 100, 1_000, 3_000),
            message("t1", "tool"),
            timed("a2", 300, 3_100, 12_000),
        )
        val usage = turnUsageOf(turn)
        assertEquals(400, usage?.outputTokens)
        assertEquals(11_000L, usage?.spanMs)
        val stats = turnStatsFromMeta(
            meta = null,
            clock = null,
            host = null,
            tokens = usage?.outputTokens,
            turnSpanMs = usage?.spanMs,
        )
        assertEquals(11_000L, stats.totalMs)
        assertEquals(400, stats.outputTokens)
        assertEquals(36.36, stats.endToEndTps!!, 0.02)
        assertNull(stats.ttftMs)
    }

    private fun message(id: String, role: String, tokens: Int? = null) = ChatMessage(
        id = id,
        role = role,
        text = "x",
        meta = tokens?.let { meta("""{"usage":{"outputTokens":$it}}""") },
    )

    @Test
    fun `整轮的 token 数要把每一步加起来`() {
        // 带工具的一轮 = 多条 assistant 消息（每步一条）。只取最后一条会把 350 显示成 50。
        val turn = listOf(
            message("u1", "user"),
            message("a1", "assistant", 100),
            message("t1", "tool"),
            message("a2", "assistant", 200),
            message("t2", "tool"),
            message("a3", "assistant", 50),
        )
        val usage = turnUsageOf(turn)
        assertEquals(350, usage?.outputTokens)
        assertEquals(3, usage?.steps)
    }

    @Test
    fun `求和只算最后一轮，不把上一轮算进来`() {
        val messages = listOf(
            message("u1", "user"),
            message("a1", "assistant", 900),   // 上一轮
            message("u2", "user"),
            message("a2", "assistant", 120),
            message("a3", "assistant", 80),
        )
        assertEquals(200, turnUsageOf(messages)?.outputTokens)
    }

    @Test
    fun `翻页拿不到 user 消息时，从最后一个非 assistant 之后算`() {
        val messages = listOf(
            message("t9", "tool"),
            message("a7", "assistant", 40),
            message("a8", "assistant", 60),
        )
        assertEquals(100, turnUsageOf(messages)?.outputTokens)
    }

    @Test
    fun `没有 usage 的时候返回 null，而不是 0`() {
        assertNull(turnUsageOf(listOf(message("u1", "user"), message("a1", "assistant"))))
        assertNull(turnUsageOf(emptyList()))
    }

    @Test
    fun `宿主给了 perf 就以它为准，四个数都和桌面端一致`() {
        // 桌面端 runtime/state 的 perf：ttft 2800 / total 10591 / 全程 117.82 → 反推 1248 tok
        val stats = deriveTurnStats(
            clock = clock(1_000L, 3_000L, 9_000L, requestSentAt = 1_100L),
            outputTokens = 1_248,
            host = HostTurnPerf(ttftMs = 2_800L, totalMs = 10_591L, tps = 150.0, endToEndTps = 117.82, sessionTps = 197.4),
        )
        assertEquals(2_800L, stats.ttftMs)
        assertEquals(10_591L, stats.totalMs)
        assertEquals(1_248, stats.outputTokens)
        assertEquals(117.8, stats.endToEndTps!!, 0.05)
        // 「匀速」用会话级的平均流式速度，不是本轮端到端
        assertEquals(197.4, stats.averageTps!!, 0.001)
    }

    @Test
    fun `宿主 perf 与本地求和冲突时用宿主的，别拼出假速度`() {
        // 带工具的一轮：本端整轮求和 9_400，宿主最后一步只有 881（total 9_689 → 90.9 tok/s）
        val stats = deriveTurnStats(
            clock = clock(1_000L, 3_000L, 12_000L, requestSentAt = 1_100L),
            outputTokens = 9_400,
            host = HostTurnPerf(ttftMs = 2_000L, totalMs = 9_689L, tps = 120.0, endToEndTps = 90.93),
        )
        assertEquals(881, stats.outputTokens)
        assertEquals(90.93, stats.endToEndTps!!, 0.05)
        assertEquals(2_000L, stats.ttftMs)
    }

    @Test
    fun `宿主 perf 解析：四件套齐全才算有，空对象返回 null`() {
        val perf = parseHostTurnPerf(meta("""{"ttftMs":2800,"totalMs":10591,"tps":150.2,"endToEndTps":117.8,"sessionTps":197.4,"perfAt":1793953850056}"""))
        assertEquals(2_800L, perf?.ttftMs)
        assertEquals(10_591L, perf?.totalMs)
        assertEquals(150.2, perf!!.tps!!, 0.001)
        assertEquals(197.4, perf.sessionTps!!, 0.001)
        assertEquals(1793953850056L, perf.at)
        assertNull(parseHostTurnPerf(meta("""{}""")))
        assertNull(parseHostTurnPerf(null))
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
