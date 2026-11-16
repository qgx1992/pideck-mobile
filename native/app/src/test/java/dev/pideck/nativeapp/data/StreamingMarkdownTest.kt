package dev.pideck.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingMarkdownTest {
    @Test
    fun `unclosed code fence is closed`() {
        val fixed = repairStreamingMarkdown("先看代码：\n```kotlin\nval a = 1")
        assertEquals("先看代码：\n```kotlin\nval a = 1\n```", fixed)
    }

    @Test
    fun `balanced code fence is left alone`() {
        val text = "```\ncode\n```"
        assertEquals(text, repairStreamingMarkdown(text))
    }

    @Test
    fun `unclosed bold is closed`() {
        assertTrue(repairStreamingMarkdown("这是**重点").endsWith("**"))
    }

    @Test
    fun `balanced bold is left alone`() {
        val text = "这是**重点**，后面还有"
        assertEquals(text, repairStreamingMarkdown(text))
    }

    @Test
    fun `unclosed inline code is closed but fences are ignored`() {
        val text = "命令是 `ls -la 然后"
        assertTrue(repairStreamingMarkdown(text).endsWith("`"))
        // 围栏里的反引号不参与行内判断
        val fenced = "```\ncode\n```\n正文 `x"
        assertTrue(repairStreamingMarkdown(fenced).endsWith("`"))
    }

    @Test
    fun `table header without separator gets one`() {
        val fixed = repairStreamingMarkdown("| 项 | 值 |\n| 首字 | 3.5s |")
        assertTrue(fixed.contains("| --- | --- |"))
        assertTrue(fixed.indexOf("| --- | --- |") < fixed.indexOf("| 首字 |"))
    }

    @Test
    fun `table that already has separator is untouched`() {
        val text = "| 项 | 值 |\n| --- | --- |\n| 首字 | 3.5s |"
        assertEquals(text, repairStreamingMarkdown(text))
    }

    @Test
    fun `blank input stays blank`() {
        assertEquals("", repairStreamingMarkdown(""))
        assertEquals("   ", repairStreamingMarkdown("   "))
    }

    @Test
    fun `reparse only when enough new text or waited long enough`() {
        // 新增 40 字、只过了 100ms → 先不解析（省 CPU）
        assertFalse(shouldReparse(shown = 340, parsedAt = 300, sinceLastParseMs = 100))
        // 新增 80 字 → 立刻解析
        assertTrue(shouldReparse(shown = 380, parsedAt = 300, sinceLastParseMs = 40))
        // 等够 400ms → 攒得再少也解析一次
        assertTrue(shouldReparse(shown = 310, parsedAt = 300, sinceLastParseMs = 400))
        // 没有新字 / 还退回去了 → 不解析
        assertFalse(shouldReparse(shown = 300, parsedAt = 300, sinceLastParseMs = 900))
        // 第一次解析
        assertTrue(shouldReparse(shown = 1, parsedAt = -1, sinceLastParseMs = 0))
    }
}
