package dev.pideck.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationTest {

    @Test
    fun `空格不参与统计`() {
        assertEquals(1f, cjkRatio("你 好\n 世 界"), 0.0001f)
        assertEquals(0f, cjkRatio("hello world\n"), 0.0001f)
    }

    @Test
    fun `空串与纯标点不算中文`() {
        assertEquals(0f, cjkRatio(""), 0.0001f)
        assertEquals(0f, cjkRatio("... --- ::"), 0.0001f)
    }

    @Test
    fun `英文正文默认译成中文`() {
        assertEquals(TranslateTarget.Chinese, defaultTranslateTarget("The build is failing on the CI runner."))
    }

    @Test
    fun `中文正文默认译成英文`() {
        assertEquals(TranslateTarget.English, defaultTranslateTarget("先把 native/app 下的单测跑一遍，再看统计行"))
    }

    @Test
    fun `英文里夹几个汉字仍算英文`() {
        // 2 个汉字 / 30 个非空白字符 ≈ 6.7%，低于 15% 这条线
        val text = "Check the `pi` Agent output for 思考 markers in this turn please"
        assertTrue(cjkRatio(text) < 0.15f)
        assertEquals(TranslateTarget.Chinese, defaultTranslateTarget(text))
    }

    @Test
    fun `指令带上目标语言与原文，并要求只输出译文`() {
        val body = translateInstruction("  translate me  ", TranslateTarget.Chinese)
        assertTrue(body.contains("翻译成中文"))
        assertTrue(body.contains("不要使用任何工具"))
        assertTrue(body.endsWith("translate me"))
        assertFalse(body.contains("  translate me"))
    }

    @Test
    fun `译文方向可以来回翻`() {
        assertEquals(TranslateTarget.English, TranslateTarget.Chinese.flipped())
        assertEquals(TranslateTarget.Chinese, TranslateTarget.English.flipped())
    }
}
