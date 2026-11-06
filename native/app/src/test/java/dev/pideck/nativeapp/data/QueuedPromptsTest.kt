package dev.pideck.nativeapp.data

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「排队到下一轮」的对账口径。
 *
 * 钉住两件事：
 * 1. 对账键是消息序号（`meta._piDeckMsgSeq`），不是文本 —— 同一句话发两次，
 *    一条落盘只抵掉一条排队；
 * 2. 入队之前就存在的同文消息（序号 ≤ 基线）不算到达。
 */
class QueuedPromptsTest {

    private fun message(text: String, seq: Long?): ChatMessage = ChatMessage(
        id = "m-$seq",
        role = "user",
        text = text,
        meta = seq?.let { value ->
            buildJsonObject { put("_piDeckMsgSeq", value) }
        },
    )

    private fun assistant(text: String, seq: Long? = null): ChatMessage = ChatMessage(
        id = "a-$seq",
        role = "assistant",
        text = text,
        meta = seq?.let { value -> buildJsonObject { put("_piDeckMsgSeq", value) } },
    )

    @Test
    fun baselineIsMaxSeq() {
        assertEquals(7L, QueuedPrompts.baselineOf(listOf(message("a", 3), message("b", 7), message("c", 5))))
        assertEquals(0L, QueuedPrompts.baselineOf(listOf(message("a", null))))
        assertEquals(0L, QueuedPrompts.baselineOf(emptyList()))
    }

    @Test
    fun landedMessageDropsItsQueueEntry() {
        val queued = listOf(QueuedPrompt("写个测试", 4))
        val rest = QueuedPrompts.afterLanding(queued, listOf(message("写个测试", 9)))
        assertEquals(emptyList<QueuedPrompt>(), rest)
    }

    @Test
    fun sameTextTwiceOnlyDropsOne() {
        // 同一句话排队两次：只有一条落盘，另一条必须还挂着（按文本对账会一次消两条）
        val queued = listOf(QueuedPrompt("继续", 4), QueuedPrompt("继续", 4))
        val rest = QueuedPrompts.afterLanding(queued, listOf(message("继续", 9)))
        assertEquals(listOf(QueuedPrompt("继续", 4)), rest)
    }

    @Test
    fun messageFromBeforeEnqueueDoesNotCount() {
        // 基线之前的同文消息是上一轮的历史，不是这条排队的抵达证据
        val queued = listOf(QueuedPrompt("继续", 7))
        val rest = QueuedPrompts.afterLanding(queued, listOf(message("继续", 5)))
        assertEquals(queued, rest)
    }

    @Test
    fun assistantEchoDoesNotDropQueue() {
        // 回答里回显了这句话不算到达：只认 role=user
        val queued = listOf(QueuedPrompt("继续", 1))
        val rest = QueuedPrompts.afterLanding(queued, listOf(assistant("继续", 9)))
        assertEquals(queued, rest)
    }

    @Test
    fun otherPeoplesMessageDoesNotDropQueue() {
        // 别的设备插了一句（文本不同），本次排队的那条仍在
        val queued = listOf(QueuedPrompt("继续", 1))
        val rest = QueuedPrompts.afterLanding(queued, listOf(message("别人的问题", 9)))
        assertEquals(queued, rest)
    }

    @Test
    fun fallsBackToCountingWithoutSeq() {
        // 老宿主没有序号：一条已落盘只抵一条排队
        val queued = listOf(QueuedPrompt("继续", 0), QueuedPrompt("继续", 0))
        val rest = QueuedPrompts.afterLanding(queued, listOf(message("继续", null)))
        assertEquals(1, rest.size)
    }

    @Test
    fun keepsOrderOfRemainingEntries() {
        val queued = listOf(QueuedPrompt("一", 1), QueuedPrompt("二", 1), QueuedPrompt("三", 1))
        val rest = QueuedPrompts.afterLanding(queued, listOf(message("二", 5)))
        assertEquals(listOf("一", "三"), rest.map { it.text })
    }
}
