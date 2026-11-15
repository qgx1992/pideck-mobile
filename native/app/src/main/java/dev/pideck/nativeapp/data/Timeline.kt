package dev.pideck.nativeapp.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 一张工具卡（历史里的 tool 消息与流式里的 tool part 归一成同一个形状）。 */
data class ToolView(
    val id: String,
    val name: String,
    val state: String,
    val input: String? = null,
    val output: String? = null,
    val errorText: String? = null,
    /**
     * 工具结果里带的图片（宿主 0.11.0 起：生图类扩展把图放在工具结果里）。
     *
     * 历史投影会挂 `images: [{type, data, mimeType}]`（裸 base64）；
     * 流式那边的 tool part 没有这个字段，所以这一路只有补拉历史之后才有图。
     */
    val images: List<MessageImage> = emptyList(),
) {
    val isError: Boolean get() = state == "output-error"
    val isSettled: Boolean get() = state == "output-available" || state == "output-error"
}

sealed interface TimelineItem {
    val key: String

    data class UserMessage(
        override val key: String,
        val text: String,
        val timestamp: Long,
        /** 随消息带的图片（手机/网页发图后宿主会回传 base64）。 */
        val images: List<MessageImage> = emptyList(),
    ) : TimelineItem

    data class AssistantMessage(
        override val key: String,
        val text: String,
        val thinking: String? = null,
        val tools: List<ToolView> = emptyList(),
        val timestamp: Long = 0L,
        /** 还在流式输出（末尾转圈）。 */
        val streaming: Boolean = false,
        val errorText: String? = null,
    ) : TimelineItem
}

/**
 * 历史消息 → 时间线条目。
 *
 * 上游 webApi.ts 的 `chatMessagesToUiMessages` 把 `role == "tool"` 的条目折成工具卡，
 * 这里同构处理；`meta.args` 可能是字符串化的 JSON（工具入参），取不到就用原文。
 */
/** 宿主内部记账用的 customType 前缀（桌面端同样不画成卡片）。 */
internal val INTERNAL_CUSTOM_TYPE_PREFIXES = listOf("subagents:", "pi-deck-", "codemode-")

/**
 * 这条消息在手机上要不要画。
 *
 * 宿主会把 pi 的 `custom_message` / `custom` 条目投影成 `role="system"` 的消息（见
 * `asar` 主进程 `projectEntryMessages` 附近）：`meta.type = "customMessage"` 的带一个
 * `meta.display` 布尔，`display: false` 表示「这是塞给模型看的内部注入，不要画出来」——
 * 桌面端就按这个字段过滤。**手机以前不看它**，于是目标模式的
 * 「Continue the current goal. Rounds used: 0/32」和 guard-replay 包着的上一轮思考
 * （`<think-replay>…</think-replay>`）整段铺在聊天流里，一次几百行。
 *
 * `meta.type = "customEntry"` 是扩展输出卡：桌面端只认扩展注册过 renderer 的类型，
 * 内部前缀（`subagents:` / `pi-deck-` / `codemode-`）一律不画，这里照同一条规则。
 */
internal fun ChatMessage.isInternalOnlyMessage(): Boolean {
    val meta = meta ?: return false
    // display 是宿主给的唯一权威开关：false 就是「只喂给模型，不给人看」。
    // 先按字段判一次、再看 type —— 宿主以后再加一种投影类型也不会又漏一次
    //（第一次漏的就是 guard-replay）。
    if (meta.bool("display") == false) return true
    return when (meta.string("type")) {
        "customMessage" -> meta.bool("display") != true
        "customEntry" -> {
            val customType = meta.string("customType").orEmpty()
            customType.isBlank() || INTERNAL_CUSTOM_TYPE_PREFIXES.any { customType.startsWith(it) }
        }
        else -> false
    }
}

fun List<ChatMessage>.toTimelineItems(): List<TimelineItem> = mapNotNull { message ->
    if (message.isInternalOnlyMessage()) return@mapNotNull null
    when (message.role) {
        "tool" -> {
            val meta = message.meta
            val toolName = meta.string("toolName").orEmpty()
            val error = meta.bool("isError") == true || message.text.startsWith("✗")
            if (toolName.isNotEmpty()) {
                TimelineItem.AssistantMessage(
                    key = "tool-${message.id}",
                    text = "",
                    tools = listOf(
                        ToolView(
                            id = "hist-${message.id}",
                            name = toolName,
                            state = if (error) "output-error" else "output-available",
                            input = meta.string("args") ?: meta.raw("args"),
                            output = meta.raw("result")?.takeIf { it.isNotBlank() && it != "null" },
                            errorText = if (error) meta.string("detailText") ?: message.text else null,
                            images = message.images.filter { it.data.isNotBlank() },
                        ),
                    ),
                    timestamp = message.timestamp,
                )
            } else {
                TimelineItem.AssistantMessage(
                    key = "msg-${message.id}",
                    text = message.text,
                    timestamp = message.timestamp,
                )
            }
        }

        "user" -> TimelineItem.UserMessage(
            key = "msg-${message.id}",
            text = message.text,
            timestamp = message.timestamp,
            images = message.images.filter { it.data.isNotBlank() },
        )

        // assistant 以及未知角色都按回复渲染：宁可多显示，别让内容凭空消失。
        else -> {
            // 有的后端把思考直接拼在正文里（`<thinking>…</thinking>`），usage.thinking 同时也有值，
            // 不拆就会在思考块下面再看到一整段带标签的重复内容。
            val (text, inlineThinking) = splitThinking(message.text)
            val thinking = listOfNotNull(
                message.thinking?.let(::thinkingFieldText)?.trim()?.takeIf { it.isNotBlank() },
                inlineThinking,
            ).distinct().joinToString("\n\n").ifBlank { null }
            if (text.isBlank() && thinking == null) null
            else TimelineItem.AssistantMessage(
                key = "msg-${message.id}",
                text = text,
                thinking = thinking,
                timestamp = message.timestamp,
            )
        }
    }
}

/** 流式快照 → 时间线条目（放在列表末尾）。 */
fun ReplySnapshot.toTimelineItem(streaming: Boolean): TimelineItem.AssistantMessage {
    val (body, inlineThinking) = splitThinking(text)
    return TimelineItem.AssistantMessage(
        key = "live-$messageId",
        text = body,
        thinking = listOfNotNull(
            latestReasoning.trim().takeIf { it.isNotBlank() },
            inlineThinking,
        ).distinct().joinToString("\n\n").ifBlank { null },
        tools = tools.map { tool ->
            ToolView(
                id = tool.toolCallId,
                name = tool.toolName,
                state = tool.state,
                input = tool.input?.prettyArgs(),
                output = tool.output?.prettyArgs(),
                errorText = tool.errorText,
            )
        },
        streaming = streaming && !finished,
        errorText = errorText,
    )
}

/**
 * 后端把思考塞进正文时用的标签。
 *
 * - `<thinking>…</thinking>`：pi 自己
 * - `<think-replay>…</think-replay>`：pi 的「思考重放」包装（目标模式/循环执行会把上一轮思考
 *   连同正文一起回灌，标签是英文的，和后端内部的 replay 逻辑同名）
 * - `<think>…</think>`：DSH 一类后端
 *
 * 认词根不认全名：见 INTERNAL_TAG_ROOTS。普通 HTML 标签原样保留。
 */
/**
 * 内部标签按「词根」分类，不按全名列举。
 *
 * 宿主的注入标签一直在换名字：`<thinking>` → `<think-replay>` → `<think-frag>` → `<guard-note>`。
 * 每换一个名字就往表里加一行是追不上的 —— 前两次露字都是「表里没有这个名字」。
 * 所以这里只认词根：名字里带这些词根的标签，一律按「给模型看」处理。
 */
private val INTERNAL_TAG_ROOTS = listOf("think", "guard", "internal", "pi-deck")

/** 用户本来就该看的思考标签：内容进折叠的思考块，不丢。 */
private val VISIBLE_THINK_TAGS = setOf("thinking", "think")

private enum class TagKind { Thinking, Drop, Plain }

private fun tagKindOf(name: String): TagKind {
    val n = name.lowercase()
    if (n in VISIBLE_THINK_TAGS) return TagKind.Thinking
    if (INTERNAL_TAG_ROOTS.any { n.contains(it) }) return TagKind.Drop
    return TagKind.Plain
}

/** 任何形如 `<name>` 的标签：内部标签认得出来，普通 HTML 标签原样留着。 */
private val ANY_TAG_PATTERN = Regex(
    "<\\s*(/)?\\s*([A-Za-z][A-Za-z0-9:_-]*)\\s*>",
    RegexOption.IGNORE_CASE,
)

/**
 * 把正文里的思考标签段拆出来。
 *
 * 规则：
 * - 成对标签之间的内容算思考；
 * - 只有开标签（流式输出到一半）也收，拿到一半的思考比让它糊在正文里强；
 * - **只有闭标签**（上一段的开标签被截断/被后端吃掉）只把标签本身去掉，内容留在正文里 ——
 *   否则会把用户真正想看的正文整段吞进思考块（目标模式的 `<think-replay>` 尾巴上踩过）。
 * - 没有标签时原样返回，一个字都不动。
 */
internal fun splitThinking(raw: String): Pair<String, String?> {
    if (!raw.contains('<')) return raw to null
    val matches = ANY_TAG_PATTERN.findAll(raw).toList()
    if (matches.isEmpty()) return raw to null

    val body = StringBuilder()
    val segments = mutableListOf<String>()
    // cursor 永远停在「当前没被任何标签包住的位置」：开标签之后就是它的内容起点，
    // 闭标签之后是它后面的正文起点。以前未闭合时 cursor 不动，
    // 于是 `body.append(raw.substring(cursor, open.range.first))` 会把上一个 `<thinking>` 标签本身
    // 抄进正文，还会把同一段内容重复一遍（12 个开标签 4 个闭标签的那种消息上就是这么露的）。
    var cursor = 0
    var open: MatchResult? = null
    var openDrop = false

    for (match in matches) {
        val closing = match.groupValues[1] == "/"
        val kind = tagKindOf(match.groupValues[2])
        if (kind == TagKind.Plain) continue // 不是内部标签：留在正文，一个字不动
        if (!closing) {
            if (open != null) {
                // 上一个还没闭合就来了新的开标签：手里这段算它的内容，按它自己的用途收掉
                if (!openDrop) segments += raw.substring(cursor, match.range.first)
            } else {
                body.append(raw.substring(cursor, match.range.first))
            }
            open = match
            openDrop = kind == TagKind.Drop
            cursor = match.range.last + 1
            continue
        }
        if (open == null) {
            // 孤立的闭标签：只去掉标签，内容照旧留在正文
            body.append(raw.substring(cursor, match.range.first))
            cursor = match.range.last + 1
            continue
        }
        if (!openDrop) segments += raw.substring(cursor, match.range.first)
        open = null
        openDrop = false
        cursor = match.range.last + 1
    }

    if (open != null) {
        if (!openDrop) segments += raw.substring(cursor)
    } else {
        body.append(raw.substring(cursor))
    }

    val cleanBody = body.toString().replace(Regex("\n{3,}"), "\n\n").trim()
    val cleanThinking = segments
        .map { it.replace(Regex("\n{3,}"), "\n\n").trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n\n")
    return cleanBody to cleanThinking.ifBlank { null }
}

/**
 * 思考字段的安全化：剥掉标签壳，内部的整段（`<guard-note>` 一类）直接丢。
 *
 * 不能只用 `splitThinking(...).second`：字段里大多数时候是**没有标签的纯文本**，
 * 那种情况下第二项是 null，会把正常的思考全丢掉。
 */
internal fun thinkingFieldText(raw: String): String? {
    if (!raw.contains('<')) return raw
    val (body, inline) = splitThinking(raw)
    return listOfNotNull(inline?.takeIf { it.isNotBlank() }, body.trim().takeIf { it.isNotBlank() })
        .distinct()
        .joinToString("\n\n")
        .ifBlank { null }
}

/** 入参/结果压成一行可读文本：对象走紧凑 JSON，字符串去掉外层引号。 */
private fun JsonElement.prettyArgs(): String {
    val primitive = this as? JsonPrimitive
    if (primitive != null && primitive.isString) return primitive.content
    val text = toString()
    return if (text.length > 4000) text.take(4000) + "…" else text
}

private fun JsonObject?.string(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }

private fun JsonObject?.raw(key: String): String? {
    val element = this?.get(key) ?: return null
    return element.prettyArgs()
}

private fun JsonObject?.bool(key: String): Boolean? =
    (this?.get(key) as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
