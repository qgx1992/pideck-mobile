package dev.pideck.nativeapp.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * UIMessageStream 帧 → 一条消息的分部（text / reasoning / tool）。
 *
 * 直接照搬上游 `webExternalStream.ts` 的 `ExternalReplyMerger` 语义（那是桌面网页版正在用的实现）：
 * - `tool-input-start` → `tool-input-available` 是同一张卡的输入阶段，更新不新建；
 * - `tool-output-*` 只对「本端见过 input 且尚未 settle」的 toolCallId 生效，防止重连重放把已完成的卡打回 running；
 * - `error` / `finish` 收尾，`finish()` 兜底把开着的 reasoning 关闭。
 */
sealed interface ReplyPart {
    data class Text(val text: String) : ReplyPart

    data class Reasoning(val text: String, val done: Boolean) : ReplyPart

    data class Tool(
        val toolCallId: String,
        val toolName: String,
        val state: String,
        val input: JsonElement? = null,
        val output: JsonElement? = null,
        val errorText: String? = null,
    ) : ReplyPart {
        val isSettled: Boolean get() = state == "output-available" || state == "output-error"
    }
}

data class ReplySnapshot(
    val messageId: String,
    val parts: List<ReplyPart>,
    val finished: Boolean,
    val errorText: String?,
) {
    val text: String get() = parts.filterIsInstance<ReplyPart.Text>().joinToString("") { it.text }
    val reasoning: String get() = parts.filterIsInstance<ReplyPart.Reasoning>().joinToString("\n\n") { it.text }
    val tools: List<ReplyPart.Tool> get() = parts.filterIsInstance<ReplyPart.Tool>()
}

class ReplyMerger(
    private val idFactory: () -> String = { "external_${System.currentTimeMillis()}" },
) {
    private val parts = mutableListOf<ReplyPart>()
    private val inputSent = mutableSetOf<String>()
    private val settled = mutableSetOf<String>()

    var messageId: String? = null
        private set

    var finished: Boolean = false
        private set

    var errorText: String? = null
        private set

    fun hasContent(): Boolean = messageId != null

    fun isFinished(): Boolean = finished

    fun snapshot(): ReplySnapshot? {
        val id = messageId ?: return null
        return ReplySnapshot(id, parts.toList(), finished, errorText)
    }

    fun reset() {
        parts.clear()
        inputSent.clear()
        settled.clear()
        messageId = null
        finished = false
        errorText = null
    }

    /** [DONE] / runtime 转 idle 的兜底收尾：关掉所有还开着的块。 */
    fun finish(): ReplySnapshot? {
        finished = true
        for (index in parts.indices) {
            val part = parts[index]
            if (part is ReplyPart.Reasoning && !part.done) parts[index] = part.copy(done = true)
        }
        return snapshot()
    }

    fun applyFrame(frame: JsonObject): ReplySnapshot? {
        val type = frame.str("type")
        if (finished) return snapshot()

        when (type) {
            "start" -> {
                if (messageId == null) messageId = frame.str("messageId")?.ifBlank { null } ?: idFactory()
                return snapshot()
            }

            "text-start" -> {
                ensureMessage()
                parts += ReplyPart.Text("")
                return snapshot()
            }

            "text-delta" -> {
                ensureMessage()
                val delta = frame.str("delta").orEmpty()
                val last = parts.lastOrNull()
                if (last is ReplyPart.Text) parts[parts.lastIndex] = last.copy(text = last.text + delta)
                else parts += ReplyPart.Text(delta)
                return snapshot()
            }

            "text-end" -> return snapshot()

            "reasoning-start" -> {
                ensureMessage()
                parts += ReplyPart.Reasoning("", done = false)
                return snapshot()
            }

            "reasoning-delta" -> {
                ensureMessage()
                val delta = frame.str("delta").orEmpty()
                val last = parts.lastOrNull()
                if (last is ReplyPart.Reasoning) parts[parts.lastIndex] = last.copy(text = last.text + delta)
                else parts += ReplyPart.Reasoning(delta, done = false)
                return snapshot()
            }

            "reasoning-end" -> {
                for (index in parts.indices.reversed()) {
                    val part = parts[index]
                    if (part is ReplyPart.Reasoning) {
                        parts[index] = part.copy(done = true)
                        break
                    }
                }
                return snapshot()
            }

            "tool-input-start", "tool-input-available" -> {
                val toolCallId = frame.str("toolCallId").orEmpty()
                if (toolCallId.isEmpty() || toolCallId in settled) return snapshot()
                ensureMessage()
                val name = frame.str("toolName")?.ifBlank { null } ?: "tool"
                val existing = parts.indexOfFirst { it is ReplyPart.Tool && it.toolCallId == toolCallId }
                if (existing >= 0) {
                    val tool = parts[existing] as ReplyPart.Tool
                    if (type == "tool-input-available") {
                        parts[existing] = tool.copy(state = "input-available", input = frame["input"])
                    }
                    return snapshot()
                }
                inputSent += toolCallId
                parts += ReplyPart.Tool(
                    toolCallId = toolCallId,
                    toolName = name,
                    state = if (type == "tool-input-available") "input-available" else "input-streaming",
                    input = frame["input"],
                )
                return snapshot()
            }

            "tool-output-available", "tool-output-error" -> {
                val toolCallId = frame.str("toolCallId").orEmpty()
                if (toolCallId.isEmpty() || toolCallId !in inputSent || toolCallId in settled) return snapshot()
                settled += toolCallId
                inputSent -= toolCallId
                val index = parts.indexOfFirst { it is ReplyPart.Tool && it.toolCallId == toolCallId }
                if (index >= 0) {
                    val tool = parts[index] as ReplyPart.Tool
                    parts[index] = if (type == "tool-output-error") {
                        tool.copy(state = "output-error", errorText = frame.str("errorText") ?: "Tool failed")
                    } else {
                        tool.copy(state = "output-available", output = frame["output"])
                    }
                }
                return snapshot()
            }

            "error" -> {
                errorText = frame.str("errorText") ?: "Agent 运行失败"
                return finish()
            }

            "finish" -> return finish()
        }
        return snapshot()
    }

    private fun ensureMessage() {
        if (messageId == null) messageId = idFactory()
    }

    private fun JsonObject.str(key: String): String? {
        val value: JsonElement = this[key] ?: return null
        val primitive = runCatching { value.jsonPrimitive }.getOrNull() ?: return null
        if (primitive is JsonPrimitive && !primitive.isString) return primitive.contentOrNull
        return primitive.contentOrNull
    }
}
