package dev.pideck.nativeapp.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `gfm table becomes a table block with alignment`() {
        val source = """
            | 项目 | 版本 | 备注 |
            |:-----|:----:|-----:|
            | 桌面端 | 0.9.9 | 已同步 |
            | 手机端 | 0.5.23 | 待发 |
        """.trimIndent()
        val table = parseMarkdown(source).single() as MdBlock.Table
        assertEquals(listOf("项目", "版本", "备注"), table.headers)
        assertEquals(
            listOf(TableCellAlign.LEFT, TableCellAlign.CENTER, TableCellAlign.RIGHT),
            table.aligns,
        )
        assertEquals(2, table.rows.size)
        assertEquals(listOf("桌面端", "0.9.9", "已同步"), table.rows[0])
        assertEquals(listOf("手机端", "0.5.23", "待发"), table.rows[1])
    }

    @Test
    fun `table stops at blank line and keeps following paragraph`() {
        val source = "| a | b |\n|---|---|\n| 1 | 2 |\n\n后面这段是正文。"
        val blocks = parseMarkdown(source)
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is MdBlock.Table)
        assertEquals("后面这段是正文。", (blocks[1] as MdBlock.Paragraph).text)
    }

    @Test
    fun `pipe without delimiter row stays a paragraph`() {
        val blocks = parseMarkdown("这不是表格 | 只是竖线\n第二行")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MdBlock.Paragraph)
    }

    @Test
    fun `ragged table rows are padded to the header width`() {
        val source = "| a | b | c |\n|---|---|---|\n| 1 |\n| 1 | 2 | 3 | 4 |"
        val table = parseMarkdown(source).single() as MdBlock.Table
        assertEquals(listOf("1", "", ""), table.rows[0])
        assertEquals(listOf("1", "2", "3"), table.rows[1])
    }

    @Test
    fun `escaped pipe stays inside a cell`() {
        val source = "| 命令 | 说明 |\n|---|---|\n| a \\| b | 竖线 |"
        val table = parseMarkdown(source).single() as MdBlock.Table
        assertEquals(listOf("a | b", "竖线"), table.rows.single())
    }

    @Test
    fun `table inside a fenced block is code`() {
        val blocks = parseMarkdown("```md\n| a | b |\n|---|---|\n```")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MdBlock.Code)
    }

    @Test
    fun `无表头的表（键值对照）照样成表并从数据行开始`() {
        // 用户实际报的那个：表头是全空的 `| | |`，旧实现因为 headers 全空而整张不认
        val source = """
            | | |
            |---|---|
            | 文件 | `apk/PiDeck-native-0.5.32-release.apk` |
            | 版本 | 0.5.32 / versionCode **532** |
        """.trimIndent()
        val table = parseMarkdown(source).single() as MdBlock.Table
        assertEquals(2, table.headers.size)
        assertFalse("表头全空 → 不画表头行", table.showHeader)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("文件", "`apk/PiDeck-native-0.5.32-release.apk`"), table.rows[0])
        assertEquals(listOf("版本", "0.5.32 / versionCode **532**"), table.rows[1])
    }

    @Test
    fun `表头正常时 showHeader 为 true`() {
        val table = parseMarkdown("| a | b |\n|---|---|\n| 1 | 2 |").single() as MdBlock.Table
        assertTrue(table.showHeader)
    }

    @Test
    fun `只有分隔行没有数据行时当段落`() {
        val blocks = parseMarkdown("| a | b |\n|---|---|")
        assertTrue("空盒子不如就当普通文本", blocks.single() is MdBlock.Paragraph)
    }

    @Test
    fun `blank source yields nothing`() {
        assertTrue(parseMarkdown("   ").isEmpty())
    }
}
