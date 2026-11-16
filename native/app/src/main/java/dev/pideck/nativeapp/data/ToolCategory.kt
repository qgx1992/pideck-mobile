package dev.pideck.nativeapp.data

/**
 * 工具归类：直接照搬桌面端 `ProcessGroupStep` 的 `EXACT_CATEGORY` / `PREFIX_CATEGORY`
 * （asar 里 `mermaid-GHXKKRXX-DTtnNymj.js` 的 `toolActivityCategory`）。
 *
 * 为什么要跟桌面端一模一样：过程组的组头、每一步前面的图标、以及「读了几次 / 跑了几次命令」
 * 这类摘要都按它算。手机端自己发明一套，同一个回合在两边就会显示成两种说法。
 */
enum class ToolCategory {
    Read,
    ReadImage,
    Search,
    Write,
    Edit,
    Commands,
    Code,
    WebSearch,
    WebFetch,
    Subagents,
    Plan,
    Questions,
    Tools,
}

object ToolCategoryMap {
    /** 精确名字（桌面端那张表的逐条对照）。 */
    private val EXACT: Map<String, ToolCategory> = mapOf(
        "read_image" to ToolCategory.ReadImage,
        "read" to ToolCategory.Read,
        "run_code" to ToolCategory.Code,
        "web_search" to ToolCategory.WebSearch,
        "grep" to ToolCategory.Search,
        "glob" to ToolCategory.Search,
        "search" to ToolCategory.Search,
        "find" to ToolCategory.Search,
        "ls" to ToolCategory.Search,
        "list" to ToolCategory.Search,
        "write" to ToolCategory.Write,
        "create" to ToolCategory.Write,
        "edit" to ToolCategory.Edit,
        "multi_edit" to ToolCategory.Edit,
        "apply_patch" to ToolCategory.Edit,
        "patch" to ToolCategory.Edit,
        "bash" to ToolCategory.Commands,
        "shell" to ToolCategory.Commands,
        "pwsh" to ToolCategory.Commands,
        "powershell" to ToolCategory.Commands,
        "exec_command" to ToolCategory.Commands,
        "write_stdin" to ToolCategory.Commands,
        "run" to ToolCategory.Commands,
        "web_fetch" to ToolCategory.WebFetch,
        "fetch" to ToolCategory.WebFetch,
        "fetch_content" to ToolCategory.WebFetch,
        "url" to ToolCategory.WebFetch,
        "http" to ToolCategory.WebFetch,
        "subagent" to ToolCategory.Subagents,
        "agent" to ToolCategory.Subagents,
        "task" to ToolCategory.Subagents,
        "todo" to ToolCategory.Plan,
        "todo_write" to ToolCategory.Plan,
        "todolist" to ToolCategory.Plan,
        "create_goal" to ToolCategory.Plan,
        "update_goal" to ToolCategory.Plan,
        "get_goal" to ToolCategory.Plan,
        "plan" to ToolCategory.Plan,
        "ask_question" to ToolCategory.Questions,
        "ask_user_question" to ToolCategory.Questions,
        "request_user_input" to ToolCategory.Questions,
    )

    /** 前缀归类（桌面端只有这两条）。 */
    private val PREFIXES: List<Pair<String, ToolCategory>> = listOf(
        "terminal_" to ToolCategory.Commands,
        "subagent_" to ToolCategory.Subagents,
    )

    fun of(rawName: String): ToolCategory {
        val name = rawName.trim().lowercase()
        if (name.isEmpty()) return ToolCategory.Tools
        // 桌面端：`*_inspect` 一律当检索（MCP 工具的「看细节」不动数据）
        if (name.endsWith("_inspect")) return ToolCategory.Search
        EXACT[name]?.let { return it }
        val prefix = PREFIXES.firstOrNull { name.startsWith(it.first) }
        return prefix?.second ?: ToolCategory.Tools
    }
}
