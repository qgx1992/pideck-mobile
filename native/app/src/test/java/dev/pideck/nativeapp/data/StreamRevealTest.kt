package dev.pideck.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamRevealTest {
    @Test
    fun `already fully shown stays put`() {
        assertEquals(10, nextReveal(shown = 10, total = 10, dtMillis = 16))
    }

    @Test
    fun `zero frame time never advances`() {
        assertEquals(3, nextReveal(shown = 3, total = 100, dtMillis = 0))
    }

    @Test
    fun `small step still advances one char per frame`() {
        // 60 字/秒 × 16ms = 0.96 字 → 取整是 0，这里必须至少 +1，否则永远不动
        assertEquals(1, nextReveal(shown = 0, total = 100, dtMillis = 16))
    }

    @Test
    fun `steady rate after half a second`() {
        // 积压 100 字 < 160 的追赶阈值 → 走匀速 60 字/秒
        assertEquals(30, nextReveal(shown = 0, total = 100, dtMillis = 500))
    }

    @Test
    fun `backlog beyond threshold catches up fast`() {
        // 积压 500 字 > 160 → 按 900 字/秒放，半秒就是 450
        assertEquals(450, nextReveal(shown = 0, total = 600, dtMillis = 500))
    }

    @Test
    fun `never overshoots what arrived`() {
        assertEquals(150, nextReveal(shown = 0, total = 150, dtMillis = 5_000))
    }

    @Test
    fun `shown ahead of total is clamped`() {
        assertEquals(20, nextReveal(shown = 40, total = 20, dtMillis = 16))
    }
}
