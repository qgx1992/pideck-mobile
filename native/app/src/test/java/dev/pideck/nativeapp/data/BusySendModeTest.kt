package dev.pideck.nativeapp.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 运行中发送的三档：这一层只钉两件事 —— 档位字符串与桌面端一致（`steer` / `followUp`），
 * 以及 `prompt` 的 body 形状对得上宿主（`{requestId, message, streamingBehavior}`）。
 */
class BusySendModeTest {

    private val json = Json

    @Test
    fun `wire ids match the desktop settings values`() {
        assertEquals("steer", BusySendMode.STEER.id)
        assertEquals("followUp", BusySendMode.FOLLOW_UP.id)
        // 并行发送不是宿主认识的投递方式，它是客户端自己的另一条路（匿名会话）
        assertEquals("ask", BusySendMode.PARALLEL.id)
    }

    @Test
    fun `unknown or empty ids fall back to steer`() {
        assertEquals(BusySendMode.STEER, BusySendMode.parse(null))
        assertEquals(BusySendMode.STEER, BusySendMode.parse(""))
        assertEquals(BusySendMode.STEER, BusySendMode.parse("queue"))
        assertEquals(BusySendMode.FOLLOW_UP, BusySendMode.parse("followUp"))
    }

    @Test
    fun `parallel ask is the readable label of the anonymous session mode`() {
        assertEquals("并行发送", BusySendMode.PARALLEL.label)
        assertEquals("加入当前回合", BusySendMode.STEER.label)
        assertEquals("排队到下一轮", BusySendMode.FOLLOW_UP.label)
    }

    @Test
    fun `follow up body carries the streaming behavior and nothing else`() {
        val body = ChatPayloads.promptBody("req-1", "  下一轮再说  ", "followUp").jsonObject
        assertEquals("req-1", body["requestId"]?.jsonPrimitive?.content)
        assertEquals("下一轮再说", body["message"]?.jsonPrimitive?.content)
        assertEquals("followUp", body["streamingBehavior"]?.jsonPrimitive?.content)
        assertNull(body["images"])
    }

    @Test
    fun `prompt body omits the behavior when it is not set`() {
        val body = ChatPayloads.promptBody("req-2", "hi").jsonObject
        assertFalse(body.containsKey("streamingBehavior"))
        assertEquals("hi", body["message"]?.jsonPrimitive?.content)
    }

    @Test
    fun `prompt delivery reads the accepted receipt`() {
        val receipt = parsePromptDelivery(
            """{"result":{"accepted":true,"sessionId":"s1","requestId":"r1","agentId":"a1","runtimeGeneration":3}}""",
        )
        assertTrue(receipt.accepted)
        assertEquals("a1", receipt.agentId)
        assertEquals(3L, receipt.runtimeGeneration)
    }

    @Test
    fun `prompt delivery keeps the host refusal reason`() {
        val receipt = parsePromptDelivery("""{"result":{"accepted":false,"error":"busy"}}""")
        assertFalse(receipt.accepted)
        assertEquals("busy", receipt.error)
    }
}
