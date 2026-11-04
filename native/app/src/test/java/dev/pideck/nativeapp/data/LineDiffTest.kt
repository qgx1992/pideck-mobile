package dev.pideck.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LineDiffTest {

    private fun lines(vararg text: String) = text.joinToString("\n")

    @Test
    fun `完全相同时没有 diff`() {
        assertTrue(LineDiff.unified("a\nb", "a\nb").isEmpty())
    }

    @Test
    fun `中间改一行`() {
        val original = lines("one", "two", "three", "four", "five", "six", "seven", "eight")
        val modified = lines("one", "two", "THREE", "four", "five", "six", "seven", "eight")
        val text = LineDiff.render(original, modified)
        assertTrue(text.startsWith("@@ -1,"))
        assertTrue(text.contains("-three"))
        assertTrue(text.contains("+THREE"))
        // 上下文之外的远端行不该出现
        assertTrue(text.contains(" one"))
    }

    @Test
    fun `末尾追加行`() {
        val text = LineDiff.render(lines("a", "b"), lines("a", "b", "c"))
        assertTrue(text.contains("+c"))
        assertTrue(!text.contains("-a"))
    }

    @Test
    fun `纯新增文件`() {
        val text = LineDiff.render("", lines("x", "y"))
        assertTrue(text.contains("+x"))
        assertTrue(text.contains("+y"))
    }

    @Test
    fun `@@ 头的行号递增合理`() {
        val original = (1..20).joinToString("\n") { "line$it" }
        val modified = original.replace("line10", "changed")
        val diff = LineDiff.unified(original, modified)
        val header = diff.first { it.kind == LineDiff.Kind.Hunk }.text
        // 上下文 3 行 + 1 行改动 + 3 行 = 7 行；起点是 7（line7）
        assertTrue(header, header.contains("-7,7"))
        assertTrue(header, header.contains("+7,7"))
    }

    @Test
    fun `payload 解析原始与修改后的内容`() {
        val json = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"path":"a.txt","originalContent":"x\ny","modifiedContent":"x\nz"}""",
        )
        val payload = LineDiff.parsePayload(json)
        assertEquals("a.txt", payload?.path)
        assertEquals("x\ny", payload?.original)
        assertEquals("x\nz", payload?.modified)
    }

    @Test
    fun `缺字段返回 null`() {
        val json = kotlinx.serialization.json.Json.parseToJsonElement("""{"path":"a.txt"}""")
        val payload = LineDiff.parsePayload(json)
        // original / modified 缺失时按空串处理，但 path 必须有
        assertEquals("", payload?.original)
        assertEquals("", payload?.modified)
    }
}
