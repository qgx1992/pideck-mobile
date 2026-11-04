package dev.pideck.nativeapp.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 「AI 提问」卡片（ask_question / confirm / input / multi_select）的协议层。
 *
 * 数据来自 `/api/state` 的 `pendingUiRequests`，答案回 `POST /api/ui-response`。
 * 批量信封（`batchQuestions`）的提交格式与桌面/网页端一致：`response.value` 里塞
 * 一个**字符串**，内容是 `{"answers":[{"id","type","value","label","wasCustom"}]}`。
 */
object AskUi {

    data class Option(val label: String, val value: String, val description: String?)

    /** 纯字符串选项（`pendingUiRequests.options` 就是这种）。 */
    @JvmName("optionsFromStrings")
    fun options(raw: List<String>): List<Option> =
        raw.mapNotNull { text -> text.trim().takeIf { it.isNotEmpty() }?.let { Option(it, it, null) } }

    /** 选项既可能是字符串也可能是 `{label,value,description}`。 */
    fun options(raw: List<JsonElement>): List<Option> = raw.mapNotNull { element ->
        when (element) {
            is JsonPrimitive -> element.content?.takeIf { it.isNotBlank() }?.let {
                Option(label = it, value = it, description = null)
            }

            is JsonObject -> {
                val label = (element["label"] as? JsonPrimitive)?.content
                if (label == null) {
                    (element["value"] as? JsonPrimitive)?.content?.let {
                        Option(label = it, value = it, description = null)
                    }
                } else {
                    Option(
                        label = label,
                        value = (element["value"] as? JsonPrimitive)?.content ?: label,
                        description = (element["description"] as? JsonPrimitive)?.content,
                    )
                }
            }

            else -> null
        }
    }

    sealed interface Answer {
        data class Text(val text: String) : Answer
        data class Flag(val value: Boolean) : Answer
        data class Many(val values: List<String>) : Answer
    }

    /** 人类可读的答案（提交给后端时一并带给模型看）。 */
    fun label(answer: Answer): String = when (answer) {
        is Answer.Text -> answer.text
        is Answer.Flag -> if (answer.value) "是" else "否"
        is Answer.Many -> answer.values.joinToString("、")
    }

    private fun valueOf(answer: Answer): JsonElement = when (answer) {
        is Answer.Text -> JsonPrimitive(answer.text)
        is Answer.Flag -> JsonPrimitive(answer.value)
        is Answer.Many -> buildJsonArray { answer.values.forEach { add(it) } }
    }

    /** 批量信封的最终载荷（作为 `response.value` 的字符串）。 */
    fun serializeBatch(questions: List<AskQuestion>, answers: Map<String, Answer>): String {
        val payload = buildJsonObject {
            putJsonArray("answers") {
                questions.forEach { question ->
                    val answer = answers[question.id]
                    add(
                        buildJsonObject {
                            put("id", question.id)
                            put("type", question.type)
                            put("value", answer?.let { valueOf(it) } ?: JsonPrimitive(""))
                            put("label", answer?.let { label(it) } ?: "")
                            put("wasCustom", false)
                        },
                    )
                }
            }
        }
        return payload.toString()
    }

    /** 普通（非批量）响应的 JSON。 */
    fun response(method: String, answer: Answer): JsonObject = buildJsonObject {
        when (method) {
            "confirm" -> put(
                "confirmed",
                (answer as? Answer.Flag)?.value ?: false,
            )

            else -> put("value", valueOf(answer))
        }
    }

    fun cancelled(): JsonObject = buildJsonObject { put("cancelled", true) }

    /** 一条 ask 请求最终要回的 body（含目标三元组）。 */
    fun body(request: PendingUiRequest, response: JsonObject): JsonObject = buildJsonObject {
        put("sessionId", request.sessionId)
        put("requestId", request.requestId)
        put("agentId", request.agentId)
        put("runtimeGeneration", request.runtimeGeneration)
        putJsonObject("response") {
            response.forEach { (key, value) -> put(key, value) }
        }
    }
}
