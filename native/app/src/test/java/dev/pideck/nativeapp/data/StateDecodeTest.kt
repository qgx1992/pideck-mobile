package dev.pideck.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.UnknownHostException
import kotlinx.serialization.SerializationException

/**
 * 用真机上抓到的 `/api/state` 形状做回归：会话的 `updatedAt` 是浮点（`1792225287694.017`），
 * 早先声明成 `Long` 会让整份响应解不出来，界面上就只剩一句「连接不了」。
 */
class StateDecodeTest {

    private fun sample(): String =
        javaClass.classLoader!!.getResourceAsStream("state-sample.json")!!
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

    @Test
    fun `real shaped state decodes`() {
        val state = PideckJson.decodeFromString(PideckState.serializer(), sample())

        assertEquals(2, state.projects.size)
        assertEquals(3, state.sessions.size)
        assertEquals(2, state.runtimes.size)
        assertEquals("CodeBuddy", state.acpTools.single().name)
        assertNotNull(state.security)

        val chat = state.projects.first { it.id == "builtin-chat" }
        assertEquals("chat", chat.kind)
        assertEquals(1792227152259L, chat.lastOpenedAt)

        val floating = state.sessions.first { it.id == "93720f59-8e51-43cd-b086-7832c06b3d2f" }
        assertEquals(1792225287694L, floating.updatedAt)

        val withModel = state.sessions.first { it.id == "0a1225e4-bb00-41f6-8b0a-8b9312df06e3" }
        assertEquals("claude-sonnet-4-5", withModel.model?.modelId)
        assertEquals("medium", withModel.thinkingLevel)

        val running = state.runtimes.first { it.status == "running" }
        assertTrue(running.isRunning)
        assertEquals("builtin-chat", running.projectId)
    }

    @Test
    fun `unknown keys do not break decoding`() {
        val json = """{"projects":[],"sessions":[],"runtimes":[],"brandNewKey":{"a":1},"version":"0.9.9"}"""
        val state = PideckJson.decodeFromString(PideckState.serializer(), json)
        assertEquals("0.9.9", state.version)
        assertTrue(state.sessions.isEmpty())
    }

    @Test
    fun `401 tells you to paste the link`() {
        val text = describeConnectionError(PideckException("state 401", code = 401))
        assertTrue(text.contains("token"))
        assertTrue(text.contains("连接面板"))
    }

    @Test
    fun `refused connection points at the web service`() {
        val text = describeConnectionError(ConnectException("Connection refused"))
        assertTrue(text.contains("局域网 Web 服务"))
    }

    @Test
    fun `unknown host points at the address`() {
        val text = describeConnectionError(UnknownHostException("pideck.local"))
        assertTrue(text.contains("找不到"))
    }

    @Test
    fun `security projection decodes from the state payload`() {
        val state = PideckJson.decodeFromString(PideckState.serializer(), sample())
        val security = parseSecurityState(state.security)
        assertTrue(security != null)
        assertEquals("off", security!!.defaultLevelId)
        assertTrue(security.levelOf("off") != null)
        // 草稿会话没有会话级覆盖，取全局默认
        assertEquals("off", security.effectiveId("sess-1"))
        assertEquals("off", security.labelOf("off"))
    }

    @Test
    fun `decode failure is reported as a format problem`() {
        val text = describeConnectionError(SerializationException("boom"))
        assertTrue(text.contains("格式"))
    }
}
