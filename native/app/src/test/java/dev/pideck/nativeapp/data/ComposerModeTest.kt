package dev.pideck.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 输入框模式的载荷拼法。
 *
 * 重点不只是「拼得对」，而是**两个载荷分开**：用户打的那句进 `message`（落盘、别人看得到），
 * 标记与指令进 `agentMessage`（只给 agent）。0.5.68 之前是把两者拼在一起塞 `message`，
 * 落盘的消息里带着隐藏指令 —— 那些老消息靠 [ComposerModePayloads.displayText] 剥。
 */
class ComposerModeTest {

    @Test
    fun `normal mode sends the text untouched`() {
        val submission = ComposerModePayloads.submission(ComposerMode.Normal, "你好")
        assertEquals("你好", submission.message)
        assertNull(submission.agentMessage)
    }

    @Test
    fun `plan mode keeps the message clean and puts the marker in agentMessage`() {
        val submission = ComposerModePayloads.submission(ComposerMode.Plan, "  把设置页拆了 ")
        assertEquals("  把设置页拆了 ", submission.message)
        val hidden = submission.agentMessage.orEmpty()
        assertTrue(hidden.startsWith(ComposerModePayloads.PLAN_MARKER))
        assertTrue(hidden.contains("把设置页拆了"))
        assertTrue(hidden.contains("Plan:"))
        assertEquals("把设置页拆了", submission.display)
    }

    @Test
    fun `plan mode with empty text still sends a usable message`() {
        val submission = ComposerModePayloads.submission(ComposerMode.Plan, "   ")
        assertEquals("请根据已附加的图片或上下文先制定实施计划。", submission.message)
        assertTrue(submission.agentMessage.orEmpty().contains("请先只做只读分析"))
    }

    @Test
    fun `goal mode uses the goal marker`() {
        val submission = ComposerModePayloads.submission(ComposerMode.Goal, "把测试补到 500 条")
        assertEquals(
            ComposerModePayloads.GOAL_MARKER + "\n把测试补到 500 条",
            submission.agentMessage,
        )
        assertEquals("把测试补到 500 条", submission.message)
    }

    @Test
    fun `slash commands never get a mode wrapper`() {
        // 宿主自己解析命令，套上标记会让它变成普通消息。
        val submission = ComposerModePayloads.submission(ComposerMode.Plan, "/compact")
        assertEquals("/compact", submission.message)
        assertNull(submission.agentMessage)
    }

    @Test
    fun `legacy messages with the marker still display clean`() {
        val legacy = listOf(
            ComposerModePayloads.PLAN_MARKER,
            "把设置页拆了",
            "",
            "请先只做只读分析，不要修改文件。最后必须输出以 `Plan:` 开头的编号计划，格式如下：\n" +
                "Plan:\n1. 第一步\n2. 第二步",
        ).joinToString("\n")
        assertEquals("把设置页拆了", ComposerModePayloads.displayText(legacy))
        assertEquals(
            "把测试补到 500 条",
            ComposerModePayloads.displayText(ComposerModePayloads.GOAL_MARKER + "\n把测试补到 500 条"),
        )
    }

    @Test
    fun `plain text that merely mentions the marker is left alone`() {
        val text = "我在文档里看到 __PI_DECK_PLAN_MODE__ 这个串，它是什么"
        assertEquals(text, ComposerModePayloads.displayText(text))
    }

    @Test
    fun `hasPlan recognises both Plan and 计划 headings`() {
        assertTrue(ComposerModePayloads.hasPlan("先说结论。\n\nPlan:\n1. 甲\n2. 乙"))
        assertTrue(ComposerModePayloads.hasPlan("计划：\n1. 甲"))
        assertTrue(!ComposerModePayloads.hasPlan("这个方案 plan: 还行"))
    }
}
