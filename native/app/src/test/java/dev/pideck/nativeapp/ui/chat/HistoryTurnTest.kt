package dev.pideck.nativeapp.ui.chat

import dev.pideck.nativeapp.data.TimelineItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTurnTest {

    private val u1 = TimelineItem.UserMessage(key = "u1", text = "第一个问题", timestamp = 1_000L)
    private val a1 = TimelineItem.AssistantMessage(key = "a1", text = "第一个回答")
    private val u2 = TimelineItem.UserMessage(key = "u2", text = "第二个问题\n换行了", timestamp = 2_000L)
    private val a2 = TimelineItem.AssistantMessage(key = "a2", text = "第二个回答")

    private fun turns(items: List<TimelineItem>) = buildHistoryTurns(items) { "t$it" }

    @Test
    fun `每一轮是一条记录，最新在前`() {
        val result = turns(listOf(u1, a1, u2, a2))
        assertEquals(listOf("u2", "u1"), result.map { it.key })
        assertEquals("第二个问题 换行了", result[0].prompt)
        assertEquals("第一个回答", result[1].reply)
    }

    @Test
    fun `只有提问没有回答也给一条记录`() {
        val result = turns(listOf(u1))
        assertEquals(1, result.size)
        assertEquals("u1", result[0].key)
        assertTrue(result[0].reply.isEmpty())
    }

    @Test
    fun `工具消息不单独成条，挂在所属提问上`() {
        val toolOnly = TimelineItem.AssistantMessage(
            key = "a1",
            text = "",
            tools = listOf(
                dev.pideck.nativeapp.data.ToolView("t1", "Read", "success", null, null, null),
            ),
        )
        val result = turns(listOf(u1, toolOnly, a1))
        assertEquals(1, result.size)
        assertEquals("第一个回答", result[0].reply)
    }

    @Test
    fun `没有提问时是空列表`() {
        assertTrue(turns(listOf(a1, a2)).isEmpty())
    }

    @Test
    fun `回答太长会截断`() {
        val long = "字".repeat(300)
        val result = turns(listOf(u1, TimelineItem.AssistantMessage(key = "a1", text = long)))
        assertEquals(120, result[0].reply.length)
    }
}
