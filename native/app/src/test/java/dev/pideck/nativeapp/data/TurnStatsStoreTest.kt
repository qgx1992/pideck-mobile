package dev.pideck.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 统计行落盘的读写一致性。
 *
 * 钉住这一条：**[encode] 写出来的列数必须正好是 [decode] 认的列数**。
 * 0.5.36 加 `averageTps` / `cacheHit` 时只改了 decode（要求 8 列），encode 还写 6 列 →
 * 每一行都被 `parts.size != 8` 吞掉，表现是「重启后统计行全部消失、还不报错」。
 */
class TurnStatsStoreTest {

    private val full = TurnStats(
        ttftMs = 3_480,
        totalMs = 12_400,
        outputTokens = 881,
        tps = 92.5,
        endToEndTps = 71.0,
        averageTps = 197.4,
        cacheHit = 63.5,
    )

    @Test
    fun `encode 写满 8 列并能原样解回来`() {
        val line = TurnStatsStore.encode("sess-1|msg-9", full)
        assertNotNull(line)
        assertEquals(8, line!!.split('\t').size)

        val (key, stats) = TurnStatsStore.decode(line)!!
        assertEquals("sess-1|msg-9", key)
        assertEquals(full, stats)
    }

    @Test
    fun `兼容 0_5_35 及更早落下的 6 列旧记录`() {
        val legacy = "sess-2|msg-3\t2500\t8000\t500\t88.0\t62.5"
        val (key, stats) = TurnStatsStore.decode(legacy)!!
        assertEquals("sess-2|msg-3", key)
        assertEquals(2_500L, stats.ttftMs)
        assertEquals(500, stats.outputTokens)
        assertEquals(88.0, stats.tps!!, 1e-9)
        assertEquals(62.5, stats.endToEndTps!!, 1e-9)
        // 旧记录里没有这两项，留空（界面上显示 —），不能当成 0
        assertNull(stats.averageTps)
        assertNull(stats.cacheHit)
    }

    @Test
    fun `列数不足或没有可用数字时返回 null`() {
        assertNull(TurnStatsStore.decode("只有一列"))
        assertNull(TurnStatsStore.decode("k\t\t\t\t\t"))
    }

    @Test
    fun `空 stats 不落盘`() {
        assertNull(TurnStatsStore.encode("k", TurnStats()))
    }

    @Test
    fun `只有速度的行视为空，不落盘`() {
        // TurnStats.isEmpty 只看首字 / 全程 / tokens：三项全空时这一轮没有被真正结算过，
        // 落下来只会让统计行显示一排带假速度的数字，所以宁可不记。
        assertNull(TurnStatsStore.encode("k", TurnStats(tps = 10.0)))

        // 只要有 tokens（哪怕是 0），速度就跟着一起存
        val line = TurnStatsStore.encode("k", TurnStats(outputTokens = 0, tps = 10.0))
        assertNotNull(line)
        val (_, back) = TurnStatsStore.decode(line!!)!!
        assertEquals(10.0, back.tps!!, 1e-9)
        assertEquals(0, back.outputTokens)
    }
}
