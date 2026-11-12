package dev.pideck.nativeapp.ui.home

import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pideck.nativeapp.data.AcpTool
import dev.pideck.nativeapp.data.AvailableModel
import dev.pideck.nativeapp.data.AgentBackend
import dev.pideck.nativeapp.data.Device
import dev.pideck.nativeapp.data.PideckApi
import dev.pideck.nativeapp.data.Project
import dev.pideck.nativeapp.data.STANDARD_THINKING_LEVELS
import dev.pideck.nativeapp.data.SessionModel
import dev.pideck.nativeapp.data.Session
import dev.pideck.nativeapp.data.ConnectionResolver
import dev.pideck.nativeapp.ui.common.LoadState
import dev.pideck.nativeapp.ui.common.failureOf
import dev.pideck.nativeapp.ui.common.isLoading
import dev.pideck.nativeapp.ui.common.message
import dev.pideck.nativeapp.data.SessionStateStore
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
    private val connections: ConnectionResolver,
    private val api: PideckApi,
    private val stateStore: SessionStateStore,
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

    /**
     * 这一屏「拉到哪一步了」（0.5.42 统一成一份）。界面靠它区分
     * 「还在拉」/「拉完了但真没内容」/「拉失败了」—— 以前只有 `loading` + `error` 两个布尔式字段，
     * 空白页看不出到底是哪种。
     */
    var loadState by mutableStateOf<LoadState>(LoadState.Idle)
        private set

    /** 动作类失败（开新会话、切后端之类），与「这一屏拉没拉到」分开记。 */
    var actionError by mutableStateOf<String?>(null)
        private set

    val loading: Boolean get() = loadState.isLoading

    /** 界面看的那一行红字：动作错误优先，其次是这一屏的拉取失败。 */
    val error: String? get() = actionError ?: loadState.message

    var creating by mutableStateOf(false)
        private set

    /** `/api/state` 里的 ACP 工具表：能选的 ACP 后端就是它。 */
    var acpTools by mutableStateOf<List<AcpTool>>(emptyList())
        private set

    /** 可选项：pi + DSH + 每个 ACP 工具。 */
    val backendOptions: List<AgentBackend>
        get() = AgentBackend.options(acpTools)

    /**
     * 当前选中的后端，**每次进首页都是 pi**。
     *
     * 不记上次选择：桌面端的默认后端（`defaultAgentBackend`）不经 HTTP 暴露，我们没法知道它填什么，
     * 与其猜一个「上次用的」当默认，不如老老实实跟 pi 对齐 —— 想换的人自然会去点。
     */
    var backend by mutableStateOf<AgentBackend>(AgentBackend.Pi)
        private set

    /** 换后端：只影响「下一次新建」，不碰已有会话。 */
    fun selectBackend(next: AgentBackend) {
        backend = next
    }

    /**
     * `/api/models` 的清单：只给「新会话用哪个模型」这一颗胶囊。
     *
     * 默认 `null` = **跟随电脑端默认**：不带模型字段，宿主按它自己的链路解析
     * （`welcomeModel ?? 设置里的默认模型 ?? 第一个启用模型 ?? 上次用的`）。
     * 本端不记上次的选择 —— 桌面端默认值不经 HTTP 暴露，猜一个「上次用的」当默认
     * 会比跟机器对齐更容易让人困惑（0.5.25/0.5.26 踩过）。
     */
    var selectedModel by mutableStateOf<SessionModel?>(null)
        private set

    var models by mutableStateOf<List<AvailableModel>>(emptyList())
        private set

    var modelsBusy by mutableStateOf(false)
        private set

    /**
     * 新会话的思考档位；`null` = 跟随电脑端默认（宿主按模型能力 → `defaultThinkingLevel` 解析）。
     */
    var selectedThinking by mutableStateOf<String?>(null)
        private set

    /**
     * 可选的思考档位。
     *
     * 选了具体模型就只列它支持的档位（`/api/models` 的 `thinkingLevels`）——
     * 不在里面的档位传上去会被主机拒或静默降级；还没选模型、或者模型表没拉到，
     * 就用 pi 的标准档位表。
     */
    val thinkingLevels: List<String>
        get() {
            val current = selectedModel ?: return STANDARD_THINKING_LEVELS
            val entry = models.firstOrNull { it.provider == current.provider && it.id == current.modelId }
            return entry?.thinkingLevels?.takeIf { it.isNotEmpty() } ?: STANDARD_THINKING_LEVELS
        }

    /** 选中的模型明确不支持思考时，思考胶囊只显示「不思考」并且不可点。 */
    val thinkingAvailable: Boolean
        get() {
            val current = selectedModel ?: return true
            val entry = models.firstOrNull { it.provider == current.provider && it.id == current.modelId }
            return entry?.reasoning ?: true
        }

    /** 选模型：`null` 表示回到「跟随电脑端默认」；顺带清掉档位，免得留下上一个模型的档位。 */
    fun selectModel(next: SessionModel?) {
        selectedModel = next
        selectedThinking = null
    }

    /** 选思考档位：`null` 表示回到「跟随电脑端默认」。 */
    fun selectThinking(next: String?) {
        selectedThinking = next
    }

    /**
     * 拉模型清单。失败不弹窗（首页只有一行红字位，留给「新建失败」这种真错误）；
     * 胶囊自己会显示「模型列表没拉到 · 点这里重试」。
     */
    fun loadModels(force: Boolean = false) {
        if (modelsBusy) return
        viewModelScope.launch {
            modelsBusy = true
            try {
                val target = device ?: return@launch
                models = api.models(target, token, force)
            } catch (e: Exception) {
                if (e is CancellationException) throw e

                // 清单拿不到不影响新建会话：不带模型就是「跟随电脑端默认」
            } finally {
                modelsBusy = false
            }
        }
    }

    val selectedProject: Project?
        get() = projects.firstOrNull { it.id == selectedProjectId }

    fun projectNameOf(projectId: String?): String =
        projects.firstOrNull { it.id == projectId }?.name?.ifBlank { null } ?: "未命名工作区"

    init {
        viewModelScope.launch {
            // 设备解析统一走 ConnectionResolver（5 秒上限，读盘抽风也不会把首页冻住）。
            // 以前这里没有超时，DataStore 不吐时首页就是空白。会话列表页也有一份同样的代码，
            // 现在两边都收口到同一个实现，别再各写一遍。
            connections.resolve(deviceId)?.let { conn ->
                device = conn.device
                token = conn.token
                deviceName = conn.device.name
            }
            // 列表页刚拉过的状态先垫上，首页不至于空着等网络
            stateStore.cached(deviceId)?.let { apply(it.projects, it.sessions, it.acpTools) }
            load()
            loadModels()
        }
    }

    /** 上一次 load 开始的时刻：回前台时太近就不重复拉（切页来回点不至于每次都打宿主）。 */
    private var lastLoadAt = 0L

    fun onForeground() {
        if (System.currentTimeMillis() - lastLoadAt < 20_000L) return
        load()
        loadModels()
    }

    fun load() {
        lastLoadAt = System.currentTimeMillis()
        val target = device ?: run {
            loadState = failureOf("设备已不存在，回设备页重新添加")
            return
        }
        viewModelScope.launch {
            loadState = LoadState.Loading
            try {
                when (val fetched = api.state(target, token, etag)) {
                    is StateFetch.Fresh -> {
                        etag = fetched.etag
                        stateStore.put(deviceId, fetched.state)
                        apply(fetched.state.projects, fetched.state.sessions, fetched.state.acpTools)
                    }

                    StateFetch.NotModified -> Unit
                }
                if (loadState !is LoadState.Ready) loadState = LoadState.Ready
            } catch (e: Exception) {
                if (e is CancellationException) throw e

                loadState = failureOf(e)
            }
        }
    }

    fun select(projectId: String) {
        selectedProjectId = projectId
    }

    /** 新建工作区的输入框与状态（首页那颗胶囊的下拉里有一项「新建工作区…」）。 */
    var workspaceEditorOpen by mutableStateOf(false)

    var workspacePath by mutableStateOf("")

    var workspaceBusy by mutableStateOf(false)

    var workspaceError by mutableStateOf<String?>(null)

    fun openWorkspaceEditor() {
        workspacePath = ""
        workspaceError = null
        workspaceEditorOpen = true
    }

    fun closeWorkspaceEditor() {
        if (workspaceBusy) return
        workspaceEditorOpen = false
    }

    fun editWorkspacePath(value: String) {
        workspacePath = value
    }

    /**
     * 在桌面端那台机器上登记一个目录作为工作区。
     *
     * 宿主只认「路径非空」：路径写错也会建出来一个空项目，所以成功后把新建的那个直接选上
     * （`POST` 有回包就按 id 选，没回包就按路径找），让用户立刻看到结果。
     */
    fun createWorkspace() {
        val target = device ?: return
        val path = workspacePath.trim()
        if (path.isEmpty() || workspaceBusy) return
        viewModelScope.launch {
            workspaceBusy = true
            workspaceError = null
            try {
                val created = api.createProject(target, token, path)
                // 新建的项目一定不在旧快照里，清掉 ETag 逼一次全量，别拿 304 把新项目挡掉
                etag = null
                load()
                val id = created?.id
                if (id != null) {
                    selectedProjectId = id
                    workspaceEditorOpen = false
                    workspacePath = ""
                } else {
                    workspaceError = "桌面端没有回报这个工作区，刷新一下看看"
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e

                workspaceError = describeConnectionError(e)
            } finally {
                workspaceBusy = false
            }
        }
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
            actionError = null
            try {
                val chosen = backend
                val id = api.createSession(
                    target,
                    token,
                    projectId,
                    backend = chosen.wire,
                    acpToolId = (chosen as? AgentBackend.Acp)?.toolId,
                    // 只有 pi 吃这份偏好（见 ChatPayloads.createSessionBody）
                    model = selectedModel.takeIf { chosen == AgentBackend.Pi },
                    thinkingLevel = selectedThinking.takeIf { chosen == AgentBackend.Pi },
                )
                onCreated(id)
            } catch (e: Exception) {
                if (e is CancellationException) throw e

                actionError = describeConnectionError(e)
            } finally {
                creating = false
            }
        }
    }

    private fun apply(nextProjects: List<Project>, nextSessions: List<Session>, tools: List<AcpTool>) {
        acpTools = tools
        // 选中的 ACP 工具在桌面端被删了：退回 pi，别留着建一个宿主不认识的会话
        if (backend is AgentBackend.Acp && tools.none { it.id == (backend as AgentBackend.Acp).toolId }) {
            backend = AgentBackend.Pi
        }
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
