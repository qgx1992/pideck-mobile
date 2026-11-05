package dev.pideck.nativeapp.data

import kotlinx.serialization.json.JsonArray
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

/**
 * `runtime/state` 的两个用途：上下文用量（辅助信息）＋「运行时真正在跑的模型与思考档位」。
 *
 * 后者的存在理由：宿主**不会**把启动默认值写进 web 端新建的会话记录（默认值解析器只在
 * Electron IPC 的 createDraft 里跑），所以「记录里没有模型」时手机上唯一能拿到的真值
 * 就是运行时状态这一份 —— 它和 pi 实际用的模型/档位同源。
 */
data class RuntimeSnapshot(
    val usage: ContextUsage = ContextUsage(),
    val model: SessionModel? = null,
    val thinkingLevel: String? = null,
)

/** 运行时状态 → [RuntimeSnapshot]；任何字段缺失都当没有，不猜。 */
fun parseRuntimeSnapshot(root: JsonElement?): RuntimeSnapshot = RuntimeSnapshot(
    usage = parseContextUsage(root),
    model = parseRuntimeModel(root),
    thinkingLevel = (root as? JsonObject)?.let { obj ->
        (obj["thinkingLevel"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    },
)

/** `runtime/state` 里的 `provider` / `modelId` / `modelName` 就是 pi 当前真正在跑的模型。 */
fun parseRuntimeModel(root: JsonElement?): SessionModel? {
    val obj = root as? JsonObject ?: return null
    fun str(key: String): String? = (obj[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    val provider = str("provider")
    val modelId = str("modelId")
    // 两个都给不出来就是个空壳（运行时还没起来时宿主返回 `{}`），别造一个假模型出来
    if (provider == null && modelId == null) return null
    return SessionModel(
        provider = provider.orEmpty(),
        modelId = modelId.orEmpty(),
        modelName = str("modelName").orEmpty(),
    )
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

/**
 * 会话「权限位」按后端分发（判据照抄桌面端 `SecurityControl.tsx` + `ComposerComponents.tsx` 的屏蔽分支）：
 *
 * - pi（以及草稿会话那种 backend 为空的）→ **安全等级**（PiDeck 安全门，写 SecurityStore）；
 * - dsh → **权限预设**（read-only / workspace-write / danger-full-access，走 DSH host 的 `/permission`）；
 * - acp → 都不摆（权限由 agent CLI 自己的 session/request_permission 接管）；
 * - imagegen / videogen → 都不摆（没有 pi/DSH runtime）。
 *
 * 两套机制不能互相顶替：把 DSH 的 `permissionPreset` 写到 pi 会话记录上，主机会在激活时拿这个
 * 它不认识的预设去 apply，表现就是「调过一次权限之后再也不能继续对话」。
 */
enum class PrivilegeControl { SecurityLevel, PermissionPreset, None }

fun privilegeControlFor(backend: String?): PrivilegeControl = when (backend?.lowercase()) {
    "dsh" -> PrivilegeControl.PermissionPreset
    "acp", "imagegen", "videogen" -> PrivilegeControl.None
    else -> PrivilegeControl.SecurityLevel
}

/** 安全等级的一档（`/api/state` 的 `security.levels[i]`，与桌面端 `WebSecurityState` 同形）。 */
data class SecurityLevel(
    val id: String,
    val name: String,
    val description: String? = null,
)

/**
 * `/api/state` 的 `security` 投影（与桌面端 `WebSecurityState` 同名同义）。
 *
 * 只保留选择器要的东西：工具规则 / 目录边界属于主进程与扩展的执行契约，客户端既不展示也不改。
 * [sessionOverrides] 是「会话级覆盖」，没有条目就是跟随 [defaultLevelId]。
 */
data class SecurityState(
    val enabled: Boolean = true,
    val defaultLevelId: String? = null,
    val levels: List<SecurityLevel> = emptyList(),
    val sessionOverrides: Map<String, String> = emptyMap(),
) {
    fun levelOf(levelId: String?): SecurityLevel? = levels.firstOrNull { it.id == levelId }

    /** 本会话的覆盖等级；null = 没有覆盖（跟随全局默认）。 */
    fun overrideOf(sessionId: String?): String? = sessionId?.let { sessionOverrides[it] }

    fun effectiveId(sessionId: String?): String? = overrideOf(sessionId) ?: defaultLevelId

    fun labelOf(levelId: String?): String =
        levelOf(levelId)?.name ?: (levelId ?: "未设置")
}

/**
 * 解析 `security` 投影；拿不到等级表就返回 null（老主机的 `/api/state` 没有这个字段）。
 *
 * 宁可显示「主机没提供」，也不要拿一组假的内置等级去顶替 —— 点了会以 400 收场。
 */
fun parseSecurityState(root: JsonElement?): SecurityState? {
    val obj = root as? JsonObject ?: return null
    val levels = (obj["levels"] as? JsonArray).orEmpty().mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val id = (item["id"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        SecurityLevel(
            id = id,
            name = (item["name"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: id,
            description = (item["description"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() },
        )
    }
    if (levels.isEmpty()) return null
    val overrides = (obj["sessionOverrides"] as? JsonObject).orEmpty().mapNotNull { (key, value) ->
        val levelId = (value as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        key to levelId
    }.toMap()
    return SecurityState(
        enabled = (obj["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true,
        defaultLevelId = (obj["defaultLevelId"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() },
        levels = levels,
        sessionOverrides = overrides,
    )
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
