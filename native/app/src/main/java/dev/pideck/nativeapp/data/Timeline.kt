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
fun List<ChatMessage>.toTimelineItems(): List<TimelineItem> = mapNotNull { message ->
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
                message.thinking?.trim()?.takeIf { it.isNotBlank() },
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
            reasoning.trim().takeIf { it.isNotBlank() },
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

private const val THINK_OPEN = "<thinking>"
private const val THINK_CLOSE = "</thinking>"

/**
 * 把正文里的 `<thinking>…</thinking>` 段拆出来。
 *
 * 未闭合的（流式输出中途）也收：拿到一半的思考也比让它糊在正文里强。
 * 返回的正文会去掉标签与多余空行；没有标签时原样返回，不做任何裁剪。
 */
internal fun splitThinking(raw: String): Pair<String, String?> {
    if (!raw.contains(THINK_OPEN) && !raw.contains(THINK_CLOSE)) return raw to null
    val body = StringBuilder()
    val segments = mutableListOf<String>()
    var cursor = 0
    while (cursor < raw.length) {
        val start = raw.indexOf(THINK_OPEN, cursor)
        if (start < 0) {
            val rest = raw.substring(cursor)
            if (rest.contains(THINK_CLOSE)) body.append(rest.replace(THINK_CLOSE, "")) else body.append(rest)
            break
        }
        body.append(raw.substring(cursor, start))
        val end = raw.indexOf(THINK_CLOSE, start + THINK_OPEN.length)
        if (end < 0) {
            segments += raw.substring(start + THINK_OPEN.length)
            break
        }
        segments += raw.substring(start + THINK_OPEN.length, end)
        cursor = end + THINK_CLOSE.length
    }
    val cleanBody = body.toString().replace(Regex("\n{3,}"), "\n\n").trim()
    val cleanThinking = segments
        .map { it.replace(Regex("\n{3,}"), "\n\n").trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n\n")
    return cleanBody to cleanThinking.ifBlank { null }
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
