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

        /** 中间回复条数（Web 端组头上的 💬N）。 */
        val interimCount: Int get() = steps.sumOf { it.interim.size }

        /** 组头摘要文案（运行中给「正在读取…」）。 */
        val summary: String
            get() = ProcessActivity.summary(
                toolNames = toolNames,
                thinkingCount = thinkingCount,
                running = streaming,
                interimCount = interimCount,
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
    /**
     * 要不要把连续的过程条目合并成「过程组」（设置里的「过程组显示」，默认开）。
     * 关掉时每条内容各自平铺一行，与桌面端同一个开关同一种表现。
     */
    groupProcess: Boolean = true,
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

    // 一轮里只有**最后一段**正文才算最终回答；前面那些 assistant 的正文是「中间回复」，
    // 和思考、工具一样属于过程（Web 端组头的 💬N 就是它）。
    // 以前每段正文都单独成行 → 每条都 flush 一次，于是同一轮冒出四五个「执行过程」组，
    // 正文也被切得七零八落。
    val lastAnswerKey = all
        .filterIsInstance<TimelineItem.AssistantMessage>()
        .lastOrNull { it.text.isNotBlank() }
        ?.key
    // 还在流式时，只有 live 那条算正文；其它带正文的步骤先按中间回复处理。
    // 否则正文一变（宿主又来一段），上一段会从「正文」跳进组里，屏幕上抖一下。
    val liveKey = live?.key
    // 「最后一段正文」只在**本轮**里成立。整个会话都塞进这个函数时，上一轮的最终回答也会被
    // 这一轮的答案盖掉、被折叠成中间回复（用户看到的就是「再问一次，上一条回答缩进组里了」）。
    val lastUserIndex = all.indexOfLast { it is TimelineItem.UserMessage }

    all.forEachIndexed { index, raw ->
        var item = raw
        // 桌面端的 💬 是「本轮除最终回答外，每条 assistant 消息一段」——即使那条只有思考、
        // 没有正文，也算一段（正文空串在组里不画行，计数仍 +1）。两轮实测对得上：12 条消息 → 11，
        // 4 条消息 → 3。
        val earlyAnswer = (item as? TimelineItem.AssistantMessage)
            ?.takeIf {
                index > lastUserIndex && it.key != lastAnswerKey && it.key != liveKey &&
                    // 既没正文也没思考的条目（只有错误）不算中间回复，留给下面的空行处理。
                    (it.text.isNotBlank() || !it.thinking.isNullOrBlank())
            }
        if (groupProcess && earlyAnswer != null) {
            pending += earlyAnswer.copy(
                text = "",
                interim = listOf(earlyAnswer.text),
                streaming = false,
            )
            return@forEachIndexed
        }
        // 收尾的那条（有正文）如果还带着思考，把这段思考并进紧挨着的过程组：
        // 桌面端同一个回合的思考全算在组里（组头「思考 ×4」），手机上最后一段会掉到组外，
        // 变成组下面一张孤零零的「思考过程」卡。
        val trailingThink = (item as? TimelineItem.AssistantMessage)
            ?.takeIf { it.text.isNotBlank() && it.thinking?.isNotBlank() == true }
        val lastGroup = rows.lastOrNull() as? ChatRow.Process
        if (groupProcess && trailingThink != null && pending.isEmpty() && lastGroup != null) {
            // 组已经 flush 出去了（比如中间隔了一条正文），把这段思考并回最后那个组，
            // 别让它在组下面变成一张孤零零的「思考过程」卡。
            rows[rows.lastIndex] = lastGroup.copy(
                steps = lastGroup.steps + trailingThink.copy(
                    key = trailingThink.key + "-think",
                    text = "",
                    tools = emptyList(),
                    errorText = null,
                    streaming = false,
                ),
            )
            item = trailingThink.copy(thinking = null)
        } else if (groupProcess && trailingThink != null && pending.isNotEmpty()) {
            pending += trailingThink.copy(
                key = trailingThink.key + "-think",
                text = "",
                tools = emptyList(),
                errorText = null,
                streaming = false,
            )
            item = trailingThink.copy(thinking = null)
        }
        if (groupProcess && item is TimelineItem.AssistantMessage && item.text.isBlank() && !isOnlyError(item)) {
            // 思考与工具调用都算「过程」，一起折进组里；组内思考是展开显示的（见 ProcessGroupBlock），
            // 所以并进组不等于把内容藏起来。
            pending += item
            return@forEachIndexed
        }
        flush()
        rows += ChatRow.Single(item)
    }
    // 流式中「正在生成的这段思考」也要在组里：桌面端同一个组会显示「思考 3/4」，
    // 正文在组外继续往下写。不加这段，运行中就是「组里只有工具、思考掉到组外」，
    // 跑完把思考挪回组里才对齐——用户看到的就是「完成的顺序才对」。
    if (groupProcess && live != null && !live.thinking.isNullOrBlank() && pending.none { it.key == live.key }) {
        pending += live.copy(
            key = live.key + "-think",
            text = "",
            tools = emptyList(),
            errorText = null,
            interim = emptyList(),
            streaming = true,
        )
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
