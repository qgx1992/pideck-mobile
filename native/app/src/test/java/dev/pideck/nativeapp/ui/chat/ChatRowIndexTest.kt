package dev.pideck.nativeapp.ui.chat

import dev.pideck.nativeapp.data.TimelineItem
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 历史面板「点一条跳回去」的下标换算。
 *
 * 曾经的 bug：`rows.indexOfFirst { it.key == turn.key }` 直接当 LazyColumn 下标用。
 * 列表顶上挂着「加载更早的消息」时，LazyColumn 的第 0 项是那个按钮，rows[0] 其实是第 1 项 ——
 * 于是点每一条都落到上一条。这个文件把两种情况都钉住。
 */
class ChatRowIndexTest {

    private val u1 = TimelineItem.UserMessage(key = "u1", text = "第一句", timestamp = 1_000L)
    private val a1 = TimelineItem.AssistantMessage(key = "a1", text = "回答一")
    private val u2 = TimelineItem.UserMessage(key = "u2", text = "第二句", timestamp = 2_000L)

    private fun rows(vararg items: TimelineItem): List<ChatRow> = buildChatRows(items.toList(), null)

    @Test
    fun `没有前导项时下标就是行下标`() {
        val list = rows(u1, a1, u2)
        assertEquals(0, historyListIndex(list, "u1"))
        assertEquals(2, historyListIndex(list, "u2"))
    }

    @Test
    fun `顶上挂着加载更早的消息时整体下移一位`() {
        val list = rows(u1, a1, u2)
        // 真实列表：[「加载更早的消息」, u1, a1, u2]
        assertEquals(1, historyListIndex(list, "u1", leadingItems = 1))
        assertEquals(3, historyListIndex(list, "u2", leadingItems = 1))
    }

    @Test
    fun `找不到的 key 返回 -1 而不是 0`() {
        val list = rows(u1, a1)
        assertEquals(-1, historyListIndex(list, "不存在的提问", leadingItems = 1))
    }

    @Test
    fun `过程组用的是 grp 前缀，不会抢走提问的 key`() {
        val thinking = TimelineItem.AssistantMessage(key = "a2", text = "", thinking = "想一想")
        val list = rows(u1, a1, thinking, u2)
        // a1 有正文单独成行；thinking 没有正文 → 折成过程组，key 变成 grp:a2
        assertEquals("grp:a2", list[2].key)
        assertEquals(3, historyListIndex(list, "u2"))
    }
}
