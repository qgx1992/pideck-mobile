package dev.pideck.nativeapp.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.JsonObject

/**
 * 帧形状直接取自上游 `webExternalStream.ts` 的 `ExternalReplyMerger` 语义对账，
 * 每条断言对应一条真实 SSE 帧序列。
 */
class ReplyMergerTest {

    private fun frame(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    private fun merger() = ReplyMerger(idFactory = { "msg-1" })

    @Test
    fun `text deltas accumulate into one message`() {
        val merger = merger()
        assertNull(merger.snapshot())
        merger.applyFrame(frame("""{"type":"start","messageId":"srv-1"}"""))
        merger.applyFrame(frame("""{"type":"text-start"}"""))
        merger.applyFrame(frame("""{"type":"text-delta","delta":"你好"}"""))
        merger.applyFrame(frame("""{"type":"text-delta","delta":"，世界"}"""))
        val snapshot = merger.snapshot()!!
        assertEquals("srv-1", snapshot.messageId)
        assertEquals("你好，世界", snapshot.text)
        assertEquals(1, snapshot.parts.size)
        assertFalse(snapshot.finished)
    }

    @Test
    fun `reasoning deltas stay separate from text and close on reasoning-end`() {
        val merger = merger()
        merger.applyFrame(frame("""{"type":"reasoning-start"}"""))
        merger.applyFrame(frame("""{"type":"reasoning-delta","delta":"先看"}"""))
        merger.applyFrame(frame("""{"type":"reasoning-delta","delta":"文件"}"""))
        merger.applyFrame(frame("""{"type":"reasoning-end"}"""))
        merger.applyFrame(frame("""{"type":"text-start"}"""))
        merger.applyFrame(frame("""{"type":"text-delta","delta":"结论"}"""))
        val snapshot = merger.snapshot()!!
        assertEquals("先看文件", snapshot.reasoning)
        assertEquals("结论", snapshot.text)
        assertTrue(snapshot.parts.filterIsInstance<ReplyPart.Reasoning>().all { it.done })
    }

    @Test
    fun `tool input phases update one card then output settles it`() {
        val merger = merger()
        merger.applyFrame(frame("""{"type":"tool-input-start","toolCallId":"t1","toolName":"bash"}"""))
        merger.applyFrame(frame("""{"type":"tool-input-available","toolCallId":"t1","toolName":"bash","input":{"command":"ls"}}"""))
        var tool = merger.snapshot()!!.tools.single()
        assertEquals("input-available", tool.state)
        assertEquals("""{"command":"ls"}""", tool.input.toString())
        assertFalse(tool.isSettled)

        merger.applyFrame(frame("""{"type":"tool-output-available","toolCallId":"t1","output":"a.txt"}"""))
        tool = merger.snapshot()!!.tools.single()
        assertEquals("output-available", tool.state)
        assertTrue(tool.isSettled)
        assertEquals(1, merger.snapshot()!!.tools.size)
    }

    @Test
    fun `replayed frames do not resurrect a settled tool`() {
        val merger = merger()
        merger.applyFrame(frame("""{"type":"tool-input-start","toolCallId":"t1","toolName":"bash"}"""))
        merger.applyFrame(frame("""{"type":"tool-output-available","toolCallId":"t1","output":"ok"}"""))
        // 重连后服务端重放整轮：input 帧不能把已完成的卡打回 running，output 也不能重复写。
        merger.applyFrame(frame("""{"type":"tool-input-start","toolCallId":"t1","toolName":"bash"}"""))
        merger.applyFrame(frame("""{"type":"tool-output-available","toolCallId":"t1","output":"dup"}"""))
        val tool = merger.snapshot()!!.tools.single()
        assertEquals("output-available", tool.state)
        assertEquals("\"ok\"", tool.output.toString())
    }

    @Test
    fun `tool output without seen input still shows the result`() {
        // 0.5.56 改的：宿主重连时可能从 output 那一段重放，或中途丢一帧 input。
        // 以前这里整帧丢掉，工具卡永远停在「正在执行」。
        val merger = merger()
        merger.applyFrame(frame("""{"type":"text-start"}"""))
        merger.applyFrame(frame("""{"type":"tool-output-available","toolCallId":"ghost","output":"x"}"""))
        val tool = merger.snapshot()!!.tools.single()
        assertEquals("output-available", tool.state)
        assertEquals("\"x\"", tool.output.toString())
    }

    @Test
    fun `error frame records text and finishes`() {
        val merger = merger()
        merger.applyFrame(frame("""{"type":"text-delta","delta":"半截"}"""))
        merger.applyFrame(frame("""{"type":"error","errorText":"额度用完了"}"""))
        val snapshot = merger.snapshot()!!
        assertTrue(snapshot.finished)
        assertEquals("额度用完了", snapshot.errorText)
        // finish 之后再来帧一律忽略。
        merger.applyFrame(frame("""{"type":"text-delta","delta":"不该出现"}"""))
        assertEquals("半截", merger.snapshot()!!.text)
    }

    @Test
    fun `finish closes open reasoning and empty finish yields nothing`() {
        val empty = merger()
        assertNull(empty.finish())

        val merger = merger()
        merger.applyFrame(frame("""{"type":"reasoning-start"}"""))
        merger.applyFrame(frame("""{"type":"reasoning-delta","delta":"想想"}"""))
        merger.applyFrame(frame("""{"type":"finish"}"""))
        val snapshot = merger.snapshot()!!
        assertTrue(snapshot.finished)
        assertTrue(snapshot.parts.filterIsInstance<ReplyPart.Reasoning>().single().done)
    }

    @Test
    fun `reset drops everything for the next turn`() {
        val merger = merger()
        merger.applyFrame(frame("""{"type":"text-delta","delta":"上一轮"}"""))
        merger.reset()
        assertNull(merger.snapshot())
        merger.applyFrame(frame("""{"type":"text-delta","delta":"新一轮"}"""))
        assertEquals("新一轮", merger.snapshot()!!.text)
    }

    @Test
    fun `output frame without a matching input still builds the tool card`() {
        // 重连时宿主可能从 output 那一段开始重放；缺 input 不能把整帧丢掉，
        // 否则工具卡永远停在「正在执行」。
        val merger = merger()
        merger.applyFrame(frame("""{"type":"start","messageId":"srv-1"}"""))
        merger.applyFrame(
            frame("""{"type":"tool-output-available","toolCallId":"t1","toolName":"bash","output":"ok"}"""),
        )
        val snapshot = merger.snapshot()!!
        val tool = snapshot.parts.filterIsInstance<ReplyPart.Tool>().single()
        assertEquals("t1", tool.toolCallId)
        assertEquals("bash", tool.toolName)
        assertEquals("output-available", tool.state)
    }
}
