package dev.pideck.nativeapp.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `/api/chat` 请求体的形状 —— 图片必须挂在 user 消息的 `file` parts 上，
 * 服务端只从那里提取图片（放别的字段不读），所以这条契约值得钉住。
 */
class ChatPayloadsTest {

    private val png = "data:image/jpeg;base64,AAAA"

    @Test
    fun `文本与图片一起进 parts`() {
        val body = ChatPayloads.chatBody("s-1", "看这张图", listOf(png, png))
        assertEquals("s-1", body["id"]!!.jsonPrimitive.content)

        val messages = body["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        val message = messages[0].jsonObject
        assertEquals("user", message["role"]!!.jsonPrimitive.content)
        // content 是给老路径的兜底，服务端两者都会看
        assertEquals("看这张图", message["content"]!!.jsonPrimitive.content)

        val parts = message["parts"]!!.jsonArray
        assertEquals(3, parts.size)
        assertEquals("text", parts[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("看这张图", parts[0].jsonObject["text"]!!.jsonPrimitive.content)

        val file = parts[1].jsonObject
        assertEquals("file", file["type"]!!.jsonPrimitive.content)
        assertEquals("image/jpeg", file["mediaType"]!!.jsonPrimitive.content)
        assertEquals(png, file["url"]!!.jsonPrimitive.content)
        assertNull(file["data"])
    }

    @Test
    fun `只有图片时不留空文本段`() {
        val message = ChatPayloads.userMessage("", listOf(png))
        val parts = message["parts"]!!.jsonArray
        assertEquals(1, parts.size)
        assertEquals("file", parts[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `超过四张截断到服务端上限`() {
        val urls = List(6) { png }
        val parts = ChatPayloads.userMessage("x", urls)["parts"]!!.jsonArray
        val files = parts.filter { it.jsonObject["type"]!!.jsonPrimitive.content == "file" }
        assertEquals(ImagePrep.MAX_COUNT, files.size)
        assertTrue(parts is JsonArray)
    }

    @Test
    fun `纯文本消息仍然同时带 content`() {
        val body: JsonObject = ChatPayloads.chatBody("s-2", "hello", emptyList())
        val message = body["messages"]!!.jsonArray[0].jsonObject
        assertEquals("hello", message["content"]!!.jsonPrimitive.content)
        assertEquals(1, message["parts"]!!.jsonArray.size)
    }
}
