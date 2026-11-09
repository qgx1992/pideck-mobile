package dev.pideck.nativeapp.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 过程组的「活动类别」归纳（对齐桌面端
 * `app/vendor/pideck/src/renderer/src/components/session/timeline/toolCategory.ts`）。
 *
 * 折叠态的组头不罗列工具名，而是概括「这组干了哪类事」：读取 ×3 · 搜索 ×2 · 思考 ×1。
 * 分类只依据工具名本身，不做语义猜测（`mcp__fs__read` 这类带命名空间的落进 [Tools]）。
 */
enum class ActivityKind(val label: String) {
    Read("读取"),
    ReadImage("看图"),
    Search("搜索"),
    Write("新建"),
    Edit("编辑"),
    Commands("执行命令"),
    Code("跑代码"),
    WebSearch("网络搜索"),
    WebFetch("抓取网页"),
    Subagents("子代理"),
    Plan("整理计划"),
    Questions("提问"),
    Tools("工具"),
}

object ProcessActivity {

    /** 精确名表：命中即该类。 */
    private val exact: Map<String, ActivityKind> = mapOf(
        "read_image" to ActivityKind.ReadImage,
        "read" to ActivityKind.Read,
        "run_code" to ActivityKind.Code,
        "web_search" to ActivityKind.WebSearch,
        "grep" to ActivityKind.Search,
        "glob" to ActivityKind.Search,
        "search" to ActivityKind.Search,
        "find" to ActivityKind.Search,
        "ls" to ActivityKind.Search,
        "list" to ActivityKind.Search,
        "write" to ActivityKind.Write,
        "create" to ActivityKind.Write,
        "edit" to ActivityKind.Edit,
        "multi_edit" to ActivityKind.Edit,
        "apply_patch" to ActivityKind.Edit,
        "patch" to ActivityKind.Edit,
        "bash" to ActivityKind.Commands,
        "shell" to ActivityKind.Commands,
        "pwsh" to ActivityKind.Commands,
        "powershell" to ActivityKind.Commands,
        "exec_command" to ActivityKind.Commands,
        "write_stdin" to ActivityKind.Commands,
        "run" to ActivityKind.Commands,
        "web_fetch" to ActivityKind.WebFetch,
        "fetch" to ActivityKind.WebFetch,
        "fetch_content" to ActivityKind.WebFetch,
        "url" to ActivityKind.WebFetch,
        "http" to ActivityKind.WebFetch,
        "subagent" to ActivityKind.Subagents,
        "agent" to ActivityKind.Subagents,
        "task" to ActivityKind.Subagents,
        "todo" to ActivityKind.Plan,
        "todo_write" to ActivityKind.Plan,
        "todolist" to ActivityKind.Plan,
        "create_goal" to ActivityKind.Plan,
        "update_goal" to ActivityKind.Plan,
        "get_goal" to ActivityKind.Plan,
        "plan" to ActivityKind.Plan,
        "ask_question" to ActivityKind.Questions,
        "ask_user_question" to ActivityKind.Questions,
        "request_user_input" to ActivityKind.Questions,
    )

    /** 前缀表：顺序即优先级。 */
    private val prefixes: List<Pair<String, ActivityKind>> = listOf(
        "terminal_" to ActivityKind.Commands,
        "subagent_" to ActivityKind.Subagents,
    )

    fun categoryOf(toolName: String): ActivityKind {
        val key = toolName.trim().lowercase()
        if (key.isEmpty()) return ActivityKind.Tools
        // `subagent_inspect` 归搜索而不是子代理（与桌面端同规则）
        if (key.endsWith("_inspect")) return ActivityKind.Search
        exact[key]?.let { return it }
        prefixes.firstOrNull { key.startsWith(it.first) }?.let { return it.second }
        return ActivityKind.Tools
    }

    /** 工具名 → 类别计数；次数降序，同次数保持首次出现顺序（流式增量时组头文字不会来回跳）。 */
    fun counts(toolNames: List<String>): List<Pair<ActivityKind, Int>> {
        val order = mutableListOf<ActivityKind>()
        val tally = mutableMapOf<ActivityKind, Int>()
        for (name in toolNames) {
            if (name.isBlank()) continue
            val kind = categoryOf(name)
            if (tally[kind] == null) order += kind
            tally[kind] = (tally[kind] ?: 0) + 1
        }
        return order.map { it to (tally[it] ?: 0) }
            .sortedByDescending { it.second }
    }

    /**
     * 组头右侧那截实时细节：正在跑的那条工具的参数摘要（对齐桌面端 `lastToolDetail`）。
     *
     * 桌面端把参数规整成一句人话（「读取 README.md」），手机这边没有那套 phrase 表，
     * 就从参数里挑最像「在干什么」的那个字段，压成一行给组头看 —— 折叠状态下也能知道在动哪个文件。
     */
    fun toolDetail(tool: ToolView): String? = detailFromArgs(tool.input)

    /** 参数 JSON → 一行摘要（先按常用字段挑，挑不到就取第一个非空字符串值）。 */
    fun detailFromArgs(input: String?): String? {
        val raw = input?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val element = runCatching { Json.parseToJsonElement(raw) }.getOrNull()
        val picked = pickDetail(element)
            ?: (element as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val lines = picked?.lineSequence()
            ?.map { it.trim() }?.filter { it.isNotEmpty() }?.toList().orEmpty()
        if (lines.isEmpty()) return null
        val text = lines.joinToString(" · ")
        return if (text.length > 90) text.take(90) + "…" else text
    }

    private fun pickDetail(element: JsonElement?): String? {
        val obj = element as? JsonObject ?: return null
        for (key in DETAIL_KEYS) {
            val value = obj[key] as? JsonPrimitive ?: continue
            val text = value.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
            return text
        }
        // 没有常见字段（`mcp__xxx__do` 这类）：按顺序拿第一个非空字符串值，聊胜于无。
        return obj.values.firstNotNullOfOrNull { value ->
            (value as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        }
    }

    /** 组头摘要：运行中给「正在读取…」，结束后给「读取 ×3 · 思考 ×2」。 */
    fun summary(
        toolNames: List<String>,
        thinkingCount: Int,
        running: Boolean,
        maxKinds: Int = 3,
    ): String {
        if (running) {
            val lastTool = toolNames.lastOrNull { it.isNotBlank() }
            if (lastTool == null) return if (thinkingCount > 0) "正在思考" else "进行中"
            val kind = categoryOf(lastTool)
            return if (kind == ActivityKind.Tools) "正在调用工具" else "正在${kind.label}"
        }
        val parts = mutableListOf<String>()
        counts(toolNames).take(maxKinds).forEach { (kind, count) -> parts += "${kind.label} ×$count" }
        if (thinkingCount > 0) parts += "思考 ×$thinkingCount"
        return if (parts.isEmpty()) "过程" else parts.joinToString(" · ")
    }
}

/** 组内成员的挂载预算（对齐桌面端 `PROCESS_GROUP_MEMBER_LIMIT = 32`）。 */
const val PROCESS_MEMBER_BUDGET = 32

/**
 * 组内实际渲染多少条成员（`batches` = 点了多少次「显示更早的 N 条步骤」）。
 *
 * 从末尾往前留：过程组是往后长的，真正想看的是刚发生的那几条。
 */
fun visibleMemberCount(total: Int, batches: Int, budget: Int = PROCESS_MEMBER_BUDGET): Int {
    if (total <= 0) return 0
    if (budget <= 0) return total
    val steps = batches.coerceAtLeast(0) + 1
    val shown = budget.toLong() * steps
    return if (shown >= total) total else shown.toInt()
}

/** 挑细节时优先看的参数名（命令 > 路径 > 检索词 > 描述），顺序即优先级。 */
private val DETAIL_KEYS = listOf(
    "command", "cmd", "script",
    "path", "file_path", "file", "filename", "notebook_path",
    "pattern", "query", "q", "search",
    "url", "prompt", "description", "name", "title",
)
