package dev.pideck.nativeapp.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * 相对时间口径：与桌面端 `formatRelativeTime` 一致（30 天以上给「N 个月前」）。
 * 之前列表页给「93 天前」、抽屉给「08-08」，同一个会话两处不同 —— 这个测试守住「只有一份实现」。
 */
class TimeTextTest {

    private val now = 1_760_000_000_000L

    @Test
    fun `zero or negative epoch is blank`() {
        assertEquals("", relativeTime(0L, now))
        assertEquals("", relativeTime(-5L, now))
    }

    @Test
    fun `future timestamps fall back to just now`() {
        assertEquals("刚刚", relativeTime(now + 60_000L, now))
    }

    @Test
    fun `minute hour and day buckets`() {
        assertEquals("刚刚", relativeTime(now - 30_000L, now))
        assertEquals("1 分钟前", relativeTime(now - TimeUnit.MINUTES.toMillis(1), now))
        assertEquals("59 分钟前", relativeTime(now - TimeUnit.MINUTES.toMillis(59), now))
        assertEquals("1 小时前", relativeTime(now - TimeUnit.HOURS.toMillis(1), now))
        assertEquals("23 小时前", relativeTime(now - TimeUnit.HOURS.toMillis(23), now))
        assertEquals("1 天前", relativeTime(now - TimeUnit.DAYS.toMillis(1), now))
        assertEquals("29 天前", relativeTime(now - TimeUnit.DAYS.toMillis(29), now))
    }

    @Test
    fun `thirty days and beyond switch to months`() {
        assertEquals("1 个月前", relativeTime(now - TimeUnit.DAYS.toMillis(30), now))
        assertEquals("3 个月前", relativeTime(now - TimeUnit.DAYS.toMillis(93), now))
        assertEquals("12 个月前", relativeTime(now - TimeUnit.DAYS.toMillis(365), now))
    }
}
