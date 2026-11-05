package dev.pideck.nativeapp.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用真机上抓到的 `/api/sessions/:id/messages/page` 形状（工具消息的 meta、assistant 的
 * thinking + text、system 噪音）验证映射，别在真机上才发现工具卡不显示。
 */
class TimelineTest {

    private fun meta(json: String?): JsonObject? = json?.let { Json.parseToJsonElement(it) as JsonObject }

    @Test
    fun `user and assistant messages map to bubbles`() {
        val messages = listOf(
            ChatMessage(id = "u1", role = "user", text = "帮我看下构建", timestamp = 1_700_000_000_000),
            ChatMessage(
                id = "a1",
                role = "assistant",
                text = "构建通过了。",
                thinking = "先跑一次",
                timestamp = 1_700_000_001_000,
                meta = meta("""{"durationMs":5282,"usage":{"outputTokens":433}}"""),
            ),
        )
        val items = messages.toTimelineItems()
        assertEquals(2, items.size)
        val user = items[0] as TimelineItem.UserMessage
        assertEquals("帮我看下构建", user.text)
        val assistant = items[1] as TimelineItem.AssistantMessage
        assertEquals("构建通过了。", assistant.text)
        assertEquals("先跑一次", assistant.thinking)
        assertTrue(assistant.tools.isEmpty())
    }

    @Test
    fun `tool message becomes a tool card with args and result`() {
        val message = ChatMessage(
            id = "t1",
            role = "tool",
            text = "pwsh: Get-ChildItem",
            timestamp = 1_700_000_002_000,
            meta = meta(
                """{"toolCallId":"call_1","toolName":"pwsh","status":"ok","args":{"command":"Get-ChildItem"},"result":"a.txt","durationMs":120}""",
            ),
        )
        val item = listOf(message).toTimelineItems().single() as TimelineItem.AssistantMessage
        val tool = item.tools.single()
        assertEquals("pwsh", tool.name)
        assertEquals("output-available", tool.state)
        assertTrue(tool.input!!.contains("Get-ChildItem"))
        assertEquals("a.txt", tool.output)
    }

    @Test
    fun `error tool keeps its message`() {
        val message = ChatMessage(
            id = "t2",
            role = "tool",
            text = "✗ 命令失败",
            timestamp = 1_700_000_003_000,
            meta = meta("""{"toolName":"bash","isError":true,"detailText":"exit code 1"}"""),
        )
        val tool = (listOf(message).toTimelineItems().single() as TimelineItem.AssistantMessage).tools.single()
        assertEquals("output-error", tool.state)
        assertEquals("exit code 1", tool.errorText)
        assertTrue(tool.isError)
    }

    @Test
    fun `empty assistant shell is dropped`() {
        val messages = listOf(ChatMessage(id = "a9", role = "assistant", text = "  ", thinking = null))
        assertTrue(messages.toTimelineItems().isEmpty())
    }

    @Test
    fun `live snapshot maps tools and streaming flag`() {
        val merger = ReplyMerger(idFactory = { "live-1" })
        merger.applyFrame(meta("""{"type":"text-delta","delta":"好了"}""")!!)
        merger.applyFrame(meta("""{"type":"tool-input-available","toolCallId":"x","toolName":"read","input":{"path":"a.ts"}}""")!!)
        val item = merger.snapshot()!!.toTimelineItem(streaming = true)
        assertEquals("好了", item.text)
        assertTrue(item.streaming)
        assertEquals("read", item.tools.single().name)

        val finished = merger.finish()!!.toTimelineItem(streaming = true)
        assertTrue("收尾后不再显示光标", !finished.streaming)
        assertNull(finished.errorText)
    }

    @Test
    fun `user images survive the mapping`() {
        val message = ChatMessage(
            id = "u-img",
            role = "user",
            text = "看下这张图",
            timestamp = 1_700_000_000_000,
            images = listOf(MessageImage(type = "image", data = "/9j/AAAA", mimeType = "image/jpeg")),
        )
        val user = listOf(message).toTimelineItems().single() as TimelineItem.UserMessage
        assertEquals(1, user.images.size)
        assertEquals("data:image/jpeg;base64,/9j/AAAA", user.images.single().dataUrl)
    }

    @Test
    fun `empty image payloads are dropped`() {
        val message = ChatMessage(
            id = "u-empt",
            role = "user",
            text = "只有文字",
            images = listOf(MessageImage(type = "image", data = "")),
        )
        assertTrue((listOf(message).toTimelineItems().single() as TimelineItem.UserMessage).images.isEmpty())
    }

    @Test
    fun `history json with images decodes`() {
        val json = """{"messages":[{"id":"m1","role":"user","text":"看下这张图","timestamp":1700000000000,
            "images":[{"type":"image","data":"/9j/AAAA","mimeType":"image/jpeg"}]}],"total":1}"""
        val page = PideckJson.decodeFromString(SessionMessagePage.serializer(), json)
        val images = (page.messages.toTimelineItems().single() as TimelineItem.UserMessage).images
        assertEquals(1, images.size)
        assertEquals("image/jpeg", images.single().mimeType)
    }
}
