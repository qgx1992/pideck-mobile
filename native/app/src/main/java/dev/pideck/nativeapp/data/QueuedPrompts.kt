package dev.pideck.nativeapp.data

/**
 * 一条「排队到下一轮」的消息。
 *
 * `baselineSeq` 是入队那一刻历史里最大的消息序号（老宿主没有序号时是 0）：之后只有
 * **序号更大**的同文消息才算这条落了盘，所以「同一句话发两次」不会一次消掉两条。
 */
data class QueuedPrompt(val text: String, val baselineSeq: Long)

/**
 * 排队消息的对账。
 *
 * 「排队到下一轮」走的是 `prompt` + `streamingBehavior: "followUp"`：宿主只回执「收下了」，
 * 没有后续事件，也**不暴露队列内容**（桌面端的排队面板是它自己攒的账，走 Electron IPC）。
 * 所以手机上「已排队 N 条」只能自己记账，等消息真出现在历史里再撤掉。
 *
 * 对账键用宿主给每条消息写的序号（`meta._piDeckMsgSeq`，全局递增），
 * 而不是文本 —— 按文本对账（0.5.22–0.5.33 的做法）在「同一句话发两次」或
 * 「回答里回显了这句话」时会一次消掉好几条。
 */
object QueuedPrompts {

    /** 入队基线：历史里最大的序号。没有序号（老宿主）时返回 0。 */
    fun baselineOf(messages: List<ChatMessage>): Long =
        messages.mapNotNull { it.seq }.maxOrNull() ?: 0L

    /**
     * 把已经落盘的排队消息去掉。
     *
     * 顺序有意义：`landed` 按序号升序扫一遍，每条落盘消息只抵掉**第一条**同文且基线更小的排队消息，
     * 没落盘的那些原样保留（保持原顺序）。
     */
    fun afterLanding(queued: List<QueuedPrompt>, messages: List<ChatMessage>): List<QueuedPrompt> {
        if (queued.isEmpty()) return queued
        val userMessages = messages.filter { it.role == "user" }
        val landed = userMessages
            .mapNotNull { message -> message.seq?.let { it to message.text } }
            .sortedBy { it.first }
            .filter { (seq, _) -> queued.any { it.baselineSeq < seq } }

        if (landed.isEmpty() && userMessages.none { it.seq != null }) {
            // 老宿主没有序号：退回按出现次数对账（一条落盘最多抵一条排队）。
            val remaining = queued.toMutableList()
            userMessages.groupingBy { it.text }.eachCount().forEach { (text, count) ->
                repeat(minOf(count, remaining.count { it.text == text })) {
                    val index = remaining.indexOfFirst { it.text == text }
                    if (index >= 0) remaining.removeAt(index)
                }
            }
            return remaining
        }

        val remaining = queued.toMutableList()
        landed.forEach { (seq, text) ->
            val index = remaining.indexOfFirst { it.text == text && it.baselineSeq < seq }
            if (index >= 0) remaining.removeAt(index)
        }
        return remaining
    }
}
