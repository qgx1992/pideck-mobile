package dev.pideck.nativeapp.ui.chat

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 草稿箱的键是「设备 + 会话」，不是只有会话。
 *
 * 两台电脑上完全可能各有同名的 uuid；只按 sessionId 存就会串台
 * （A 电脑写了一半的话出现在 B 电脑的输入框里）。
 */
class ChatDraftsTest {

    @After
    fun tearDown() {
        ChatDrafts.resetForTest()
    }

    @Test
    fun `same session id on two devices does not share a draft`() {
        ChatDrafts.put("device-a", "s1", "电脑 A 上没写完的话")
        assertEquals("电脑 A 上没写完的话", ChatDrafts.get("device-a", "s1"))
        assertEquals("", ChatDrafts.get("device-b", "s1"))
    }

    @Test
    fun `sending clears only that conversation`() {
        ChatDrafts.put("d1", "s1", "第一句")
        ChatDrafts.put("d1", "s2", "第二句")
        ChatDrafts.clear("d1", "s1")
        assertEquals("", ChatDrafts.get("d1", "s1"))
        assertEquals("第二句", ChatDrafts.get("d1", "s2"))
    }

    @Test
    fun `empty text drops the entry`() {
        ChatDrafts.put("d1", "s1", "写了点东西")
        ChatDrafts.put("d1", "s1", "")
        assertEquals("", ChatDrafts.get("d1", "s1"))
    }

    @Test
    fun `unknown conversation reads as empty`() {
        assertEquals("", ChatDrafts.get("d1", "没见过"))
    }
}
