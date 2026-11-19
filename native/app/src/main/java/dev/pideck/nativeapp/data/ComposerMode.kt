package dev.pideck.nativeapp.data

/**
 * 输入框的三个模式（对齐桌面端的 `composerMode`：normal / plan / goal）。
 *
 * **通道（0.5.69 起）**：桌面端把模式指令放在 `agentMessage` 这个隐藏载荷里 —— `message`
 * 只进聊天记录，`agentMessage` 才交给 agent。宿主 0.13.0 的 Web 层已经认这个字段
 * （`POST /api/sessions/:id/prompt` 与 `/api/chat` 都读 `body.agentMessage`，上限 64 KB），
 * 所以手机端也走同一条路：用户打的那句话落盘，标记与指令只进提示词。
 *
 * 0.13.0 还有一层守卫：会话没注入 `pi-deck-plan-mode.ts` / `pi-deck-goal-mode.ts` 时，
 * 带标记的 `agentMessage` 会被丢掉、回退成 `message`（只记一条 warn），也就是说这里发出去的
 * 东西在任何宿主上都不会坏，最多退化成一条普通消息。
 *
 * 更早的宿主（0.12 及以前）会忽略 `agentMessage`，那种情况只能靠 [displayText] 把落盘的
 * 标记剥掉 —— 老消息里已经带着标记，这个函数仍然要认。
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

    /** 一次发送的两个载荷：`message` 进聊天记录，`agentMessage` 交给 agent。 */
    data class Submission(val message: String, val agentMessage: String?) {
        /** 本地先画出来的那一句（不显示标记与指令）。 */
        val display: String get() = ComposerModePayloads.displayText(message).trim()
    }

    /**
     * 按桌面端的规则算两个载荷。
     *
     * `/` 开头的斜杠命令不套模式 —— 桌面端也是这么判的（命令要原样交给宿主解析）。
     */
    fun submission(mode: ComposerMode, text: String): Submission {
        if (text.trimStart().startsWith("/")) return Submission(text, null)
        return when (mode) {
            ComposerMode.Normal -> Submission(text, null)
            ComposerMode.Plan -> {
                // 空文本只带图片时，用桌面端那句默认指令当可见消息（宿主拒收空 message）。
                val visible = text.trim().ifEmpty { PLAN_DEFAULT }
                Submission(
                    message = if (text.isBlank()) visible else text,
                    agentMessage = listOf(
                        PLAN_MARKER,
                        visible,
                        "",
                        PLAN_INSTRUCTION,
                    ).joinToString("\n"),
                )
            }
            ComposerMode.Goal -> {
                val visible = text.trim().ifEmpty { GOAL_DEFAULT }
                Submission(
                    message = if (text.isBlank()) visible else text,
                    agentMessage = listOf(GOAL_MARKER, visible).joinToString("\n"),
                )
            }
        }
    }

    /**
     * 显示用：剥掉标记行和计划模式那一段隐藏指令。
     *
     * 只认我们自己拼出来的形状（首行标记 + 计划模式的可选尾段），别把它做成通用清洗 ——
     * 用户真的打字说「__PI_DECK_PLAN_MODE__ 是什么」时不该被吃。
     * 现在只用于 0.5.68 及更早落盘的老消息。
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
