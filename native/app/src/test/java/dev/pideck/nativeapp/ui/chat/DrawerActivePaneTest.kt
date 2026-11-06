package dev.pideck.nativeapp.ui.chat

import dev.pideck.nativeapp.data.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawerActivePaneTest {

    private fun session(id: String, updatedAt: Long, title: String = id) =
        Session(id = id, projectId = "p1", title = title, updatedAt = updatedAt)

    private val sessions = listOf(
        session("running", 6_000),
        session("idle-loaded", 5_000),
        session("recent-new", 4_000),
        session("recent-old", 3_000),
        session("recent-oldest", 2_000),
    )

    @Test
    fun `上半是装载着运行时的会话，按最近活动倒序（不是只挑在跑的）`() {
        val live = liveDrawerSessions(sessions, setOf("running", "idle-loaded"))
        assertEquals(listOf("running", "idle-loaded"), live.map { it.id })
    }

    @Test
    fun `运行时全是 idle 时上半照样有内容`() {
        val live = liveDrawerSessions(sessions, setOf("idle-loaded"))
        assertEquals(listOf("idle-loaded"), live.map { it.id })
    }

    @Test
    fun `下半排除上半已经出现过的会话，上下不重复`() {
        val loaded = setOf("running", "idle-loaded")
        val live = liveDrawerSessions(sessions, loaded)
        val recent = recentDrawerSessions(sessions, loaded)
        assertEquals(listOf("recent-new", "recent-old", "recent-oldest"), recent.map { it.id })
        val overlap = live.map { it.id }.intersect(recent.map { it.id }.toSet())
        assertTrue("上下两段不能有重复：$overlap", overlap.isEmpty())
    }

    @Test
    fun `下半按最近活动倒序`() {
        val recent = recentDrawerSessions(sessions, setOf("running"))
        assertEquals(listOf("idle-loaded", "recent-new", "recent-old", "recent-oldest"), recent.map { it.id })
    }

    @Test
    fun `没有任何运行时时上半为空、下半是全部会话`() {
        assertTrue(liveDrawerSessions(sessions, emptySet()).isEmpty())
        assertEquals(5, recentDrawerSessions(sessions, emptySet()).size)
    }

    @Test
    fun `运行时集合里出现本地没有的会话 id 不会炸也不算进上下两段`() {
        val live = liveDrawerSessions(sessions, setOf("ghost"))
        assertTrue(live.isEmpty())
        assertEquals(5, recentDrawerSessions(sessions, setOf("ghost")).size)
    }

    @Test
    fun `空列表不炸`() {
        assertTrue(liveDrawerSessions(emptyList(), setOf("a")).isEmpty())
        assertTrue(recentDrawerSessions(emptyList(), emptySet()).isEmpty())
    }

    @Test
    fun `分段默认落在聊天，切换后进程内保持`() {
        DrawerTabState.reset()
        assertEquals(DrawerSection.Chats, DrawerTabState.current)
        DrawerTabState.select(DrawerSection.Active)
        assertEquals(DrawerSection.Active, DrawerTabState.current)
        DrawerTabState.reset()
        assertEquals(DrawerSection.Chats, DrawerTabState.current)
    }
}
