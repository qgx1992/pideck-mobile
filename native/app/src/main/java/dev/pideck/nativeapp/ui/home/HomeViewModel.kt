package dev.pideck.nativeapp.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pideck.nativeapp.data.AcpTool
import dev.pideck.nativeapp.data.AgentBackend
import dev.pideck.nativeapp.data.Device
import dev.pideck.nativeapp.data.DeviceRepository
import dev.pideck.nativeapp.data.PideckApi
import dev.pideck.nativeapp.data.Prefs
import dev.pideck.nativeapp.data.Project
import dev.pideck.nativeapp.data.Session
import dev.pideck.nativeapp.data.StateCache
import dev.pideck.nativeapp.data.StateFetch
import dev.pideck.nativeapp.data.describeConnectionError
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 首页（对齐桌面网页版的默认落地页）：选一个工作区，直接发第一句话。
 *
 * 与后端只打两次照面：`/api/state`（拿工作区与最近会话）+ `POST /api/sessions`（开新会话）。
 * 开完就交棒给聊天页 —— 首句通过路由参数带过去，由 [dev.pideck.nativeapp.ui.chat.ChatViewModel] 投递，
 * 免得首页在这里自己跑一条 SSE。
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: DeviceRepository,
    private val api: PideckApi,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val deviceId: String = savedStateHandle["deviceId"] ?: error("缺少 deviceId")

    private var device: Device? = null
    private var token: String? = null
    private var etag: String? = null

    var deviceName by mutableStateOf<String?>(null)
        private set

    var projects by mutableStateOf<List<Project>>(emptyList())
        private set

    /** 最近会话（跨工作区，按更新时间倒序，最多 10 条）。 */
    var recent by mutableStateOf<List<Session>>(emptyList())
        private set

    var selectedProjectId by mutableStateOf<String?>(null)
        private set

    var loading by mutableStateOf(true)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var creating by mutableStateOf(false)
        private set

    /** `/api/state` 里的 ACP 工具表：能选的 ACP 后端就是它。 */
    var acpTools by mutableStateOf<List<AcpTool>>(emptyList())
        private set

    /** 本地记住的上次选择（`""` / `dsh` / `acp:<toolId>`），见 [AgentBackend.parse]。 */
    private var storedBackendId by mutableStateOf("")
    private var backendTouched = false

    /** 可选项：pi + DSH + 每个 ACP 工具。 */
    val backendOptions: List<AgentBackend>
        get() = AgentBackend.options(acpTools)

    /** 当前选中的后端（ACP 工具没加载出来时会退回 pi，不会建出个空后端）。 */
    val backend: AgentBackend
        get() = AgentBackend.parse(storedBackendId, acpTools)

    /** 换后端：只影响「下一次新建」，不碰已有会话。 */
    fun selectBackend(next: AgentBackend) {
        storedBackendId = AgentBackend.idOf(next)
        backendTouched = true
        viewModelScope.launch { runCatching { Prefs.setAgentBackendId(context, storedBackendId) } }
    }

    val selectedProject: Project?
        get() = projects.firstOrNull { it.id == selectedProjectId }

    fun projectNameOf(projectId: String?): String =
        projects.firstOrNull { it.id == projectId }?.name?.ifBlank { null } ?: "未命名工作区"

    init {
        viewModelScope.launch {
            val found = repository.devices.first { list -> list.any { it.id == deviceId } }
                .firstOrNull { it.id == deviceId }
            if (found != null) {
                device = found
                token = repository.tokenOf(found.id)
                deviceName = found.name
            }
            storedBackendId = Prefs.agentBackendId
            // 列表页刚拉过的状态先垫上，首页不至于空着等网络
            StateCache.get(deviceId)?.let { apply(it.projects, it.sessions, it.acpTools) }
            load()
        }
    }

    fun load() {
        val target = device ?: run {
            loading = false
            error = "设备已不存在，回设备页重新添加"
            return
        }
        viewModelScope.launch {
            loading = true
            error = null
            try {
                when (val fetched = api.state(target, token, etag)) {
                    is StateFetch.Fresh -> {
                        etag = fetched.etag
                        StateCache.put(deviceId, fetched.state)
                        apply(fetched.state.projects, fetched.state.sessions, fetched.state.acpTools)
                    }

                    StateFetch.NotModified -> Unit
                }
            } catch (e: Exception) {
                error = describeConnectionError(e)
            } finally {
                loading = false
            }
        }
    }

    fun select(projectId: String) {
        selectedProjectId = projectId
    }

    /**
     * 在 [projectId] 下开一个空会话，成功后把 id 交回 UI（UI 负责跳转）。
     *
     * 选的后端一并写进记录：pi 不写 `backend`，DSH 写 `"dsh"`，ACP 写 `"acp"` + `acpToolId`。
     * 失败走 [error]，不抛异常 —— 首页的失败提示是常驻的一行红字，不用弹窗。
     */
    fun startSession(projectId: String, onCreated: (String) -> Unit) {
        val target = device ?: return
        if (creating) return
        viewModelScope.launch {
            creating = true
            error = null
            try {
                val chosen = backend
                val id = api.createSession(
                    target,
                    token,
                    projectId,
                    backend = chosen.wire,
                    acpToolId = (chosen as? AgentBackend.Acp)?.toolId,
                )
                onCreated(id)
            } catch (e: Exception) {
                error = describeConnectionError(e)
            } finally {
                creating = false
            }
        }
    }

    private fun apply(nextProjects: List<Project>, nextSessions: List<Session>, tools: List<AcpTool>) {
        acpTools = tools
        // 首次拿到状态前 Prefs 可能还没读完盘，这里补一次；用户已经点过就不再覆盖
        if (!backendTouched) storedBackendId = Prefs.agentBackendId
        projects = nextProjects.sortedWith(
            compareByDescending<Project> { it.kind == "chat" }
                .thenByDescending { it.pinned }
                .thenByDescending { it.lastOpenedAt },
        )
        recent = nextSessions
            .filter { it.parentSessionId == null }
            .sortedByDescending { it.updatedAt }
            .take(10)
        if (projects.none { it.id == selectedProjectId }) {
            selectedProjectId = projects.firstOrNull()?.id
        }
    }
}
