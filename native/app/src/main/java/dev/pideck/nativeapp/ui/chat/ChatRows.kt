package dev.pideck.nativeapp.ui.chat

import dev.pideck.nativeapp.data.ProcessActivity
import dev.pideck.nativeapp.data.TimelineItem

/**
 * 时间线的「行」：单条消息，或者一个**过程组**。
 *
 * 过程组对齐桌面端 `components/session/timeline/groupTurnProcess.ts` 的语义：
 * 连续的过程条目（思考 / 工具调用，也就是「没有正文的 assistant 消息」）合并成一组，
 * 组头给类别摘要、组体默认收起；有正文的回答是组的一级兄弟节点，不折进组里。
 */
sealed interface ChatRow {
    val key: String

    data class Single(val item: TimelineItem) : ChatRow {
        override val key: String get() = item.key
    }

    data class Process(
        override val key: String,
        val steps: List<TimelineItem.AssistantMessage>,
        val streaming: Boolean,
    ) : ChatRow {
        val toolNames: List<String>
            get() = steps.flatMap { step -> step.tools.map { it.name.ifBlank { "tool" } } }

        val toolCount: Int get() = steps.sumOf { it.tools.size }

        val thinkingCount: Int get() = steps.count { it.thinking?.isNotBlank() == true }

        /** 组头摘要文案（运行中给「正在读取…」）。 */
        val summary: String
            get() = ProcessActivity.summary(
                toolNames = toolNames,
                thinkingCount = thinkingCount,
                running = streaming,
            )

        /** 组跨度（毫秒）：只用于「· 1.2s」这类提示，时间戳缺失就是 0。 */
        val durationMs: Long
            get() {
                val first = steps.firstOrNull()?.timestamp ?: 0
                val last = steps.lastOrNull()?.timestamp ?: 0
                return if (first > 0 && last > first) last - first else 0
            }
    }
}

/**
 * 把 items + 正在流式的那条拍平成行序列。
 *
 * `live` 传进来时会被追加到末尾（它自己也是一条 assistant 消息）；正文还没开始冒的
 * 流式回复会自然并入最后一个过程组，等到出正文就单独成行 —— 与桌面端的「中间回复是组的一级兄弟」一致。
 */
fun buildChatRows(
    items: List<TimelineItem>,
    live: TimelineItem.AssistantMessage?,
): List<ChatRow> {
    val all = ArrayList<TimelineItem>(items.size + 1)
    all.addAll(items)
    if (live != null) all.add(live)

    val rows = ArrayList<ChatRow>(all.size)
    var pending = ArrayList<TimelineItem.AssistantMessage>()

    fun flush() {
        if (pending.isEmpty()) return
        rows += ChatRow.Process(
            key = "grp:" + pending.first().key,
            steps = pending.toList(),
            streaming = pending.any { it.streaming },
        )
        pending = ArrayList()
    }

    for (item in all) {
        if (item is TimelineItem.AssistantMessage && item.text.isBlank() && !isOnlyError(item)) {
            // 思考**不进折叠组**：它是要读的内容，不是「过程噪音」。
            // 单独成行、按原来的样式显示（一张思考卡），只有工具调用才并进过程组。
            val thinking = item.thinking?.takeIf { it.isNotBlank() }
            if (thinking != null) {
                flush()
                rows += ChatRow.Single(
                    item.copy(thinking = thinking, tools = emptyList(), errorText = null),
                )
            }
            if (item.tools.isNotEmpty() || item.errorText != null) {
                pending += item.copy(thinking = null)
            }
            if (thinking == null && item.tools.isEmpty() && item.errorText == null) {
                pending += item
            }
            continue
        }
        flush()
        rows += ChatRow.Single(item)
    }
    flush()
    return rows
}

/** 只有错误、没有任何过程内容的空白消息：单独一行显示红字，不塞进过程组。 */
private fun isOnlyError(item: TimelineItem.AssistantMessage): Boolean =
    item.text.isBlank() && item.thinking?.isBlank() != false && item.tools.isEmpty() && !item.errorText.isNullOrBlank()
