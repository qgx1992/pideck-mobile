package dev.pideck.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerModeTest {
    @Test
    fun normalModeKeepsText() {
        assertEquals("你好", ComposerModePayloads.wrap(ComposerMode.Normal, "你好"))
    }

    @Test
    fun planModeCarriesMarkerAndPlanInstruction() {
        val text = ComposerModePayloads.wrap(ComposerMode.Plan, "  把设置页拆了 ")
        assertTrue(text.startsWith(ComposerModePayloads.PLAN_MARKER))
        assertTrue(text.contains("把设置页拆了"))
        assertTrue(text.contains("请先只做只读分析"))
        assertTrue(text.contains("Plan:"))
    }

    @Test
    fun planModeWithEmptyTextUsesDefaultInstruction() {
        val text = ComposerModePayloads.wrap(ComposerMode.Plan, "   ")
        assertTrue(text.contains("请根据已附加的图片或上下文先制定实施计划。"))
    }

    @Test
    fun goalModeCarriesMarker() {
        val text = ComposerModePayloads.wrap(ComposerMode.Goal, "把测试补到 500 条")
        assertEquals(
            ComposerModePayloads.GOAL_MARKER + "\n把测试补到 500 条",
            text,
        )
        assertEquals(
            ComposerModePayloads.GOAL_MARKER + "\n请继续当前目标。",
            ComposerModePayloads.wrap(ComposerMode.Goal, ""),
        )
    }

    @Test
    fun displayTextHidesGeneratedPayload() {
        assertEquals("把设置页拆了", ComposerModePayloads.displayText(
            ComposerModePayloads.wrap(ComposerMode.Plan, "把设置页拆了"),
        ))
        assertEquals("把测试补到 500 条", ComposerModePayloads.displayText(
            ComposerModePayloads.wrap(ComposerMode.Goal, "把测试补到 500 条"),
        ))
    }

    @Test
    fun displayTextLeavesOrdinaryTextAlone() {
        val plain = "为什么 __PI_DECK_PLAN_MODE__ 不是命令"
        assertEquals(plain, ComposerModePayloads.displayText(plain))
    }

    @Test
    fun hasPlanLooksForPlanHeading() {
        assertTrue(ComposerModePayloads.hasPlan("先看现状。\n\nPlan:\n1. 第一步\n2. 第二步"))
        assertTrue(ComposerModePayloads.hasPlan("计划：\n1. 改 A\n2. 改 B"))
        assertFalse(ComposerModePayloads.hasPlan("只有分析和结论，没有编号计划"))
        assertFalse(ComposerModePayloads.hasPlan(""))
    }
}
