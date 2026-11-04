package dev.pideck.nativeapp.data

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
