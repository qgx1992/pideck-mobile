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
        )

        // assistant 以及未知角色都按回复渲染：宁可多显示，别让内容凭空消失。
        else -> {
            val text = message.text
            val thinking = message.thinking?.takeIf { it.isNotBlank() }
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
fun ReplySnapshot.toTimelineItem(streaming: Boolean): TimelineItem.AssistantMessage =
    TimelineItem.AssistantMessage(
        key = "live-$messageId",
        text = text,
        thinking = reasoning.ifBlank { null },
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
