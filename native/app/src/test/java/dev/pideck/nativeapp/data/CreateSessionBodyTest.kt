package dev.pideck.nativeapp.data

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `POST /api/sessions` 的 body 形状。
 *
 * 宿主的路由只校验 `projectId`，整个 body 透传给 `createSessionDraft`，那边只认
 * `backend` 的 `dsh` / `acp`，`model` 也只在非 ACP 时落盘。所以这颗胶囊
 * 「带了什么 / 没带什么」直接决定新会话用哪个模型，不能靠猜。
 */
class CreateSessionBodyTest {

    private val model = SessionModel(
        provider = "anthropic",
        modelId = "claude-sonnet-4-5",
        modelName = "Claude Sonnet 4.5",
    )

    @Test
    fun `pi with a picked model carries the three fields`() {
        val body = ChatPayloads.createSessionBody("proj-1", null, null, model).jsonObject
        assertEquals("proj-1", body["projectId"]?.jsonPrimitive?.content)
        assertFalse(body.containsKey("backend"))
        val picked = body["model"]?.jsonObject
        assertEquals("anthropic", picked?.get("provider")?.jsonPrimitive?.content)
        assertEquals("claude-sonnet-4-5", picked?.get("modelId")?.jsonPrimitive?.content)
        assertEquals("Claude Sonnet 4.5", picked?.get("modelName")?.jsonPrimitive?.content)
    }

    @Test
    fun `pi without a pick omits the model key entirely`() {
        // 「跟随电脑端默认」就是不带这个字段：宿主自己去解析默认链路
        val body = ChatPayloads.createSessionBody("proj-1", null, null, null).jsonObject
        assertFalse(body.containsKey("model"))
        assertEquals(1, body.keys.size)
    }

    @Test
    fun `acp never carries a model`() {
        val body = ChatPayloads.createSessionBody("proj-1", "acp", "tool-9", model).jsonObject
        assertEquals("acp", body["backend"]?.jsonPrimitive?.content)
        assertEquals("tool-9", body["acpToolId"]?.jsonPrimitive?.content)
        assertFalse(body.containsKey("model"))
    }

    @Test
    fun `dsh keeps its backend but never a pi model`() {
        val body = ChatPayloads.createSessionBody("proj-1", "dsh", null, model).jsonObject
        assertEquals("dsh", body["backend"]?.jsonPrimitive?.content)
        assertFalse(body.containsKey("model"))
    }

    @Test
    fun `incomplete model preference is not sent`() {
        val broken = SessionModel(provider = "", modelId = "claude-sonnet-4-5", modelName = "")
        assertFalse(ChatPayloads.createSessionBody("p", null, null, broken).jsonObject.containsKey("model"))
        val noId = SessionModel(provider = "anthropic", modelId = "", modelName = "x")
        assertFalse(ChatPayloads.createSessionBody("p", null, null, noId).jsonObject.containsKey("model"))
    }

    @Test
    fun `blank backend is treated as pi`() {
        val body = ChatPayloads.createSessionBody("proj-1", "  ", null, model).jsonObject
        assertFalse(body.containsKey("backend"))
        assertEquals("anthropic", body["model"]?.jsonObject?.get("provider")?.jsonPrimitive?.content)
    }

    @Test
    fun `model name may be empty`() {
        val noName = SessionModel(provider = "openai", modelId = "gpt-5", modelName = "")
        val picked = ChatPayloads.createSessionBody("p", null, null, noName).jsonObject["model"]?.jsonObject
        assertEquals("gpt-5", picked?.get("modelId")?.jsonPrimitive?.content)
        assertEquals("", picked?.get("modelName")?.jsonPrimitive?.content)
        assertNull(picked?.get("thinkingLevel"))
    }
}
