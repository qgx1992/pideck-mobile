package dev.pideck.nativeapp.ui.sessions

import dev.pideck.nativeapp.data.Project
import dev.pideck.nativeapp.data.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 抽屉「聊天」与「项目」两段的分组口径。
 *
 * 桌面端就是这么切的（`chatProjects = rootProjects.filter(isChatProject)` /
 * `workspaceProjects = rootProjects.filter(p => !isChatProject(p))`，
 * `isChatProject(project) === project.kind === "chat"`），两边各渲染同一套
 * 「项目头 + 会话树」。这里只守两件事：按 `kind` 切分、空项目也要留着。
 */
class ProjectGroupsTest {

    @Test
    fun `只有 kind 是 chat 的项目算聊天`() {
        assertTrue(isChatProject(project(id = "p1", kind = "chat")))
        assertFalse(isChatProject(project(id = "p2", kind = null)))
        assertFalse(isChatProject(project(id = "p3", kind = "workspace")))
        assertFalse(isChatProject(null))
    }

    @Test
    fun `两段用的是同一批项目按 kind 切开`() {
        val projects = listOf(
            project(id = "c1", name = "闲聊", kind = "chat"),
            project(id = "w1", name = "PiDeck", kind = null),
        )
        assertEquals(listOf("c1"), projectGroupsOf(projects, emptyList(), chat = true).map { it.projectId })
        assertEquals(listOf("w1"), projectGroupsOf(projects, emptyList(), chat = false).map { it.projectId })
    }

    @Test
    fun `没有会话的项目也留在项目段里`() {
        val projects = listOf(project(id = "w1", name = "PiDeck", kind = null))
        val groups = projectGroupsOf(projects, emptyList(), chat = false)
        assertEquals(1, groups.size)
        assertEquals(emptyList<Session>(), groups.single().sessions)
    }

    @Test
    fun `组内按最近更新倒序且不含子代理会话`() {
        val sessions = listOf(
            session(id = "a", projectId = "w1", updatedAt = 100L),
            session(id = "b", projectId = "w1", updatedAt = 300L),
            session(id = "sub", projectId = "w1", updatedAt = 500L, parentSessionId = "a"),
        )
        val group = projectGroupsOf(listOf(project(id = "w1", kind = null)), sessions, chat = false).single()
        assertEquals(listOf("b", "a"), group.sessions.map { it.id })
    }

    @Test
    fun `置顶项目排前面 其次按最近打开`() {
        val projects = listOf(
            project(id = "a", lastOpenedAt = 900L),
            project(id = "pin", lastOpenedAt = 1L, pinned = true),
            project(id = "b", lastOpenedAt = 500L),
        )
        assertEquals(
            listOf("pin", "a", "b"),
            projectGroupsOf(projects, emptyList(), chat = false).map { it.projectId },
        )
    }

    @Test
    fun `项目名空白时退回目录名`() {
        val groups = projectGroupsOf(
            listOf(project(id = "w1", name = "", path = "D:\\work\\pideck", kind = null)),
            emptyList(),
            chat = false,
        )
        assertEquals("D:\\work\\pideck", groups.single().projectName)
    }

    private fun project(
        id: String,
        name: String = id,
        path: String = "",
        kind: String? = null,
        pinned: Boolean = false,
        lastOpenedAt: Long = 0L,
    ) = Project(id = id, name = name, path = path, kind = kind, pinned = pinned, lastOpenedAt = lastOpenedAt)

    private fun session(
        id: String,
        projectId: String,
        updatedAt: Long,
        parentSessionId: String? = null,
    ) = Session(
        id = id,
        projectId = projectId,
        title = id,
        updatedAt = updatedAt,
        parentSessionId = parentSessionId,
    )
}
