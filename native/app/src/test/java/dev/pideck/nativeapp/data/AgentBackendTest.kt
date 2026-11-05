package dev.pideck.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 新建会话的后端选择。
 *
 * 守的是三件宿主行为（实测 `POST /api/sessions`）：
 * pi 是「不写 `backend`」、`dsh` / `acp` 会被写进记录、认不出来的值一律退回 pi。
 */
class AgentBackendTest {

    private val codeBuddy = AcpTool(id = "acp-tool-1", name = "CodeBuddy")

    @Test
    fun `options are pi then dsh then acp tools`() {
        val options = AgentBackend.options(listOf(codeBuddy, AcpTool(id = "acp-tool-2", name = "Other")))
        assertEquals(4, options.size)
        assertEquals(AgentBackend.Pi, options[0])
        assertEquals(AgentBackend.Dsh, options[1])
        assertTrue(options[2] is AgentBackend.Acp)
        assertEquals("CodeBuddy", options[2].label)
        assertEquals("Other", options[3].label)
    }

    @Test
    fun `acp tools without id are skipped`() {
        val options = AgentBackend.options(listOf(AcpTool(id = "", name = "Broken")))
        assertEquals(listOf("Pi", "DSH"), options.map { it.label })
    }

    @Test
    fun `pi writes no backend field`() {
        assertNull(AgentBackend.Pi.wire)
        assertEquals("", AgentBackend.idOf(AgentBackend.Pi))
    }

    @Test
    fun `dsh and acp write their wire values`() {
        assertEquals("dsh", AgentBackend.Dsh.wire)
        assertEquals("dsh", AgentBackend.idOf(AgentBackend.Dsh))

        val acp = AgentBackend.Acp("acp-tool-1", "CodeBuddy")
        assertEquals("acp", acp.wire)
        assertEquals("acp:acp-tool-1", AgentBackend.idOf(acp))
    }

    @Test
    fun `parse restores what idOf wrote`() {
        val tools = listOf(codeBuddy)
        assertEquals(AgentBackend.Dsh, AgentBackend.parse("dsh", tools))
        val acp = AgentBackend.parse("acp:acp-tool-1", tools) as AgentBackend.Acp
        assertEquals("acp-tool-1", acp.toolId)
        assertEquals("CodeBuddy", acp.label)
    }

    @Test
    fun `parse falls back to pi when the tool is gone`() {
        // 工具被桌面端删了：不能留着 `acp:` 去建一个宿主不认识的会话
        assertEquals(AgentBackend.Pi, AgentBackend.parse("acp:deleted-tool", emptyList()))
        assertEquals(AgentBackend.Pi, AgentBackend.parse("acp:", listOf(codeBuddy)))
        assertEquals(AgentBackend.Pi, AgentBackend.parse(null, listOf(codeBuddy)))
        assertEquals(AgentBackend.Pi, AgentBackend.parse("imagegen", listOf(codeBuddy)))
    }

    @Test
    fun `acp label falls back to the tool id`() {
        val acp = AgentBackend.parse("acp:acp-tool-9", listOf(AcpTool(id = "acp-tool-9", name = ""))) as AgentBackend.Acp
        assertEquals("acp-tool-9", acp.label)
    }

    @Test
    fun `badge names every backend and falls back to the tool name`() {
        assertEquals("Pi", AgentBackend.badgeOf(null))
        assertEquals("Pi", AgentBackend.badgeOf(""))
        assertEquals("Pi", AgentBackend.badgeOf("pi"))
        assertEquals("DSH", AgentBackend.badgeOf("DSH"))
        assertEquals("CodeBuddy", AgentBackend.badgeOf("acp", "CodeBuddy"))
        // 工具名还没加载出来时别显示成空白
        assertEquals("ACP", AgentBackend.badgeOf("acp", null))
        assertEquals("图像生成", AgentBackend.badgeOf("imagegen"))
        assertEquals("视频生成", AgentBackend.badgeOf("videogen"))
    }

    @Test
    fun `new session defaults to pi`() {
        // 首页每次打开都是 pi：不记上次选择（桌面端的 defaultAgentBackend 拿不到）
        assertEquals(AgentBackend.Pi, AgentBackend.options(emptyList()).first())
    }
}
