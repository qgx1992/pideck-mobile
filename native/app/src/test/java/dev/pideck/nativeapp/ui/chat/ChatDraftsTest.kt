package dev.pideck.nativeapp.ui.chat

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDraftsTest {

    @After
    fun clean() {
        ChatDrafts.clear("a")
        ChatDrafts.clear("b")
    }

    @Test
    fun `每个会话各自保留`() {
        ChatDrafts.put("a", "写一半的话")
        ChatDrafts.put("b", "另一条会话的草稿")
        assertEquals("写一半的话", ChatDrafts.get("a"))
        assertEquals("另一条会话的草稿", ChatDrafts.get("b"))
    }

    @Test
    fun `没有草稿时是空串`() {
        assertEquals("", ChatDrafts.get("a"))
    }

    @Test
    fun `清空之后取回空串`() {
        ChatDrafts.put("a", "x")
        ChatDrafts.clear("a")
        assertEquals("", ChatDrafts.get("a"))
    }

    @Test
    fun `写空内容等于删除`() {
        ChatDrafts.put("a", "x")
        ChatDrafts.put("a", "")
        assertEquals("", ChatDrafts.get("a"))
    }

    @Test
    fun `条目数有上限，不会无限堆积`() {
        repeat(60) { ChatDrafts.put("k$it", "v$it") }
        assertTrue(ChatDrafts.get("k0").isEmpty())
        assertEquals("v59", ChatDrafts.get("k59"))
    }
}
