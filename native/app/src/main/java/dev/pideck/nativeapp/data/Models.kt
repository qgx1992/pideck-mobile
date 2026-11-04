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
    val options: List<String> = emptyList(),
)

@Serializable
data class SessionMessagePage(
    val messages: List<ChatMessage> = emptyList(),
    val total: Int = 0,
    val nextBefore: Long? = null,
    val unavailable: String? = null,
)

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
)

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
