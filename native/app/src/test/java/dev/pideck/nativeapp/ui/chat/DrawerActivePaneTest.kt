package dev.pideck.nativeapp.ui.chat

import dev.pideck.nativeapp.data.Session
import dev.pideck.nativeapp.data.SessionActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawerActivePaneTest {

    private fun session(id: String, updatedAt: Long, title: String = id) =
        Session(id = id, projectId = "p1", title = title, updatedAt = updatedAt)

    @Test
    fun `运行中与等回答的会话进活动段，其余不进`() {
        val sessions = listOf(
            session("running", 3_000),
            session("waiting", 2_000),
            session("done", 5_000),
            session("idle", 4_000),
        )
        val statuses = mapOf(
            "running" to SessionActivity.Status.Running,
            "waiting" to SessionActivity.Status.Waiting,
            "done" to SessionActivity.Status.Done,
        )
        val active = activeDrawerSessions(sessions) { statuses[it] ?: SessionActivity.Status.Idle }
        // 等回答的排前面（那是真的卡住了），正在跑的排后面
        assertEquals(listOf("waiting", "running"), active.map { it.id })
    }

    @Test
    fun `等你回答的排在正在跑的前面`() {
        val sessions = listOf(
            session("running-new", 9_000),
            session("waiting-old", 1_000),
        )
        val active = activeDrawerSessions(sessions) {
            if (it.startsWith("waiting")) SessionActivity.Status.Waiting else SessionActivity.Status.Running
        }
        assertEquals(listOf("waiting-old", "running-new"), active.map { it.id })
    }

    @Test
    fun `同档内按最近活动倒序`() {
        val sessions = listOf(session("a", 1_000), session("c", 3_000), session("b", 2_000))
        val active = activeDrawerSessions(sessions) { SessionActivity.Status.Running }
        assertEquals(listOf("c", "b", "a"), active.map { it.id })
    }

    @Test
    fun `没有在跑的会话时活动段为空`() {
        val active = activeDrawerSessions(listOf(session("a", 1_000))) { SessionActivity.Status.Done }
        assertTrue(active.isEmpty())
    }

    @Test
    fun `最近会话只取前 N 条，且按时间倒序`() {
        val sessions = listOf(
            session("old", 1_000),
            session("new", 9_000),
            session("mid", 5_000),
        )
        assertEquals(listOf("new", "mid"), recentDrawerSessions(sessions, 2).map { it.id })
    }

    @Test
    fun `限额为零时最近会话为空（做除法或 take 负数前先挡住）`() {
        assertTrue(recentDrawerSessions(listOf(session("a", 1_000)), 0).isEmpty())
        assertTrue(recentDrawerSessions(listOf(session("a", 1_000)), -5).isEmpty())
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
