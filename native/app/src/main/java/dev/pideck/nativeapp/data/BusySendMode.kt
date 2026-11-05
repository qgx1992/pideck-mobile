package dev.pideck.nativeapp.data

/**
 * 会话还在跑的时候，新输入的消息怎么投递。
 *
 * 对齐桌面端的「忙碌时发送」（`shared/busySendDelivery.ts` 的 "steer" / "followUp"）：
 * - [STEER] 加入当前回合：`pi` 的 `streamingBehavior: "steer"`，不打断本轮，模型下一步就能看到；
 * - [FOLLOW_UP] 排队到下一轮：`streamingBehavior: "followUp"`，本轮结束后自动开新一轮；
 * - [PARALLEL] 并行发送：桌面端走「并行问询」——另开一个**匿名会话**（不落盘、不进项目列表）
 *   把问题发过去，答案单独展示，想看主线就把它插回输入框。
 *
 * 桌面端这两档是设置项（`settings.busySendDelivery`），手机上只记「上次用哪档」，
 * 每发一次都还能临时换 —— 因为手机上没有独立的设置页给这一项。
 */
enum class BusySendMode(val id: String, val label: String, val hint: String) {
    STEER("steer", "加入当前回合", "不打断这一轮，模型下一步就能看到"),
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
