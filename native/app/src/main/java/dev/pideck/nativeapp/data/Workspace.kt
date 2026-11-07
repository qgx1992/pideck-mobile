package dev.pideck.nativeapp.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 工作区面板要的三份数据：待办、改动、文件。
 *
 * 这三份接口返回的形状都比 `/api/state` 松（字段可有可无、还会长新字段），
 * 所以不用 @Serializable 绑死结构 —— 手写一层宽松解析，桌面端加字段这边不会崩。
 */

data class TodoEntry(val id: Int, val text: String, val status: String) {
    val isDone: Boolean get() = status == "completed"
    val isActive: Boolean get() = status == "in_progress"
}

data class WorkspaceTodo(val planId: Int?, val todos: List<TodoEntry>) {
    val done: Int get() = todos.count { it.isDone }
    val active: TodoEntry? get() = todos.firstOrNull { it.isActive }
}

data class ChangedFile(val path: String, val letter: String, val group: String)

data class WorkspaceChanges(val repo: Boolean, val branch: String?, val files: List<ChangedFile>) {
    val isEmpty: Boolean get() = files.isEmpty()
}

data class FileNode(val name: String, val path: String, val isDir: Boolean)

object WorkspaceParser {

    fun todo(root: JsonElement): WorkspaceTodo? {
        val plan = (root as? JsonObject)?.get("todo") as? JsonObject ?: return null
        val list = plan.array("todos") ?: return null
        val todos = list.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            TodoEntry(
                id = obj.int("id") ?: 0,
                text = obj.str("text") ?: return@mapNotNull null,
                status = obj.str("status") ?: "pending",
            )
        }
        return WorkspaceTodo(planId = plan.int("planId"), todos = todos)
    }

    /** [projectPath] 用来把 git 返回的绝对路径裁成相对路径（Windows 上是反斜杠）。 */
    fun changes(root: JsonElement, projectPath: String?): WorkspaceChanges {
        val obj = root as? JsonObject ?: return WorkspaceChanges(false, null, emptyList())
        val repo = obj["repo"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        val branch = (obj["branch"] as? JsonObject)?.str("current")
        val groups = obj["groups"] as? JsonObject
            ?: return WorkspaceChanges(repo, branch, emptyList())
        val files = ArrayList<ChangedFile>()
        // untracked 的 letter 是 "U"，index 是暂存区，workingTree 是已跟踪的改动
        val labels = listOf("merge", "index", "workingTree", "untracked")
        for (key in labels) {
            val arr = groups.array(key) ?: continue
            for (item in arr) {
                val entry = item as? JsonObject ?: continue
                val raw = entry.str("path") ?: continue
                files += ChangedFile(
                    path = relativize(raw, projectPath),
                    letter = entry.str("letter") ?: "?",
                    group = key,
                )
            }
        }
        return WorkspaceChanges(repo, branch, files)
    }

    fun files(root: JsonElement): List<FileNode> {
        val list = (root as? JsonObject)?.array("nodes") ?: return emptyList()
        return list.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val name = obj.str("name") ?: return@mapNotNull null
            FileNode(
                name = name,
                path = obj.str("relativePath") ?: name,
                isDir = obj.str("type") == "directory",
            )
        }.sortedWith(compareByDescending<FileNode> { it.isDir }.thenBy { it.name.lowercase() })
    }

    fun relativize(path: String, projectPath: String?): String {
        val normalized = path.replace('\\', '/')
        if (projectPath.isNullOrBlank()) return normalized
        val base = projectPath.replace('\\', '/').trimEnd('/') + "/"
        return normalized.removePrefix(base)
    }

    private fun JsonObject.array(key: String): JsonArray? = (this[key] as? JsonArray)

    private fun JsonObject.str(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
            ?: (this[key] as? JsonObject)?.let { (it["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content }

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)
            ?.content?.toDoubleOrNull()?.toInt()

    @Suppress("unused")
    private fun JsonElement.asArrayOrNull(): JsonArray? = this as? JsonArray
}

/** 一条提交（`GET /api/git/log?projectId=&limit=`）。 */
data class GitCommit(
    val hash: String,
    val shortHash: String,
    /** 提交信息首行（多行的只留标题行，手机上换行会把列表撑乱）。 */
    val title: String,
    val authorName: String,
    /** 作者时间（毫秒）。宿主给的是 epoch ms，别用 locale 格式化。 */
    val authorDateMs: Long,
    val refNames: List<String>,
) {
    /** `HEAD -> master, origin/master` 这类引用，列表里只显示前两个。 */
    val refs: String get() = refNames.take(2).joinToString(", ")
}

object GitLogParser {
    fun commits(text: String): List<GitCommit> {
        val root = runCatching { PideckJson.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return emptyList()
        val array = root["commits"] as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val hash = obj["hash"].str() ?: return@mapNotNull null
            GitCommit(
                hash = hash,
                shortHash = obj["shortHash"].str() ?: hash.take(7),
                title = (obj["message"].str() ?: "").lineSequence().firstOrNull().orEmpty().trim(),
                authorName = obj["authorName"].str() ?: "",
                authorDateMs = obj["authorDate"].str()?.toDoubleOrNull()?.toLong() ?: 0L,
                refNames = (obj["refNames"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    ?: emptyList(),
            )
        }
    }

    private fun kotlinx.serialization.json.JsonElement?.str(): String? =
        (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}
