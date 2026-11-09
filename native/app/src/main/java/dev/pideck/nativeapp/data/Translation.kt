package dev.pideck.nativeapp.data

/**
 * 浮窗里的「翻译」。
 *
 * 手机上没有任何本机翻译能力（ML Kit 那种要额外下模型、还得联网拉 AAR），宿主那台桌面端
 * 的划词翻译走的是 Electron IPC（`agent-profiles:invoke`），HTTP 侧拿不到。所以这里用
 * 「开一个匿名会话让宿主上的 agent 翻」这条现成的路：`POST /api/sessions/anonymous`
 * → `prompt` → 读回复流 → 收尾 `runtime/stop`，与聊天页的「并行发送」同一套机器。
 */
enum class TranslateTarget(val label: String) {
    Chinese("中文"),
    English("英文"),
    ;

    /** 另一个方向：已经译成中文了，再按一次就是译成英文。 */
    fun flipped(): TranslateTarget = if (this == Chinese) English else Chinese
}

/** 非空白字符里中日韩字符的占比；0f 表示完全不是中文（或没有正文）。 */
fun cjkRatio(text: String): Float {
    var total = 0
    var cjk = 0
    for (ch in text) {
        if (ch.isWhitespace()) continue
        total += 1
        val code = ch.code
        if ((code in 0x4E00..0x9FFF) || (code in 0x3400..0x4DBF) || (code in 0xF900..0xFAFF)) {
            cjk += 1
        }
    }
    return if (total == 0) 0f else cjk.toFloat() / total
}

/**
 * 该译成什么：中文写的内容译成英文，其余（英文 / 日文 / 代码）译成中文。
 *
 * 0.15 这条线是「一段里至少有一成半是汉字」才当成中文正文 —— 英文段落里偶尔夹几个
 * 汉字（术语、人名）不该被判成中文，否则按一下翻译等于什么都没干。
 */
fun defaultTranslateTarget(text: String): TranslateTarget =
    if (cjkRatio(text) >= 0.15f) TranslateTarget.English else TranslateTarget.Chinese

/**
 * 发给匿名会话的那句话。
 *
 * 「只输出译文」是必须的：agent 默认会先来一句「好的，以下是译文：」，浮窗里多这一行
 * 用户还得自己删；「不要使用工具」是防止它去读文件、跑命令（思考里常出现路径）。
 */
fun translateInstruction(text: String, target: TranslateTarget): String = buildString {
    append("把下面这段内容翻译成")
    append(target.label)
    append("。只输出译文本身：不要开场白、不要解释、不要复述原文、不要使用任何工具。")
    append("原文已经是")
    append(target.label)
    append("时原样返回。原文如下：\n\n")
    append(text.trim())
}

/** 浮窗里那块译文的状态。`text` 为空且 `busy` 为假、`error` 为 null 时表示还没开始翻。 */
data class TranslationState(
    val target: TranslateTarget,
    val busy: Boolean = false,
    val text: String = "",
    val error: String? = null,
)
