package dev.pideck.nativeapp.data

/**
 * 输入框的三个模式（对齐桌面端的 `composerMode`：normal / plan / goal）。
 *
 * 宿主在**桌面端**是用 `agentMessage` 这个隐藏载荷送指令的：`message` 只进聊天记录，
 * `agentMessage` 才交给 agent。但 Web 侧（`POST /api/sessions/:id/prompt`、`/api/chat`）
 * 的处理器只把 `message` 往下传，`agentMessage` 会被丢掉（实测 0.11.3 主进程里只有桌面端
 * 自己那 4 处）—— 所以手机这边只能把指令拼进 `message`，落盘的消息里会带这段文字。
 * 界面上由 [displayText] 剥掉，用户看到的是自己打的那句。
 */
enum class ComposerMode(val label: String) {
    Normal("正常"),
    Plan("计划"),
    Goal("目标"),
}

object ComposerModePayloads {
    const val PLAN_MARKER = "__PI_DECK_PLAN_MODE__"
    const val GOAL_MARKER = "__PI_DECK_GOAL_MODE__"

    private const val PLAN_DEFAULT =
        "请根据已附加的图片或上下文先制定实施计划。"
    private const val GOAL_DEFAULT = "请继续当前目标。"

    /** 桌面端 `buildComposerPromptSubmission` 的同款拼法（逐字对齐，便于两边对账）。 */
    private const val PLAN_INSTRUCTION =
        "请先只做只读分析，不要修改文件。最后必须输出以 `Plan:` 开头的编号计划，格式如下：\n" +
            "Plan:\n1. 第一步\n2. 第二步"

    /** 真正发给 agent 的那段（会作为用户消息落盘）。 */
    fun wrap(mode: ComposerMode, text: String): String {
        val trimmed = text.trim()
        return when (mode) {
            ComposerMode.Normal -> text
            ComposerMode.Plan -> listOf(
                PLAN_MARKER,
                trimmed.ifEmpty { PLAN_DEFAULT },
                "",
                PLAN_INSTRUCTION,
            ).joinToString("\n")
            ComposerMode.Goal -> listOf(GOAL_MARKER, trimmed.ifEmpty { GOAL_DEFAULT }).joinToString("\n")
        }
    }

    /**
     * 显示用：剥掉标记行和计划模式那一段隐藏指令。
     *
     * 只认我们自己拼出来的形状（首行标记 + 计划模式的可选尾段），别把它做成通用清洗 ——
     * 用户真的打字说「__PI_DECK_PLAN_MODE__ 是什么」时不该被吃。
     */
    fun displayText(text: String): String {
        if (text.startsWith(PLAN_MARKER)) {
            val rest = text.removePrefix(PLAN_MARKER).trimStart('\n')
            val cut = rest.indexOf("\n\n" + PLAN_INSTRUCTION)
            val visible = if (cut >= 0) rest.substring(0, cut) else rest
            return visible.trim().ifEmpty { PLAN_DEFAULT }
        }
        if (text.startsWith(GOAL_MARKER)) {
            val rest = text.removePrefix(GOAL_MARKER).trimStart('\n')
            return rest.trim().ifEmpty { GOAL_DEFAULT }
        }
        return text
    }

    /** 助手这条回复里有没有计划（桌面端要求以 `Plan:` 开头的编号列表）。 */
    fun hasPlan(text: String): Boolean {
        if (text.isBlank()) return false
        return Regex("(^|\\n)\\s*Plan\\s*:|(^|\\n)\\s*计划\\s*[：:]", RegexOption.IGNORE_CASE)
            .containsMatchIn(text)
    }
}
