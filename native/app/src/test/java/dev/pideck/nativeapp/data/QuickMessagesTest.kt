package dev.pideck.nativeapp.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 快捷消息清单（本机偏好，没有 Context 时退回种子清单）。
 *
 * 它是「一句话，随手点」的东西：去重、限长、限条数，删掉就没了 —— 不要在界面上
 * 留「已删除但还显示」的中间态，所以这几个约束都在这里钉住。
 */
class QuickMessagesTest {

    @Before
    fun setUp() {
        QuickMessages.resetForTest(null)
    }

    @After
    fun tearDown() {
        QuickMessages.resetForTest(null)
    }

    @Test
    fun `没有存储时给一份可用的种子清单`() {
        val items = QuickMessages.load()
        assertTrue(items.isNotEmpty())
        assertTrue(items.size <= QuickMessages.MAX_COUNT)
    }

    @Test
    fun `新增会去掉重复、空白忽略`() {
        val first = QuickMessages.add("继续")
        assertEquals(first.size, first.distinct().size)
        val before = first.size
        QuickMessages.add("继续")
        assertEquals(before, QuickMessages.load().size)
        QuickMessages.add("   ")
        assertEquals(before, QuickMessages.load().size)
    }

    @Test
    fun `超长的一条按上限截断`() {
        val long = "好".repeat(QuickMessages.MAX_LENGTH + 50)
        val items = QuickMessages.add(long)
        assertEquals(QuickMessages.MAX_LENGTH, items.last().length)
    }

    @Test
    fun `条数上限之外的被挤掉，删掉还能再删`() {
        repeat(QuickMessages.MAX_COUNT + 3) { index -> QuickMessages.add("第 $index 条") }
        val items = QuickMessages.load()
        assertEquals(QuickMessages.MAX_COUNT, items.size)
        val removed = QuickMessages.remove(items.first())
        assertFalse(items.first() in removed)
    }

    @Test
    fun `带反斜杠 n 的快捷消息原样存回来`() {
        val items = listOf("printf(\"a\\nb\")", "换行:\n第二行")
        val back = QuickMessages.decode(QuickMessages.encode(items))
        assertEquals(items, back)
    }

    @Test
    fun `升级前存的老格式还能读出来`() {
        val back = QuickMessages.decode("继续\u001e第一行\\n第二行")
        assertEquals(listOf("继续", "第一行\n第二行"), back)
    }
}
