package dev.pideck.nativeapp.data

/**
 * 会话还在跑的时候，新输入的消息怎么投递。
 *
 * 对齐桌面端的「忙碌时发送」（`shared/busySendDelivery.ts` 的 "steer" / "followUp"）：
 * - [STEER] 加入当前回合：`pi` 的 `streamingBehavior: "steer"`，不打断本轮，模型下一步就能看到；
 * - [SEND_NOW] 立即发送：先停当前回合，再把这条当新一轮发出去（桌面端 0.10.0 起用它顶替 ACP 会话的
 *   「加入当前回合」—— ACP 协议**没有 steer 通道**，点了「加入当前回合」只会被排到本轮之后）；
 * - [FOLLOW_UP] 排队到下一轮：`streamingBehavior: "followUp"`，本轮结束后自动开新一轮；
 * - [PARALLEL] 并行发送：桌面端走「并行问询」——另开一个**匿名会话**（不落盘、不进项目列表）
 *   把问题发过去，答案单独展示，想看主线就把它插回输入框。
 *
 * 桌面端这几档是设置项（`settings.busySendDelivery`），手机上只记「上次用哪档」，
 * 每发一次都还能临时换 —— 因为手机上没有独立的设置页给这一项。
 */
enum class BusySendMode(val id: String, val label: String, val hint: String) {
    STEER("steer", "加入当前回合", "不打断这一轮，模型下一步就能看到"),
    SEND_NOW("sendNow", "立即发送", "先停这一轮，再把这条发出去"),
    FOLLOW_UP("followUp", "排队到下一轮", "本轮结束后自动发送"),
    PARALLEL("ask", "并行发送", "另开一个匿名会话去问，不打断当前回合"),
    ;

    companion object {
        fun parse(raw: String?): BusySendMode =
            entries.firstOrNull { it.id == raw } ?: STEER

        /** 会话没在跑的时候没有「投递方式」可言：此时一律按普通发送走。 */
        fun fromWire(behavior: String?): BusySendMode? = when (behavior) {
            "steer" -> STEER
            "followUp" -> FOLLOW_UP
            else -> null
        }

        /**
         * 这个后端在忙碌时能选哪几档。
         *
         * ACP 协议没有 steer 通道（桌面端 0.9.x 起就用 `steerSupported` 隐藏它、文案写着
         * 「该会话的 agent 不支持插入当前回合」，0.10.0 把那一格换成「立即发送」）。
         * 本端照抄这个判定：ACP 会话不给「加入当前回合」，换成先停再发。
         */
        fun options(backend: String?): List<BusySendMode> =
            if (backend == "acp") listOf(SEND_NOW, FOLLOW_UP, PARALLEL) else entries.toList()

        /** 上次选的档位在当前后端不可用时，换成等价的默认档（ACP 的 steer → 立即发送）。 */
        fun coerce(backend: String?, mode: BusySendMode): BusySendMode =
            if (mode in options(backend)) mode
            else if (backend == "acp") SEND_NOW
            else STEER
    }
}

/** `POST /api/sessions/:id/prompt` 的回执（宿主 `sessionRuntimeCoordinator.send` 的 result）。 */
data class PromptDelivery(
    val accepted: Boolean = false,
    val error: String? = null,
    val agentId: String? = null,
    val runtimeGeneration: Long? = null,
    /** 宿主自己的判定：delivered / queued / rejected / unknown（老主机可能没有）。 */
    val delivery: String? = null,
)
