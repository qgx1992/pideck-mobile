package dev.pideck.nativeapp.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 与桌面端 WebServiceManager 的 JSON 契约（`app/vendor/pideck/src/renderer/src/web/webTypes.ts`）。
 *
 * 上游字段只增不减，所以全部 `ignoreUnknownKeys = true`；缺失字段一律给默认值，
 * 避免上游某个可选键消失就把整页打空。
 */
val PideckJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
    encodeDefaults = true
}

/**
 * 毫秒时间戳。上游有时写字面整数、有时写 `1792225287694.017` 这样的浮点
 * （会话的 updatedAt 就是浮点），直接声明成 Long 会让整份 `/api/state` 解不出来 —— 真机上踩过。
 */
object EpochMillisSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("dev.pideck.EpochMillis", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): Long = decoder.decodeDouble().toLong()

    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeLong(value)
}

@Serializable
data class PideckState(
    val projects: List<Project> = emptyList(),
    val sessions: List<Session> = emptyList(),
    val runtimes: List<RuntimeInfo> = emptyList(),
    val pendingUiRequests: List<PendingUiRequest> = emptyList(),
    val acpTools: List<AcpTool> = emptyList(),
    val security: JsonElement? = null,
    val version: String? = null,
)

@Serializable
data class AcpTool(
    val id: String = "",
    val name: String = "",
)

@Serializable
data class Project(
    val id: String,
    val name: String = "",
    val path: String = "",
    @Serializable(with = EpochMillisSerializer::class)
    val lastOpenedAt: Long = 0L,
    val pinned: Boolean = false,
    val sortOrder: Int = 0,
    val kind: String? = null,
    val environment: String? = null,
)

@Serializable
data class ModelPreference(
    val provider: String = "",
    val modelId: String = "",
    val modelName: String? = null,
)

@Serializable
data class Session(
    val id: String,
    val projectId: String = "",
    val title: String = "",
    val status: String = "",
    val projectPath: String? = null,
    @Serializable(with = EpochMillisSerializer::class)
    val updatedAt: Long = 0L,
    @Serializable(with = EpochMillisSerializer::class)
    val createdAt: Long = 0L,
    val parentSessionId: String? = null,
    val forked: Boolean = false,
    val backend: String? = null,
    val thinkingLevel: String? = null,
    val model: ModelPreference? = null,
    val permissionPreset: String? = null,
    val acpToolId: String? = null,
)

@Serializable
data class RuntimeInfo(
    val sessionId: String,
    val agentId: String = "",
    val status: String = "",
    val cwd: String? = null,
    val runtimeGeneration: Int = 0,
    @Serializable(with = EpochMillisSerializer::class)
    val createdAt: Long = 0L,
    val deckSessionId: String? = null,
    val projectId: String? = null,
    val sessionPath: String? = null,
) {
    val isRunning: Boolean get() = status == "running" || status == "busy"
}

@Serializable
data class PendingUiRequest(
    val sessionId: String = "",
    val agentId: String = "",
    val requestId: String = "",
    val method: String = "",
    val title: String = "",
    // 选项既可能是字符串也可能是 `{label,value,description}` 对象（批量题那边已经是 JsonElement）。
    // 写成 List<String> 时，只要宿主对单题也给对象数组，整份 `/api/state` 解码就抛错 ——
    // 界面拿到的是「解析失败 / 空数据」，而不是少一张卡。
    val options: List<JsonElement> = emptyList(),
    val runtimeGeneration: Long = 0,
    val placeholder: String? = null,
    val prefill: String? = null,
    val allowOther: Boolean? = null,
    val batchQuestions: List<AskQuestion> = emptyList(),
    val batchReview: Boolean = false,
)

/** `ask_question` 批量信封里的一道题（选项可能是字符串也可能是对象）。 */
@Serializable
data class AskQuestion(
    val id: String = "",
    val type: String = "input",
    val question: String = "",
    val options: List<JsonElement> = emptyList(),
    val allowOther: Boolean? = null,
    val placeholder: String? = null,
    val prefill: String? = null,
)

/** 子代理条目（`/api/sessions/:id/subagents`），字段对齐桌面端 `PiSubagentEntry`。 */
@Serializable
data class SubagentEntry(
    val id: String = "",
    val type: String = "",
    val description: String = "",
    val status: String = "",
    val result: String? = null,
    val error: String? = null,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val toolUses: Int? = null,
    val tokens: Int? = null,
) {
    val isRunning: Boolean get() = status == "running" || status == "queued"
    val statusLabel: String
        get() = when (status) {
            "queued" -> "排队中"
            "running" -> "执行中"
            "completed" -> "已完成"
            "steered" -> "已转向"
            "aborted" -> "已中止"
            "stopped" -> "已停止"
            "error" -> "失败"
            else -> status
        }
}

@Serializable
data class SessionMessagePage(
    val messages: List<ChatMessage> = emptyList(),
    val total: Int = 0,
    val nextBefore: Long? = null,
    val unavailable: String? = null,
)

/**
 * 历史消息里带的图片。
 *
 * 宿主（`/api/sessions/:id/messages/page`）对 user 消息会附带 `images: [{type, data, mimeType}]`，
 * `data` 是**裸 base64**（不含 `data:` 前缀）。网页端把手机发的图当 `file` part 传进去，
 * 落盘后回读就是这个形状 —— 不解析它，会话页就只剩一行文字，图彻底看不见。
 */
@Serializable
data class MessageImage(
    val type: String = "image",
    /** 裸 base64（无 `data:` 前缀）。 */
    val data: String = "",
    val mimeType: String = "image/jpeg",
) {
    val dataUrl: String get() = "data:$mimeType;base64,$data"
}

@Serializable
data class ChatMessage(
    val id: String,
    val agentId: String = "",
    val role: String = "assistant",
    val text: String = "",
    val timestamp: Long = 0L,
    val thinking: String? = null,
    /** 结构随上游变化：耗时、usage、工具名都在里面，用 JsonObject 保真，按需取。 */
    val meta: JsonObject? = null,
    /** 只有 user 消息会带（手机/网页发图之后回读历史），其余为空。 */
    val images: List<MessageImage> = emptyList(),
) {
    /**
     * 宿主给每条消息写的全局递增序号（`meta._piDeckMsgSeq`）。
     *
     * 「排队到下一轮」发出去的消息没有回执，只能等它出现在历史里。按文本对账会把
     * 「同一句话发两次」错算成两条都到了；按序号对账，一条落盘只抵掉一条排队。
     */
    val seq: Long?
        get() = (meta?.get("_piDeckMsgSeq") as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
}

/** `/api/state` 的一次抓取结果：`NotModified` 表示 ETag 命中，调用方保留旧数据。 */
sealed interface StateFetch {
    data class Fresh(val state: PideckState, val etag: String?) : StateFetch
    data object NotModified : StateFetch
}

/** SSE 单帧（UIMessageStream 的 `data:` 行）。 */
data class SseFrame(
    val id: String?,
    val type: String?,
    val data: String,
)

class PideckException(
    message: String,
    cause: Throwable? = null,
    val code: Int? = null,
) : Exception(message, cause)
