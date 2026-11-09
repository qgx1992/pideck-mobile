package dev.pideck.nativeapp.ui.chat

import dev.pideck.nativeapp.data.TimelineItem
import dev.pideck.nativeapp.data.ToolView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRowsTest {

    private fun user(key: String, text: String) =
        TimelineItem.UserMessage(key = key, text = text, timestamp = 0)

    private fun step(
        key: String,
        text: String = "",
        thinking: String? = null,
        tools: List<ToolView> = emptyList(),
        timestamp: Long = 0,
        streaming: Boolean = false,
        errorText: String? = null,
    ) = TimelineItem.AssistantMessage(
        key = key,
        text = text,
        thinking = thinking,
        tools = tools,
        timestamp = timestamp,
        streaming = streaming,
        errorText = errorText,
    )

    private fun tool(name: String) = ToolView(id = name, name = name, state = "done")

    @Test
    fun `连续的过程条目合成一个组`() {
        val rows = buildChatRows(
            items = listOf(
                user("u1", "改一下"),
                step("a1", thinking = "先看看", tools = listOf(tool("read"))),
                step("a2", tools = listOf(tool("edit"))),
                step("a3", text = "改好了"),
            ),
            live = null,
        )

        assertEquals(3, rows.size)
        assertTrue(rows[0] is ChatRow.Single)
        val group = rows[1] as ChatRow.Process
        assertEquals(listOf("a1", "a2"), group.steps.map { it.key })
        assertEquals(2, group.toolCount)
        assertEquals(1, group.thinkingCount)
        assertTrue(rows[2] is ChatRow.Single)
    }

    @Test
    fun `思考并进组且组内保留全文`() {
        val rows = buildChatRows(
            items = listOf(step("a1", thinking = "先看看再动手", tools = listOf(tool("read")))),
            live = null,
        )

        val group = rows.single() as ChatRow.Process
        assertEquals(1, group.thinkingCount)
        assertEquals("先看看再动手", group.steps.single().thinking)
        assertTrue(group.summary.contains("思考"))
    }

    @Test
    fun `中途回复不折进组里`() {
        val rows = buildChatRows(
            items = listOf(
                step("a1", tools = listOf(tool("read"))),
                step("a2", text = "先看一眼再改"),
                step("a3", tools = listOf(tool("edit"))),
            ),
            live = null,
        )

        assertEquals(3, rows.size)
        assertTrue(rows[0] is ChatRow.Process)
        assertTrue(rows[1] is ChatRow.Single)
        assertTrue(rows[2] is ChatRow.Process)
    }

    @Test
    fun `流式中的正文单独成行且带 streaming 标记`() {
        val live = step("live", text = "正在回答", streaming = true)
        val rows = buildChatRows(
            items = listOf(step("a1", tools = listOf(tool("read")))),
            live = live,
        )

        assertEquals(2, rows.size)
        val tail = rows[1] as ChatRow.Single
        assertTrue((tail.item as TimelineItem.AssistantMessage).streaming)
    }

    @Test
    fun `流式正文还没冒出来时并进过程组并标记 streaming`() {
        val live = step("live", tools = listOf(tool("grep")), streaming = true)
        val rows = buildChatRows(
            items = listOf(step("a1", tools = listOf(tool("read")))),
            live = live,
        )

        assertEquals(1, rows.size)
        val group = rows[0] as ChatRow.Process
        assertTrue(group.streaming)
        assertEquals(listOf("read", "grep"), group.toolNames)
    }

    @Test
    fun `只有错误没有过程的空白消息单独成行`() {
        val rows = buildChatRows(
            items = listOf(step("a1", errorText = "Agent 运行失败")),
            live = null,
        )

        assertEquals(1, rows.size)
        assertTrue(rows[0] is ChatRow.Single)
    }

    @Test
    fun `空列表得到空行序列`() {
        assertTrue(buildChatRows(emptyList(), null).isEmpty())
    }

    @Test
    fun `关掉过程组显示时每条内容各自平铺`() {
        val rows = buildChatRows(
            items = listOf(
                user("u1", "改一下"),
                step("a1", thinking = "先看看", tools = listOf(tool("read"))),
                step("a2", tools = listOf(tool("edit"))),
                step("a3", text = "改好了"),
            ),
            live = null,
            groupProcess = false,
        )

        assertEquals(4, rows.size)
        assertTrue(rows.all { it is ChatRow.Single })
        assertEquals("a1", (rows[1] as ChatRow.Single).item.key)
    }
}
