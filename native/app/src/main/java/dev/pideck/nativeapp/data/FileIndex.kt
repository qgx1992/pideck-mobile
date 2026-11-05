package dev.pideck.nativeapp.data

/**
 * `@` 引用用的文件索引。
 *
 * `/api/files` 只列一层，所以索引是按目录广度优先扫出来的（有上限）。这里放纯逻辑，
 * 拉取在 `ChatViewModel.buildMentionIndex()` 里做 —— 方便单测。
 */
object FileIndex {

    /** 扫描上限：目录数、节点数。手机上两万节点的候选列表既没用又费内存。 */
    const val MAX_DIRS = 90
    const val MAX_NODES = 3000

    /** 候选条数上限。 */
    const val MAX_SUGGESTIONS = 24

    /** 这些目录进去只有噪音：依赖、产物、缓存。名字小写比较。 */
    private val SKIP_DIRS = setOf(
        ".git", ".hg", ".svn", ".idea", ".vscode", ".gradle", ".kotlin", ".dart_tool",
        ".next", ".nuxt", ".cache", ".tmp", ".venv", "venv", "__pycache__", "node_modules",
        "build", "dist", "out", "target", "obj", "coverage", "vendor_build", "pods",
    )

    fun shouldSkipDir(name: String): Boolean = name.lowercase() in SKIP_DIRS

    /**
     * 排序 + 过滤候选。
     *
     * 打分顺序：名字完全匹配 → 名字前缀 → 名字包含 → 路径包含 → 名字子序列（`cskt` 命中 `ChatScreen.kt`）。
     * 同分时目录优先、路径短的优先（浅层更常被引用）。
     */
    fun rank(files: List<FileNode>, query: String, limit: Int = MAX_SUGGESTIONS): List<FileNode> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) {
            return files
                .sortedWith(compareBy({ !it.isDir }, { it.path.count { ch -> ch == '/' } }, { it.path }))
                .take(limit)
        }
        val scored = files.mapNotNull { node ->
            val name = node.name.lowercase()
            val path = node.path.lowercase()
            val score = when {
                name == needle -> 0
                name.startsWith(needle) -> 1
                name.contains(needle) -> 2
                path.contains(needle) -> 3
                isSubsequence(needle, name) -> 4
                else -> null
            }
            score?.let { node to it }
        }
        return scored
            .sortedWith(
                compareBy(
                    { it.second },
                    { !it.first.isDir },
                    { it.first.path.length },
                    { it.first.path },
                ),
            )
            .map { it.first }
            .take(limit)
    }

    /** 子序列匹配：查询的每个字符按顺序出现在名字里即可（IDE 里那种模糊搜索）。 */
    fun isSubsequence(needle: String, haystack: String): Boolean {
        if (needle.isEmpty()) return true
        var index = 0
        for (ch in haystack) {
            if (ch == needle[index]) {
                index++
                if (index == needle.length) return true
            }
        }
        return false
    }

    /** 候选行里显示的那一点点上下文（父目录），根目录下的文件返回空串。 */
    fun parentOf(path: String): String =
        path.substringBeforeLast('/', "")
}

/** 宿主上一条斜杠命令（`runtime/commands` 的返回项）。 */
data class HostCommand(
    val name: String,
    val description: String,
    val source: String,
) {
    /** `skill:image-gen` 显示成 `image-gen`，但插进输入框的仍是全名（pi 只认全名）。 */
    val displayName: String
        get() = if (':' in name && source == "skill") name.substringAfter(':') else name

    val sourceLabel: String
        get() = when (source) {
            "extension" -> "扩展"
            "prompt" -> "模板"
            "skill" -> "技能"
            else -> source
        }
}

/** 解析 `{"result":{"ok":true,"value":{"target":…,"value":[{name,description,source}]}}}`。 */
fun parseHostCommands(root: kotlinx.serialization.json.JsonElement?): List<HostCommand> {
    val ok = root as? kotlinx.serialization.json.JsonObject ?: return emptyList()
    val result = ok["result"] as? kotlinx.serialization.json.JsonObject
    val outer = result?.get("value") as? kotlinx.serialization.json.JsonObject ?: return emptyList()
    val list = outer["value"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
    return list.mapNotNull { element ->
        val item = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
        val name = (item["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        HostCommand(
            name = name,
            description = (item["description"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty(),
            source = (item["source"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty(),
        )
    }
}
