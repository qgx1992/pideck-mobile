package dev.pideck.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * 结算缓存的读写。这里不起 Android Context（单测跑在 JVM 上），
 * 只验证「同一轮再读回来是同一份数」——落盘那条路径由 encode/decode 往返覆盖。
 */
class TurnStatsStoreTest {

    private val stats = TurnStats(
        ttftMs = 2_800L,
        totalMs = 10_591L,
        outputTokens = 1_248,
        tps = 150.0,
        endToEndTps = 117.8,
    )

    private val other = TurnStats(ttftMs = 900L, totalMs = 4_000L, outputTokens = 300)

    @Before
    fun reset() = TurnStatsStore.forgetAll()

    @Test
    fun `结算过的这一轮再读回来还是同一份数`() {
        TurnStatsStore.remember("s1", "m1", stats)
        val recalled = TurnStatsStore.recall("s1", "m1")
        assertEquals(stats, recalled)
    }

    @Test
    fun `换一条消息就查不到（别把上一轮的数贴到这一轮）`() {
        TurnStatsStore.remember("s1", "m1", stats)
        assertNull(TurnStatsStore.recall("s1", "m2"))
        assertNull(TurnStatsStore.recall("s2", "m1"))
    }

    @Test
    fun `同一轮重复结算以最后一次为准`() {
        TurnStatsStore.remember("s1", "m1", stats)
        TurnStatsStore.remember("s1", "m1", other)
        assertEquals(other, TurnStatsStore.recall("s1", "m1"))
    }

    @Test
    fun `空数据不进表（acp 那种 null meta 不要占坑）`() {
        TurnStatsStore.remember("s1", "m1", TurnStats())
        assertNull(TurnStatsStore.recall("s1", "m1"))
    }
}
