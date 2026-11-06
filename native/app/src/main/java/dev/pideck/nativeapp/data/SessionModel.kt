package dev.pideck.nativeapp.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** 会话记录里存的模型偏好（`session.model`），形状是 `{provider, modelId, modelName}`。 */
@Serializable
data class SessionModel(
    val provider: String = "",
    val modelId: String = "",
    val modelName: String = "",
) {
    val label: String get() = modelName.ifBlank { modelId }.ifBlank { "默认模型" }

    /** 短标签：手机上顶栏那点宽度放不下「Deepseek-V4.1-Flash · routing group」。 */
    val shortLabel: String
        get() = label
            .substringBefore(" · ")
            .ifBlank { label }
            .let { if (it.length <= 22) it else it.take(20) + "…" }
}

/**
 * `GET /api/models` 的一项。
 *
 * `thinkingLevels` 是这个模型支持的思考档位（不在里面的档位传上去会被主机拒或静默降级，
 * 所以选择器只列它）。`reasoning=false` 的模型没有档位可选。
 */
@Serializable
data class AvailableModel(
    val provider: String = "",
    val id: String = "",
    val name: String = "",
    val contextWindow: Long = 0,
    val reasoning: Boolean = false,
    val thinkingLevels: List<String> = emptyList(),
) {
    val label: String get() = name.ifBlank { id }
}

/**
 * pi 的标准思考档位（桌面端在拿不到模型能力时也用这一份）。
 *
 * 模型表里带 `thinkingLevels` 时以它为准 —— 档位不在里面传上去会被主机拒或静默降级。
 */
val STANDARD_THINKING_LEVELS: List<String> = listOf("off", "minimal", "low", "medium", "high", "xhigh", "max")

/** 思考档位的中文名。未知档位原样显示，不吞信息。 */
fun thinkingLabel(level: String): String = when (level.lowercase()) {
    "off" -> "不思考"
    "minimal" -> "极简"
    "low" -> "低"
    "medium" -> "中"
    "high" -> "高"
    "xhigh" -> "极高"
    "max" -> "最大"
    else -> level
}

/** 会话记录里存的模型偏好 → 展示用（`session.model` 是 `ModelPreference`，形状一致）。 */
fun ModelPreference?.toSessionModel(): SessionModel? {
    val pref = this ?: return null
    if (pref.provider.isBlank() && pref.modelId.isBlank() && pref.modelName.isNullOrBlank()) return null
    return SessionModel(
        provider = pref.provider,
        modelId = pref.modelId,
        modelName = pref.modelName.orEmpty(),
    )
}

/** `/api/models` → 列表；解码失败的单项跳过，不让一个坏条目毁掉整张表。 */
fun List<JsonElement>.toAvailableModels(): List<AvailableModel> = mapNotNull { element ->
    runCatching { PideckJson.decodeFromJsonElement(AvailableModel.serializer(), element) }.getOrNull()
}
