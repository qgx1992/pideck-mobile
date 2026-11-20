package dev.pideck.nativeapp.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.pideck.nativeapp.data.FileNode
import dev.pideck.nativeapp.data.GitCommit
import dev.pideck.nativeapp.data.Session
import dev.pideck.nativeapp.data.PideckApi
import dev.pideck.nativeapp.data.SubagentEntry
import dev.pideck.nativeapp.data.WorkspaceChanges
import dev.pideck.nativeapp.data.WorkspaceTodo
import dev.pideck.nativeapp.data.describeConnectionError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 「工作区」抽屉三页（待办 / 改动 / 文件）的数据。
 *
 * 从 `ChatViewModel` 分出来的第二块：它只碰「设备 / 会话 / 项目」，与聊天流、发送、统计无关。
 * 界面仍读 `viewModel.workspaceTodo` 等转发属性，调用点没动。
 *
 * 五个请求各自独立失败：**失败原因要记账并说出来**，别退回「静默取空」——
 * 断网时界面会显示「没有待办 / 空文件树」，用户只会以为数据丢了。
 */
internal class WorkspaceController(
    private val scope: CoroutineScope,
    private val api: () -> PideckApi,
    private val device: () -> dev.pideck.nativeapp.data.Device?,
    private val token: () -> String?,
    private val session: () -> Session?,
    private val projectId: () -> String?,
    private val onSubagents: (List<SubagentEntry>) -> Unit,
) {
    var todo by mutableStateOf<WorkspaceTodo?>(null)
        private set

    var changes by mutableStateOf<WorkspaceChanges?>(null)
        private set

    var commits by mutableStateOf<List<GitCommit>>(emptyList())
        private set

    var files by mutableStateOf<List<FileNode>>(emptyList())
        private set

    /** 文件页当前所在目录（项目内相对路径，"" 是根）。 */
    var dir by mutableStateOf("")
        private set

    var busy by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    private var loaded = false

    fun load(force: Boolean = false) {
        if (loaded && !force) return
        loaded = true
        refresh()
    }

    fun refresh() {
        val target = device() ?: return
        val auth = token() ?: return
        val project = projectId() ?: run {
            error = "这个会话没有关联项目，看不到改动与文件"
            return
        }
        busy = true
        error = null
        val sessionId = session()?.id.orEmpty()
        val projectPath = session()?.projectPath
        scope.launch {
            try {
                val failures = linkedSetOf<String>()
                suspend fun <T> attempt(label: String, block: suspend () -> T): T? =
                    try {
                        block()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        if (e is CancellationException) throw e
                        failures += "$label：${describeConnectionError(e)}"
                        null
                    }

                val nextTodo = attempt("待办") { api().todo(target, auth, sessionId) }
                val nextChanges = attempt("改动") { api().changes(target, auth, project, projectPath) }
                val nextFiles = attempt("文件") { api().files(target, auth, project, dir) } ?: emptyList()
                val nextAgents = attempt("子任务") { api().subagents(target, auth, sessionId) } ?: emptyList()
                // 提交历史是锦上添花：拿不到（不是仓库 / 没装 git）就空着，不打断上面三页
                val nextCommits = if (nextChanges?.repo == true) {
                    attempt("提交历史") { api().gitLog(target, auth, project, limit = 15) }
                        ?: emptyList()
                } else {
                    emptyList()
                }
                commits = nextCommits
                todo = nextTodo
                changes = nextChanges
                files = nextFiles
                onSubagents(nextAgents)
                error = if (failures.isEmpty()) {
                    null
                } else {
                    "部分面板没读到（其余照常显示）：" + failures.joinToString("；")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = describeConnectionError(e)
            } finally {
                busy = false
            }
        }
    }

    fun openDir(target: String) {
        dir = target
        refresh()
    }
}
