package dev.pideck.nativeapp.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/** 运行时动作需要的三元组；服务端用它核对「这条命令发给的那一代运行时」。 */
data class RuntimeTarget(
    val sessionId: String,
    val agentId: String,
    val runtimeGeneration: Long,
)

/** 上下文用量（`POST /api/sessions/:id/runtime/state` 的子集，字段与桌面端 `WebContextUsage` 同名）。 */
data class ContextUsage(
    val contextTokens: Long = 0,
    val contextWindow: Long = 0,
    val contextPercent: Double = 0.0,
    val overflow: Boolean = false,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val permissionPreset: String? = null,
    val provider: String? = null,
) {
    val known: Boolean get() = contextWindow > 0

    /** 头部环里显示的那种「32%」。窗口未知时返回 null，别显示 0%。 */
    val percentLabel: String?
        get() = if (!known) null else "${contextPercent.toInt().coerceIn(0, 999)}%"

    val detail: String
        get() = if (!known) "上下文窗口未知" else "${formatTokens(contextTokens)} / ${formatTokens(contextWindow)}"
}

/** `1.4k` 这种紧凑写法，头部空间紧。 */
fun formatTokens(value: Long): String = when {
    value >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fm", value / 1_000_000.0)
    value >= 1_000 -> String.format(java.util.Locale.US, "%.1fk", value / 1_000.0)
    else -> value.toString()
}

/** 权限预设（与桌面端 `DshPermissionMenu.tsx` 的 `DSH_PERMISSION_PRESETS` 同一组值）。 */
object PermissionPresets {
    data class Preset(val id: String, val label: String, val hint: String)

    val all: List<Preset> = listOf(
        Preset("read-only", "只读", "只能读文件、跑只读命令，改不了任何东西；最接近「计划模式」"),
        Preset("workspace-write", "工作区可写", "能改当前工作区的文件，动不到外面"),
        Preset("danger-full-access", "完全访问", "不受沙箱限制，能跑任何命令、写任何路径"),
    )

    const val DEFAULT = "workspace-write"

    fun labelOf(id: String?): String =
        all.firstOrNull { it.id == id }?.label ?: (id ?: "未设置")

    fun hintOf(id: String?): String =
        all.firstOrNull { it.id == id }?.hint ?: "桌面端返回的自定义预设"
}

/** 把 `runtime/state` 的返回体解析成 [ContextUsage]；字段缺失就当没有。 */
fun parseContextUsage(root: JsonElement?): ContextUsage {
    val obj = root as? JsonObject ?: return ContextUsage()
    fun long(key: String): Long = (obj[key] as? JsonPrimitive)?.let { p ->
        p.doubleOrNull?.toLong() ?: p.content.toLongOrNull()
    } ?: 0L
    fun str(key: String): String? = (obj[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    val window = long("contextWindow")
    val tokens = long("contextTokens")
    val reported = (obj["contextPercent"] as? JsonPrimitive)?.doubleOrNull
    val percent = reported ?: if (window > 0) tokens * 100.0 / window else 0.0
    return ContextUsage(
        contextTokens = tokens,
        contextWindow = window,
        contextPercent = percent,
        overflow = (obj["contextOverflow"] as? JsonPrimitive)?.booleanOrNull ?: false,
        inputTokens = long("inputTokens"),
        outputTokens = long("outputTokens"),
        permissionPreset = str("permissionPreset"),
        provider = str("provider"),
    )
}

/**
 * `runtime/state` 的真实信封：
 * `{"result":{"ok":true,"value":{"target":{…},"value":{...状态本体...}}}}`。
 * 另外兼容 `{state: {...}}` 与「直接就是状态本体」两种形状 —— 宿主改过几次外壳，
 * 只在一种上写死就会「读取不到」。
 */
fun unwrapRuntimeState(root: JsonElement?): JsonElement? {
    val obj = root as? JsonObject ?: return root
    val result = obj["result"] as? JsonObject
    if (result != null) {
        val ok = (result["ok"] as? JsonPrimitive)?.booleanOrNull
        if (ok == false) return null
        val outer = result["value"] as? JsonObject
        val inner = outer?.get("value") as? JsonObject
        if (inner != null) return inner
        if (outer != null) return outer
    }
    obj["state"]?.takeIf { it is JsonObject }?.let { return it }
    return obj
}

/** 导出文件名：会话 id 里可能有 `:` `/` 之类，替换掉。 */
fun exportFileName(sessionId: String, title: String?): String {
    val safeTitle = title?.trim()?.take(40)?.replace(Regex("[^\\p{L}\\p{N}_-]+"), "-")?.trim('-')
    val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())
    val head = safeTitle?.takeIf { it.isNotBlank() } ?: sessionId
    return "pideck-$head-$stamp.html"
}

/** 需要给用户看的部分都解析好了；[json] 是发给服务端的那一份。 */
fun runtimeTargetBody(target: RuntimeTarget): JsonObject = buildJsonObject {
    put(
        "target",
        buildJsonObject {
            put("sessionId", target.sessionId)
            put("agentId", target.agentId)
            put("runtimeGeneration", target.runtimeGeneration)
        },
    )
}

/** `export-html` 的响应体就是整份 HTML（GET 附件流，桌面端直接当 blob 下载）。 */
fun htmlLooksValid(text: String): Boolean =
    text.isNotBlank() && text.contains("<html", ignoreCase = true)
