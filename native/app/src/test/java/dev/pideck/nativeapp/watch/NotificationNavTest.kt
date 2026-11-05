package dev.pideck.nativeapp.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 通知 id 与「点通知跳会话」的交接台。
 *
 * 坑来自真机：id 只用 sessionId 时，两台设备上同名（克隆）会话的通知会互相覆盖，
 * 点回来还会开错机器；PendingIntent 共用一个 requestCode 时，后发的通知会覆盖
 * 前一条的 extras，表现为「点了 A，进去是 B」。
 */
class NotificationNavTest {

    @Test
    fun `same session keeps one notification id`() {
        assertEquals(
            NotificationNav.notificationId("dev-a", "s1"),
            NotificationNav.notificationId("dev-a", "s1"),
        )
    }

    @Test
    fun `same session id on two devices does not collide`() {
        assertNotEquals(
            NotificationNav.notificationId("dev-a", "s1"),
            NotificationNav.notificationId("dev-b", "s1"),
        )
    }

    @Test
    fun `target survives until the nav graph takes it`() {
        NotificationNav.clear()
        NotificationNav.post(NotificationNav.Target("dev-a", "s1"))
        assertEquals(NotificationNav.Target("dev-a", "s1"), NotificationNav.take())
        // 取走即清空：同一份 Intent 在 Activity 重建时不该再跳一次
        assertNull(NotificationNav.take())
        assertNull(NotificationNav.flow.value)
    }
}
