package dev.pideck.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** 性能浮层的数字口径：FPS 取平均，1% Low 取最差那 1% 的平均帧率。 */
class FrameRateMathTest {

    @Test
    fun `steady sixty fps`() {
        val frames = LongArray(60) { 16L }
        assertEquals(62, FrameRateMath.fps(frames))
        assertEquals(62, FrameRateMath.onePercentLow(frames))
    }

    @Test
    fun `empty or zero intervals give zero`() {
        assertEquals(0, FrameRateMath.fps(LongArray(0)))
        assertEquals(0, FrameRateMath.fps(longArrayOf(0, 0)))
        assertEquals(0, FrameRateMath.onePercentLow(LongArray(0)))
    }

    @Test
    fun `one stuck frame drags the one percent low down`() {
        // 99 个好帧 + 1 个 100ms 的卡顿：平均帧率仍接近 60，但 1% Low 只有 10。
        val frames = LongArray(100) { 16L }.also { it[99] = 100L }
        assertEquals(59, FrameRateMath.fps(frames))
        assertEquals(10, FrameRateMath.onePercentLow(frames))
    }

    @Test
    fun `slow overall still reports lower`() {
        assertEquals(30, FrameRateMath.fps(longArrayOf(33, 33, 34, 33)))
    }
}
