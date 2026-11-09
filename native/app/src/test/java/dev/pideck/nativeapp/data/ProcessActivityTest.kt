package dev.pideck.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProcessActivityTest {

    @Test
    fun `组头细节优先取命令`() {
        val json = """{"command":"npm run build","path":"README.md"}"""
        assertEquals("npm run build", ProcessActivity.detailFromArgs(json))
    }

    @Test
    fun `没有命令就取路径`() {
        val json = """{"file_path":"C:/work/app/src/Main.kt","limit":40}"""
        assertEquals("C:/work/app/src/Main.kt", ProcessActivity.detailFromArgs(json))
    }

    @Test
    fun `没有常见字段就取第一个字符串值`() {
        val json = """{"weird_field":"hello world"}"""
        assertEquals("hello world", ProcessActivity.detailFromArgs(json))
    }

    @Test
    fun `多行命令压成一行`() {
        val json = """{"command":"cd app\nnpm test"}"""
        assertEquals("cd app · npm test", ProcessActivity.detailFromArgs(json))
    }

    @Test
    fun `超长参数截断`() {
        val json = """{"command":"${"x".repeat(200)}"}"""
        val detail = ProcessActivity.detailFromArgs(json)!!
        assertEquals(91, detail.length)
        assertEquals("…", detail.takeLast(1))
    }

    @Test
    fun `拿不到参数就是 null`() {
        assertNull(ProcessActivity.detailFromArgs(null))
        assertNull(ProcessActivity.detailFromArgs("   "))
        assertNull(ProcessActivity.detailFromArgs("not json{"))
    }

    @Test
    fun `工具没带参数时没有细节`() {
        assertNull(ProcessActivity.toolDetail(ToolView(id = "t", name = "read", state = "running")))
    }

    @Test
    fun `挂载预算从末尾往前留`() {
        // 40 条成员：先给最后 32 条，点一次「显示更早」再多 32（超了就全给）
        assertEquals(32, visibleMemberCount(total = 40, batches = 0))
        assertEquals(40, visibleMemberCount(total = 40, batches = 1))
        assertEquals(10, visibleMemberCount(total = 10, batches = 0))
        assertEquals(0, visibleMemberCount(total = 0, batches = 0))
        // 负数批次当 0 处理
        assertEquals(32, visibleMemberCount(total = 40, batches = -3))
    }
}
