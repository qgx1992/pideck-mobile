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
            // 思考与工具调用都算「过程」，一起折进组里；组内思考是展开显示的（见 ProcessGroupBlock），
            // 所以并进组不等于把内容藏起来。
            pending += item
            continue
        }
        flush()
        rows += ChatRow.Single(item)
    }
    flush()
    return rows
}

/**
 * 历史面板里点一条 → LazyColumn 的下标。
 *
 * `rows` 只是列表的**主体**，列表头上还可能有「加载更早的消息」这类前导项，
 * 下标要按 `leadingItems` 平移。以前直接拿 `rows.indexOfFirst` 当 LazyColumn 下标，
 * 于是只要顶上挂着「加载更早的消息」，点哪一条都落到上一条 —— 就是「跳转不准确」。
 *
 * 找不到（例如这条提问已经被折进过程组、或上下文被压缩掉了）时返回 -1，调用方跳过即可。
 */
fun historyListIndex(rows: List<ChatRow>, key: String, leadingItems: Int = 0): Int {
    val index = rows.indexOfFirst { it.key == key }
    return if (index < 0) -1 else index + leadingItems
}

/** 只有错误、没有任何过程内容的空白消息：单独一行显示红字，不塞进过程组。 */
private fun isOnlyError(item: TimelineItem.AssistantMessage): Boolean =
    item.text.isBlank() && item.thinking?.isBlank() != false && item.tools.isEmpty() && !item.errorText.isNullOrBlank()
