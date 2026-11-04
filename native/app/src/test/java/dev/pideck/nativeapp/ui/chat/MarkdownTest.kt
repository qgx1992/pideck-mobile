package dev.pideck.nativeapp.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解析器只认 agent 回复里真会出现的形状；流式未闭合的代码块也要能正常显示
 * （否则用户会看到半个 ``` 在闪）。
 */
class MarkdownTest {

    @Test
    fun `fenced code keeps language and body verbatim`() {
        val blocks = parseMarkdown("先看代码：\n```kotlin\nval a = 1\nprintln(a)\n```\n改完了。")
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is MdBlock.Paragraph)
        val code = blocks[1] as MdBlock.Code
        assertEquals("kotlin", code.lang)
        assertEquals("val a = 1\nprintln(a)", code.code)
        assertEquals("改完了。", (blocks[2] as MdBlock.Paragraph).text)
    }

    @Test
    fun `unterminated fence still renders as code`() {
        val blocks = parseMarkdown("跑一下：\n```bash\npnpm test\n```")
        assertEquals(2, blocks.size)
        assertEquals("pnpm test", (blocks[1] as MdBlock.Code).code)
    }

    @Test
    fun `streaming half-open fence does not leak backticks`() {
        val blocks = parseMarkdown("写好了：\n```ts\nconst x")
        val code = blocks.last() as MdBlock.Code
        assertEquals("ts", code.lang)
        assertEquals("const x", code.code)
    }

    @Test
    fun `headings lists quotes and rules`() {
        val source = """
            ## 标题
            - 第一条
            - 第二条

            > 引用一行
            > 引用两行

            ---
        """.trimIndent()
        val blocks = parseMarkdown(source)
        assertEquals(MdBlock.Heading(2, "标题"), blocks[0])
        assertEquals(MdBlock.ListBlock(ordered = false, items = listOf("第一条", "第二条")), blocks[1])
        assertEquals(MdBlock.Quote("引用一行\n引用两行"), blocks[2])
        assertEquals(MdBlock.Rule, blocks[3])
    }

    @Test
    fun `ordered list numbers are parsed`() {
        val blocks = parseMarkdown("1. 甲\n2. 乙\n3. 丙")
        assertEquals(MdBlock.ListBlock(ordered = true, items = listOf("甲", "乙", "丙")), blocks.single())
    }

    @Test
    fun `inline bold and code lose their markers`() {
        val text = inlineMarkdown("这是 **重点** 和 `code` 混排").text
        assertEquals("这是 重点 和 code 混排", text)
    }

    @Test
    fun `blank source yields nothing`() {
        assertTrue(parseMarkdown("   ").isEmpty())
    }
}
