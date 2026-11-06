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
    fun `活动段列出全部会话，不只是运行中的`() {
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
        val ordered = activityDrawerSessions(sessions) { statuses[it] ?: SessionActivity.Status.Idle }
        // 等回答 > 正在跑 > 其余（其余内部按最近活动倒序）；一条都不能少
        assertEquals(listOf("waiting", "running", "done", "idle"), ordered.map { it.id })
    }

    @Test
    fun `没有会话在跑时仍然按最近活动列出全部会话`() {
        val sessions = listOf(session("a", 1_000), session("b", 3_000), session("c", 2_000))
        val ordered = activityDrawerSessions(sessions) { SessionActivity.Status.Idle }
        assertEquals(listOf("b", "c", "a"), ordered.map { it.id })
    }

    @Test
    fun `等你回答的排在正在跑的前面`() {
        val sessions = listOf(
            session("running-new", 9_000),
            session("waiting-old", 1_000),
        )
        val ordered = activityDrawerSessions(sessions) {
            if (it.startsWith("waiting")) SessionActivity.Status.Waiting else SessionActivity.Status.Running
        }
        assertEquals(listOf("waiting-old", "running-new"), ordered.map { it.id })
    }

    @Test
    fun `已结束或失败的会话排在运行中的后面`() {
        val sessions = listOf(session("failed", 9_000), session("done", 8_000), session("running", 1_000))
        val statuses = mapOf(
            "failed" to SessionActivity.Status.Failed,
            "done" to SessionActivity.Status.Done,
            "running" to SessionActivity.Status.Running,
        )
        val ordered = activityDrawerSessions(sessions) { statuses[it] ?: SessionActivity.Status.Idle }
        assertEquals(listOf("running", "failed", "done"), ordered.map { it.id })
    }

    @Test
    fun `空列表不炸`() {
        assertTrue(activityDrawerSessions(emptyList()) { SessionActivity.Status.Idle }.isEmpty())
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
