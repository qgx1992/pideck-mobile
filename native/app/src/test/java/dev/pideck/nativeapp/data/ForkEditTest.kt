package dev.pideck.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForkEditTest {

    private val flat = """{"result":[{"entryId":"e1","text":"第一句"},{"entryId":"e2","text":"第二句"}]}"""

    @Test
    fun `result as array is parsed`() {
        val points = ForkEdit.points(flat)
        assertEquals(2, points.size)
        assertEquals("e1", points[0].entryId)
        assertEquals("第二句", points[1].text)
    }

    @Test
    fun `result wrapping messages object is parsed`() {
        val raw = """{"result":{"messages":[{"entryId":"a","text":"hi"}]}}"""
        assertEquals("a", ForkEdit.entryIdFor(raw, "hi"))
    }

    @Test
    fun `parses bare messages object`() {
        val raw = """{"messages":[{"entryId":"b","text":"hi"}]}"""
        assertEquals(listOf("b"), ForkEdit.points(raw).map { it.entryId })
    }

    @Test
    fun `same text twice picks the last entry`() {
        val raw = """{"result":[{"entryId":"old","text":"再来一次"},{"entryId":"new","text":"再来一次"}]}"""
        assertEquals("new", ForkEdit.entryIdFor(raw, "再来一次"))
    }

    @Test
    fun `trims both sides before comparing`() {
        assertEquals("e2", ForkEdit.entryIdFor(flat, "  第二句\n"))
    }

    @Test
    fun `unknown or blank text gives null`() {
        assertNull(ForkEdit.entryIdFor(flat, "没说过的话"))
        assertNull(ForkEdit.entryIdFor(flat, "   "))
        assertNull(ForkEdit.entryIdFor("not json", "第一句"))
    }

    @Test
    fun `entries without entryId are dropped`() {
        val raw = """{"result":[{"text":"没有 id"},{"entryId":"","text":"空 id"},{"entryId":"ok","text":"有 id"}]}"""
        assertEquals(listOf("ok"), ForkEdit.points(raw).map { it.entryId })
    }

    @Test
    fun `failure text is surfaced when host reports one`() {
        assertEquals("boom", ForkEdit.failureText("""{"result":{"error":"boom"}}"""))
        assertNull(ForkEdit.failureText(flat))
    }

    @Test
    fun `host 0_11 triple nested payload is parsed`() {
        // 实测回包（0.11.3）：result.ok / value.target / value.value[]
        val raw = """{"result":{"ok":true,"value":{"target":{"sessionId":"s","agentId":"a","runtimeGeneration":1},"value":[{"entryId":"55aef2ba","text":"不用了，我自己操作"},{"entryId":"62a9b848","text":"生图skill是哪个"}]}}}"""
        val points = ForkEdit.points(raw)
        assertEquals(2, points.size)
        assertEquals("55aef2ba", ForkEdit.entryIdFor(raw, "不用了，我自己操作"))
        assertEquals("62a9b848", ForkEdit.entryIdFor(raw, " 生图skill是哪个 "))
    }
}
