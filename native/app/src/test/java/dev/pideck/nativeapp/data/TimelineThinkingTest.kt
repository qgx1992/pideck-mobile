package dev.pideck.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineThinkingTest {

    @Test
    fun `成对的 thinking 收进思考块`() {
        val (body, thinking) = splitThinking("<thinking>先想一下</thinking>\n\n最终 42")
        assertEquals("最终 42", body)
        assertEquals("先想一下", thinking)
    }

    @Test
    fun `未闭合的 thinking 也收进思考块`() {
        // 真机会话里常见「12 个 <thinking> 只配了 4 个 </thinking>」：落单的那些如果留在正文，
        // 用户看到的就是整段推理泄露到回答中间。
        val (body, thinking) = splitThinking("<thinking>还在想")
        assertEquals("", body)
        assertEquals("还在想", thinking)
    }

    @Test
    fun `没有标签时原样返回`() {
        val raw = "只有正文，没有思考"
        val (body, thinking) = splitThinking(raw)
        assertEquals(raw, body)
        assertNull(thinking)
    }

    @Test
    fun `多段 thinking 拼到一起`() {
        val (body, thinking) = splitThinking("<thinking>第一段</thinking>中间<thinking>第二段</thinking>尾巴")
        assertEquals("中间尾巴", body)
        assertEquals("第一段\n\n第二段", thinking)
    }

    @Test
    fun `历史消息里 thinking 字段与正文标签去重`() {
        val message = ChatMessage(
            id = "m1",
            agentId = "a",
            role = "assistant",
            text = "<thinking>推理过程</thinking>\n\n最终回答",
            timestamp = 0L,
            thinking = "推理过程",
        )
        val item = listOf(message).toTimelineItems().single() as TimelineItem.AssistantMessage
        assertEquals("最终回答", item.text)
        assertEquals("推理过程", item.thinking)
    }

    @Test
    fun `只有思考没有正文时也保留条目`() {
        val message = ChatMessage(
            id = "m2",
            agentId = "a",
            role = "assistant",
            text = "<thinking>只有思考</thinking>",
            timestamp = 0L,
        )
        val item = listOf(message).toTimelineItems().single() as TimelineItem.AssistantMessage
        assertTrue(item.text.isEmpty())
        assertEquals("只有思考", item.thinking)
    }
}
