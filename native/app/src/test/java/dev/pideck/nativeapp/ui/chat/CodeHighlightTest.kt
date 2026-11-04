package dev.pideck.nativeapp.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeHighlightTest {

    private fun kinds(code: String, lang: String?): List<TokenKind> =
        CodeHighlight.tokenize(code, lang).map { it.kind }

    @Test
    fun `kotlin 关键词与类型分开`() {
        val tokens = CodeHighlight.tokenize("val price = Money(1)", "kotlin")
        val words = tokens.associateBy { "val price = Money(1)".substring(it.start, it.end) }
        assertEquals(TokenKind.Keyword, words["val"]?.kind)
        assertEquals(TokenKind.Type, words["Money"]?.kind)
        assertEquals(TokenKind.Number, words["1"]?.kind)
    }

    @Test
    fun `字符串里的内容不再当关键词`() {
        val code = """val a = "val class fun""""
        val tokens = CodeHighlight.tokenize(code, "kotlin")
        // 只有一个 val 是关键词（字符串外的那个）
        assertEquals(1, tokens.count { it.kind == TokenKind.Keyword })
        assertTrue(tokens.any { it.kind == TokenKind.String })
    }

    @Test
    fun `斜杠注释与块注释都认`() {
        assertTrue(TokenKind.Comment in kinds("val a = 1 // note", "kotlin"))
        assertTrue(TokenKind.Comment in kinds("/* multi\n line */ val a = 1", "kotlin"))
    }

    @Test
    fun `井号注释只在脚本类语言里认`() {
        assertTrue(TokenKind.Comment in kinds("# comment\nprint(1)", "python"))
        // CSS 里的颜色值不能被当成注释
        assertEquals(emptyList<TokenKind>(), kinds("color: #fff;", "css").filter { it == TokenKind.Comment })
    }

    @Test
    fun `语言别名能对上关键词表`() {
        assertTrue(TokenKind.Keyword in kinds("const a = 1", "ts"))
        assertTrue(TokenKind.Keyword in kinds("const a = 1", "js"))
        assertTrue(TokenKind.Keyword in kinds("def f(): pass", "py"))
        assertTrue(TokenKind.Keyword in kinds("const a = 1", "language-typescript"))
    }

    @Test
    fun `未知语言只上字符串数字注释色`() {
        val tokens = CodeHighlight.tokenize("foo 42 \"bar\"", "brainfuck")
        assertEquals(listOf(TokenKind.Number, TokenKind.String), tokens.map { it.kind })
    }

    @Test
    fun `diff 语言被识别`() {
        assertTrue(CodeHighlight.isDiff("diff"))
        assertTrue(CodeHighlight.isDiff("patch"))
        assertTrue(!CodeHighlight.isDiff("kotlin"))
        assertTrue(!CodeHighlight.isDiff(null))
    }

    @Test
    fun `token 覆盖原文且不重叠`() {
        val code = "fun main() {\n  println(\"hi\")\n}"
        var last = 0
        for (token in CodeHighlight.tokenize(code, "kotlin")) {
            assertTrue(token.start >= last)
            assertTrue(token.end > token.start)
            last = token.end
        }
        assertTrue(last <= code.length)
    }
}
