package dev.pideck.nativeapp.ui.sessions

import dev.pideck.nativeapp.data.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSearchTest {

    private fun session(id: String, title: String, updatedAt: Long = 0L) = Session(
        id = id,
        projectId = "p1",
        title = title,
        status = "active",
        projectPath = null,
        updatedAt = updatedAt,
        createdAt = updatedAt,
        parentSessionId = null,
        forked = false,
        backend = "pi",
        thinkingLevel = null,
        model = null,
        permissionPreset = null,
        acpToolId = null,
    )

    private val groups = listOf(
        SessionGroup(
            projectId = "p1",
            projectName = "PiDeck",
            projectPath = "/repo",
            sessions = listOf(
                session("s1", "修下载页的空白条"),
                session("s2", "Round-Trip 测试"),
            ),
        ),
        SessionGroup(
            projectId = "p2",
            projectName = "闲聊灵感",
            projectPath = "/chat",
            sessions = listOf(session("s3", "换脸脚本调参")),
        ),
    )

    @Test
    fun `空查询原样返回`() {
        assertEquals(groups, filterGroups(groups, ""))
        assertEquals(groups, filterGroups(groups, "   "))
    }

    @Test
    fun `按标题过滤并丢掉空分组`() {
        val hit = filterGroups(groups, "空白")
        assertEquals(1, hit.size)
        assertEquals("p1", hit[0].projectId)
        assertEquals(listOf("s1"), hit[0].sessions.map { it.id })
    }

    @Test
    fun `大小写与空格都不敏感`() {
        val hit = filterGroups(groups, "  round-trip ")
        assertEquals(listOf("s2"), hit.flatMap { it.sessions }.map { it.id })
        assertEquals(listOf("s2"), filterGroups(groups, "ROUND").flatMap { it.sessions }.map { it.id })
    }

    @Test
    fun `跨项目都能命中`() {
        val hit = filterGroups(groups, "调参")
        assertTrue(hit.all { it.projectId == "p2" })
    }

    @Test
    fun `命中不了返回空`() {
        assertTrue(filterGroups(groups, "不存在的标题").isEmpty())
    }
}
