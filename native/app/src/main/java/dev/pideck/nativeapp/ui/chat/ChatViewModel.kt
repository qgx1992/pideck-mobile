package dev.pideck.nativeapp.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import android.net.Uri
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pideck.nativeapp.data.Device
import dev.pideck.nativeapp.data.ChangedFile
import dev.pideck.nativeapp.data.DeviceRepository
import dev.pideck.nativeapp.data.PideckApi
import dev.pideck.nativeapp.data.ImagePrep
import dev.pideck.nativeapp.data.LineDiff
import dev.pideck.nativeapp.data.MessageImage
import dev.pideck.nativeapp.data.PendingImage
import dev.pideck.nativeapp.data.BusySendMode
import dev.pideck.nativeapp.data.ChatMessage
import dev.pideck.nativeapp.data.ChatPayloads
import dev.pideck.nativeapp.data.PendingUiRequest
import dev.pideck.nativeapp.data.PideckJson
import dev.pideck.nativeapp.data.PideckState
import dev.pideck.nativeapp.data.PrivilegeControl
import dev.pideck.nativeapp.data.SecurityState
import dev.pideck.nativeapp.data.parseSecurityState
import dev.pideck.nativeapp.data.privilegeControlFor
import dev.pideck.nativeapp.data.Prefs
import dev.pideck.nativeapp.data.Session
import dev.pideck.nativeapp.data.ReplyMerger
import dev.pideck.nativeapp.data.ReplySnapshot
import dev.pideck.nativeapp.data.SessionActivity
import dev.pideck.nativeapp.data.StateCache
import dev.pideck.nativeapp.data.StateFetch
import dev.pideck.nativeapp.data.AskUi
import dev.pideck.nativeapp.data.AvailableModel
import dev.pideck.nativeapp.data.SessionModel
import dev.pideck.nativeapp.data.SubagentEntry
import dev.pideck.nativeapp.data.toSessionModel
import dev.pideck.nativeapp.data.FileNode
import dev.pideck.nativeapp.data.TimelineItem
import dev.pideck.nativeapp.data.TurnClock
import dev.pideck.nativeapp.data.TurnStats
import dev.pideck.nativeapp.data.deriveTurnStats
import dev.pideck.nativeapp.data.turnStatsFromMeta
import dev.pideck.nativeapp.data.WorkspaceChanges
import dev.pideck.nativeapp.data.WorkspaceTodo
import dev.pideck.nativeapp.data.describeConnectionError
import dev.pideck.nativeapp.data.toTimelineItem
import dev.pideck.nativeapp.data.toTimelineItems
import dev.pideck.nativeapp.data.ContextUsage
import dev.pideck.nativeapp.data.FileIndex
import dev.pideck.nativeapp.data.FileSaver
import dev.pideck.nativeapp.data.HostCommand
import dev.pideck.nativeapp.data.parseHostCommands
import dev.pideck.nativeapp.data.PermissionPresets
import dev.pideck.nativeapp.data.PideckException
import dev.pideck.nativeapp.data.RuntimeTarget
import dev.pideck.nativeapp.data.exportFileName
import dev.pideck.nativeapp.data.htmlLooksValid
import dev.pideck.nativeapp.watch.RunWatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

/**
 * 聊天页状态机。
 *
 * 数据流两条：
 * 1. 历史 —— `GET /api/sessions/:id/messages/page`，一次一页（`nextBefore` 往前翻）；
 * 2. 本轮 —— `POST /api/chat` 的 SSE 响应，喂给 [ReplyMerger] 得到分部快照。
 *
 * 发完以服务端历史为准重拉一次：流式过程中拼出来的文本与磁盘上的权威记录（含工具卡、
 * usage）可能不完全一致，收尾对齐才不会出现「刷新一下就变了」。
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: DeviceRepository,
    private val api: PideckApi,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val deviceId: String = savedStateHandle["deviceId"] ?: error("缺少 deviceId")
    /** 侧栏要高亮当前会话，所以对外可见。 */
    val sessionId: String = savedStateHandle["sessionId"] ?: error("缺少 sessionId")

    /** 首页「选工作区 → 直接说事」的交接：路由参数带过来的第一句话，加载完历史立刻发。 */
    private val pendingDraft: String? = savedStateHandle.get<String>("draft")?.takeIf { it.isNotBlank() }

    private var device: Device? = null
    private var token: String? = null
    private var before: Long? = null
    private var sendJob: Job? = null
    private var merger = ReplyMerger()

    /**
     * 「别处发起」的回复订阅。
     *
     * `/api/chat` 的 SSE 是 POST 的一次性响应，只覆盖本端发送。桌面端（或另一台设备）
     * 发起的回复走 `GET /api/sessions/:id/stream`（服务端支持同会话多订阅者，并在订阅时
     * 回放本轮已产出的帧）。两条流互斥：本端发送期间忽略订阅帧，避免同一轮渲染两遍。
     */
    private var watchJob: Job? = null
    private var firstResume = true
    private var watchMerger = ReplyMerger()

    var title by mutableStateOf("会话")
        private set

    var items by mutableStateOf<List<TimelineItem>>(emptyList())
        private set

    var live by mutableStateOf<ReplySnapshot?>(null)
        private set

    var loading by mutableStateOf(true)
        private set

    var loadingOlder by mutableStateOf(false)
        private set

    var hasOlder by mutableStateOf(false)
        private set

    // ---- 一轮回复的节奏（首字时间 / tok/s）----
    //
    // 桌面端这组数是主进程在流式过程中现测的（`ttftMs` / `totalMs` / `tps` / `endToEndTps`），
    // 走 `agentsRuntimeState` 边沿推送，**不落在消息 meta 里**。手机上没法订阅那条通道，
    // 就由本端自己打点：按发送时刻 → 首个增量到达 → 收尾三段计时；tokens 数在落盘后
    // 从 `meta.usage.outputTokens` 取（流里没有 usage）。口径与桌面端一致，见 `data/TurnStats.kt`。

    /** 已结算的那一轮；null 表示没有可显示的数（acp / 没耗时字段的后端）。 */
    var turnStats by mutableStateOf<TurnStats?>(null)

    /** 已经算过统计的那条 assistant 消息（id），避免每次重拉历史都把秒表重算一遍。 */
    private var statsForMessageId: String? = null
    private var turnStartedAt = 0L
    private var turnFirstFrameAt = 0L
    private var turnFirstTextAt = 0L
    private var turnFirstAnyAt = 0L
    private var turnEndedAt = 0L

    var sending by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    /** 服务端说这个会话的消息拿不到（例如历史被清），直接显示原因。 */
    var unavailable by mutableStateOf<String?>(null)
        private set

    /** 上次用的投递方式，只当默认值。 */
    var busySendMode by mutableStateOf(BusySendMode.STEER)
        private set

    /**
     * 排队到下一轮、还没被 pi 取走的那些文本。
     *
     * 宿主不回传队列内容（桌面端走 Electron IPC 的 queued messages，HTTP 侧没有这个接口），
     * 所以这边只留自己发过的那几条，等它们出现在历史里就自动删掉 —— 对账而不是缓存。
     */
    var queuedTexts by mutableStateOf<List<String>>(emptyList())
        private set

    /** 并行问询面板的状态；null = 面板关着。 */
    var parallelAsk by mutableStateOf<ParallelAsk?>(null)
        private set

    /** 「插入输入框」交接：面板点插入就写这里，界面取走塞进草稿。 */
    var parallelInsert by mutableStateOf<String?>(null)
        private set

    private var askJob: Job? = null
    private var askTarget: RuntimeTarget? = null
    private var askMerger = ReplyMerger()

    init {
        restoreBusySendMode()
        viewModelScope.launch {
            val found = repository.devices.first { list -> list.any { it.id == deviceId } }
                .firstOrNull { it.id == deviceId }
            if (found != null) {
                device = found
                token = repository.tokenOf(found.id)
            }
            // 记下这一落脚点：下次冷启动直接回到这里，不用再从设备列表点进来
            runCatching { Prefs.rememberSession(context, deviceId, sessionId) }
            val cached = StateCache.session(deviceId, sessionId)
            title = cached?.title?.ifBlank { null } ?: "会话"
            sessionModel = cached?.model.toSessionModel()
            thinkingLevel = cached?.thinkingLevel
            backend = cached?.backend
            permissionPreset = cached?.permissionPreset
            StateCache.get(deviceId)?.security?.let { security = parseSecurityState(it) }
            // 从冷启动 / 历史跳进来时缓存里没有这条会话：模型与思考档位就都显示不出来。
            // 拉一次 /api/state 把记录补上（只在缺记录时才发这一趟）。
            if (cached == null && found != null) {
                runCatching { api.state(found, token, null) }
                    .onSuccess { result ->
                        if (result is StateFetch.Fresh) {
                            StateCache.put(deviceId, result.state)
                            adoptState(result.state)
                        }
                    }
            }
            // 旧版本把 DSH 的权限预设写进了 pi 会话记录，那一份会让主机在激活时 apply 一个
            // 它不认识的预设（用户反馈「调过权限就不能继续对话」）。看到就清掉，让会话自己恢复。
            clearStalePermissionPreset(cached)
            refreshHistory()
            startWatching()
            startStatePolling()
            pendingDraft?.let { prompt -> send(prompt) }
        }
    }

    fun dismissError() {
        error = null
    }

    /** 用会话记录补齐标题 / 模型 / 思考档位（只在拿得到真值时覆盖，别把用户刚选的冲掉）。 */
    private fun adoptSession(record: Session?) {
        if (record == null) return
        record.model.toSessionModel()?.let { sessionModel = it }
        record.thinkingLevel?.takeIf { it.isNotBlank() }?.let { thinkingLevel = it }
        record.backend?.takeIf { it.isNotBlank() }?.let { backend = it }
        val toolId = record.acpToolId
        if (!toolId.isNullOrBlank()) {
            acpTools.firstOrNull { it.id == toolId }?.name?.takeIf { it.isNotBlank() }?.let { acpToolName = it }
        }
        // 权限预设是 DSH 的东西，pi 会话上出现就是旧客户端留下的脏值（见 clearStalePermissionPreset）
        if (securityControl == PrivilegeControl.PermissionPreset) {
            record.permissionPreset?.takeIf { it.isNotBlank() }?.let { permissionPreset = it }
        }
        record.title?.takeIf { it.isNotBlank() }?.let { if (title == "会话") title = it }
        clearStalePermissionPreset(record)
    }

    /** 一次 `/api/state` 的结果：本会话的会话记录与安全等级投影都从这一份里对齐。 */
    private fun adoptState(state: PideckState) {
        state.security?.let { security = parseSecurityState(it) }
        if (state.acpTools.isNotEmpty()) acpTools = state.acpTools
        adoptSession(state.sessions.firstOrNull { it.id == sessionId })
    }

    /** `/api/state` 的 ACP 工具表：只为了把 `acpToolId` 翻成显示名。 */
    private var acpTools: List<dev.pideck.nativeapp.data.AcpTool> = emptyList()

    /**
     * 清掉旧版本写到 pi 会话上的 DSH 权限预设。
     *
     * 只在「后端确定不是 dsh」且有值时发一次；传 null 就是清空（服务端 `permissionPreset?: string | null`）。
     * 失败就算了：会话能不能继续以主机为准，这里只是帮它把脏值擦干。
     */
    private var stalePermissionCleared = false

    private fun clearStalePermissionPreset(record: Session?) {
        if (stalePermissionCleared) return
        if (record == null) return
        val target = device ?: return
        // 后端未知时别动手：DSH 会话的记录里本来就有预设，误清等于把用户的选择删了
        val known = backend?.takeIf { it.isNotBlank() } ?: return
        if (known.equals("dsh", ignoreCase = true)) return
        if (securityControl != PrivilegeControl.SecurityLevel) return
        if (record.permissionPreset.isNullOrBlank()) {
            stalePermissionCleared = true
            return
        }
        stalePermissionCleared = true
        permissionPreset = null
        viewModelScope.launch {
            runCatching {
                api.updateSession(
                    target,
                    token,
                    sessionId,
                    buildJsonObject { put("permissionPreset", kotlinx.serialization.json.JsonNull) },
                )
            }
        }
    }

    // ---- 运行时动作：权限预设 / 上下文用量 / 压缩 / 导出 HTML ----
    // 全都需要 target（agentId + runtimeGeneration）；runtime 没起来时这几项没法做，
    // 所以每次用之前都从 StateCache 现取，不缓存（target 会随会话重启失效）。

    var contextUsage by mutableStateOf<ContextUsage?>(null)
        private set

    var contextBusy by mutableStateOf(false)
        private set

    var compactBusy by mutableStateOf(false)
        private set

    var permissionPreset by mutableStateOf<String?>(null)
        private set

    var presetBusy by mutableStateOf(false)
        private set

    /**
     * 本会话的后端（pi / dsh / acp …）；草稿会话与旧缓存里可能没有，null 按 pi 处理。
     *
     * 权限位摆哪个控件全靠它（见 [PrivilegeControl]）：pi 是安全等级，dsh 是权限预设。
     */
    var backend by mutableStateOf<String?>(null)
        private set

    val securityControl: PrivilegeControl get() = privilegeControlFor(backend)

    /**
     * 记录里没有 `backend` 的（pi 草稿、老缓存）按 pi 处理 —— 模型与思考档位只对 pi 有意义：
     * 宿主对 acp 会话根本不落这两项（`createSessionDraft` 里 `acp ? void 0 : …`），
     * DSH 用自己的模型体系，摆一个不生效的选择器比不摆更误导。
     */
    val isPiBackend: Boolean get() = securityControl == PrivilegeControl.SecurityLevel

    /** 顶栏上的后端标记：pi 不显示（默认后端不值得占位置），其余给一个人能读的名字。 */
    val backendLabel: String?
        get() = if (isPiBackend) null else dev.pideck.nativeapp.data.AgentBackend.badgeOf(backend, acpToolName)

    /** ACP 工具显示名（`/api/state` 的 `acpTools` 里按 `acpToolId` 查）。 */
    var acpToolName by mutableStateOf<String?>(null)
        private set

    /** pi 会话的安全等级投影（`/api/state` 的 `security`）；没拿到就是 null。 */
    var security by mutableStateOf<SecurityState?>(null)
        private set

    var securityBusy by mutableStateOf(false)
        private set

    /** 本会话当前生效的等级 id（会话覆盖 > 全局默认）。 */
    val securityLevelId: String? get() = security?.effectiveId(sessionId)

    val securityOverrideId: String? get() = security?.overrideOf(sessionId)

    /** 头部/菜单上那一行文字：等级名 + （覆盖时）「仅本会话」。 */
    val securityLevelLabel: String
        get() {
            val state = security ?: return "主机未提供"
            val id = state.effectiveId(sessionId)
            val name = state.labelOf(id)
            return if (state.overrideOf(sessionId) != null) "$name（仅本会话）" else name
        }

    /**
     * 重读安全等级投影（`/api/state`）。
     *
     * 只为了 `security` 这一块：点开安全等级面板时现拉一次，不必等到 5s 轮询。
     */
    fun refreshSecurity() {
        val target = device ?: return
        viewModelScope.launch {
            runCatching { api.state(target, token, null) }
                .onSuccess { result ->
                    if (result is StateFetch.Fresh) {
                        StateCache.put(deviceId, result.state)
                        adoptState(result.state)
                    }
                }
                .onFailure { error = describeConnectionError(it) }
        }
    }

    /**
     * 切 pi 会话的安全等级；`levelId = null` 清除会话覆盖、跟随全局默认。
     *
     * 不本地猜结果：服务端会校验后端与等级，返回最新投影；失败就停在真实等级上。
     */
    fun chooseSecurityLevel(levelId: String?) {
        val target = device ?: return
        if (securityControl != PrivilegeControl.SecurityLevel) return
        viewModelScope.launch {
            securityBusy = true
            try {
                val fresh = api.setSessionSecurityLevel(target, token, sessionId, levelId)
                security = fresh
                actionNote = if (levelId == null) {
                    "安全等级已跟随全局默认（${fresh.labelOf(fresh.defaultLevelId)}）"
                } else {
                    "安全等级已切到「${fresh.labelOf(levelId)}」"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = describeConnectionError(e)
            } finally {
                securityBusy = false
            }
        }
    }

    var exportBusy by mutableStateOf(false)
        private set

    /** 一次性提示（已导出到哪里 / 压缩已请求），UI 弹完调 [dismissActionNote]。 */
    var actionNote by mutableStateOf<String?>(null)
        private set

    private fun runtimeTarget(): RuntimeTarget? {
        val runtime = StateCache.runtime(deviceId, sessionId) ?: return null
        return RuntimeTarget(sessionId, runtime.agentId, runtime.runtimeGeneration.toLong())
    }

    /** 已经为哪一代运行时拉过真值（`agentId#generation`）。失败也记，避免 5s 轮询里反复打。 */
    private var livePrefsKey: String? = null

    /**
     * 会话记录里没有模型 / 思考（手机新建的会话都是这样）时，从 `runtime/state` 把 pi
     * 实际在用的那一份取回来当**展示**值。运行时没起来就直接返回 —— 没有真值就不编。
     *
     * 每代运行时只拉一次：模型/档位在运行时生命周期内不会自己变（用户改的话记录也会跟着变）。
     */
    private fun syncLivePrefs() {
        if (sessionModel != null && thinkingLevel != null) return
        val host = device ?: return
        val runtime = StateCache.runtime(deviceId, sessionId) ?: return
        val key = "${runtime.agentId}#${runtime.runtimeGeneration}"
        if (key == livePrefsKey) return
        livePrefsKey = key
        viewModelScope.launch {
            runCatching {
                api.runtimeState(
                    host,
                    token,
                    RuntimeTarget(sessionId, runtime.agentId, runtime.runtimeGeneration.toLong()),
                )
            }.onSuccess { snap ->
                // 只补空位：用户刚选的值（sessionModel / thinkingLevel）不被运行时旧值冲掉
                if (sessionModel == null) snap.model?.let { liveModel = it }
                if (thinkingLevel == null) snap.thinkingLevel?.takeIf { it.isNotBlank() }?.let { liveThinking = it }
            }
        }
    }

    fun dismissActionNote() {
        actionNote = null
    }

    /** 拉上下文用量（也顺便对账权限预设：运行时的值比会话记录新）。 */
    fun refreshContext() {
        val target = device ?: return
        viewModelScope.launch {
            contextBusy = true
            try {
                // 缓存里没有 runtime（刚打开会话、轮询还没到）就现拉一次 /api/state，
                // 否则这一屏永远显示「读取不到」。
                var runtime = runtimeTarget()
                if (runtime == null) {
                    runCatching { api.state(target, token, null) }
                        .getOrNull()
                        ?.let { fresh ->
                            if (fresh is StateFetch.Fresh) {
                                StateCache.put(deviceId, fresh.state)
                                SessionActivity.observe(fresh.state)
                            }
                        }
                    runtime = runtimeTarget()
                }
                if (runtime == null) {
                    contextUsage = null
                    actionNote = "这个会话的运行时还没起来 —— 先发一句话再来读上下文"
                    return@launch
                }
                val snap = api.runtimeState(target, token, runtime)
                contextUsage = snap.usage
                // 顺手把运行时真值记下来（同一份回包，不额外发请求）
                livePrefsKey = "${runtime.agentId}#${runtime.runtimeGeneration}"
                if (sessionModel == null) snap.model?.let { liveModel = it }
                if (thinkingLevel == null) snap.thinkingLevel?.takeIf { it.isNotBlank() }?.let { liveThinking = it }
                // runtime 的 permissionPreset 只属于 DSH；pi 会话读它等于把没用的字段摊到界面上
                if (securityControl == PrivilegeControl.PermissionPreset) {
                    snap.usage.permissionPreset?.takeIf { it.isNotBlank() }?.let { permissionPreset = it }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 用量只是辅助信息，不打断阅读；但要说清楚为什么是空的
                contextUsage = null
                actionNote = "读取上下文用量失败：${describeConnectionError(e)}"
            } finally {
                contextBusy = false
            }
        }
    }

    /**
     * 切换 DSH 权限预设（read-only / workspace-write / danger-full-access）。
     *
     * **只给 dsh 会话用**：pi 会话的权限位是安全等级（[chooseSecurityLevel]），把预设写到
     * pi 记录上会让主机下次激活拿它不认识的预�去 apply，直接卡死后续对话。
     */
    fun choosePermission(preset: String) {
        val target = device ?: return
        if (securityControl != PrivilegeControl.PermissionPreset) return
        viewModelScope.launch {
            presetBusy = true
            try {
                val runtime = runtimeTarget()
                val viaRuntime = if (runtime == null) false else runCatching {
                    api.runtimeCommand(target, token, runtime, "permission", buildJsonObject { put("preset", preset) })
                }.isSuccess
                // 落一条记录上的偏好：下次激活时宿主按它 apply（pi 后端只走这条路）
                runCatching {
                    api.updateSession(target, token, sessionId, buildJsonObject { put("permissionPreset", preset) })
                }
                permissionPreset = preset
                actionNote = if (viaRuntime) {
                    "权限已切到「${PermissionPresets.labelOf(preset)}」"
                } else {
                    "已记下「${PermissionPresets.labelOf(preset)}」，下一轮生效"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = describeConnectionError(e)
            } finally {
                presetBusy = false
            }
        }
    }

    /** 压缩上下文（`runtime/compact`，可带一句「保留什么」的提示）。 */
    fun compact(prompt: String? = null) {
        val target = device ?: return
        val runtime = runtimeTarget()
        if (runtime == null) {
            error = "运行时还没起来，等这一轮跑完再压缩"
            return
        }
        viewModelScope.launch {
            compactBusy = true
            try {
                val extra = if (prompt.isNullOrBlank()) {
                    JsonObject(emptyMap())
                } else {
                    buildJsonObject { put("prompt", prompt.trim()) }
                }
                api.runtimeCommand(target, token, runtime, "compact", extra)
                actionNote = "已请求压缩，历史里会多出一条压缩记录"
                delay(600)
                refreshHistory()
                refreshContext()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = describeConnectionError(e)
            } finally {
                compactBusy = false
            }
        }
    }

    /** 导出整份会话 HTML 到「下载/PiDeck」。 */
    fun exportHtml() {
        val target = device ?: return
        viewModelScope.launch {
            exportBusy = true
            try {
                val html = api.exportHtml(target, token, sessionId)
                if (!htmlLooksValid(html)) throw PideckException("导出失败：服务端没有返回 HTML")
                val saved = withContext(Dispatchers.IO) {
                    FileSaver.saveText(
                        context,
                        exportFileName(sessionId, title),
                        "text/html",
                        html,
                    )
                }
                actionNote = "已导出到 $saved"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = describeConnectionError(e)
            } finally {
                exportBusy = false
            }
        }
    }

    /** 是否有正在往外冒的回复（本端发送 or 别处发起的订阅）。UI 用它决定停止按钮与光标。 */
    val streaming: Boolean
        get() = sending || live?.finished == false

    // ---- 模型 / 思考档位 ----
    // 两个都只是会话记录上的偏好字段（`POST /api/sessions/:id/update`），改完由主机在
    // 下一次激活时 applyPreferences；所以这里不做本地猜测，改完重读 /api/state 对账。

    var sessionModel by mutableStateOf<SessionModel?>(null)
        private set

    var thinkingLevel by mutableStateOf<String?>(null)
        private set

    /**
     * 运行时**真实生效**的模型 / 思考档位。
     *
     * 宿主只在 Electron 的 createDraft 里套用启动默认值（`resolveLaunchDefaultOptions`），
     * web 端（手机）新建的会话记录里 model / thinkingLevel 是空的 —— 光看记录就是「选择模型 /
     * 不思考」。运行时起来后 `runtime/state` 会报出 pi 实际用的那一个，这里存下来当**展示**来源：
     * 只改显示，不回写会话记录（写回去等于把手选的默认变成硬绑定，宿主以后改默认就不跟着走了）。
     */
    var liveModel by mutableStateOf<SessionModel?>(null)
        private set

    var liveThinking by mutableStateOf<String?>(null)
        private set

    /** 顶栏展示用的模型：会话记录优先（用户显式选过），否则用运行时真值。 */
    val displayModel: SessionModel?
        get() = sessionModel ?: liveModel

    /** 顶栏展示用的思考档位：同上。两个都为空就是「跟随电脑端默认」。 */
    val displayThinking: String?
        get() = thinkingLevel ?: liveThinking

    var availableModels by mutableStateOf<List<AvailableModel>>(emptyList())
        private set

    var modelsBusy by mutableStateOf(false)
        private set

    var prefError by mutableStateOf<String?>(null)
        private set

    // ---- AI 提问卡（ask_question / confirm / input） ----
    // 状态来源是 /api/state 的 pendingUiRequests；答案回 POST /api/ui-response。
    // 不靠 SSE 推：会话流只带回复帧，提问卡是本轮停在「等用户回答」时的一等状态。

    var pendingAsk by mutableStateOf<PendingUiRequest?>(null)
        private set

    var askBusy by mutableStateOf(false)
        private set

    var askError by mutableStateOf<String?>(null)
        private set

    private var stateEtag: String? = null
    private var statePollJob: Job? = null

    fun dismissAskError() {
        askError = null
    }

    /** 轮询 /api/state（带 ETag，没变就是 304，几乎不耗流量）只为了拿提问卡的最新状态。 */
    private fun startStatePolling() {
        if (statePollJob?.isActive == true) return
        statePollJob = viewModelScope.launch {
            while (true) {
                val target = device
                if (target != null) {
                    runCatching { api.state(target, token, stateEtag) }
                        .onSuccess { result ->
                            if (result is StateFetch.Fresh) {
                                stateEtag = result.etag
                                StateCache.put(deviceId, result.state)
                                SessionActivity.observe(result.state)
                                val next = result.state.pendingUiRequests
                                    .firstOrNull { it.sessionId == sessionId }
                                if (next?.requestId != pendingAsk?.requestId) askError = null
                                pendingAsk = next
                                adoptState(result.state)
                                // 运行时刚起来时把 pi 真实生效的模型/档位补上（记录里通常没有）
                                syncLivePrefs()
                            }
                        }
                }
                delay(5000)
            }
        }
    }

    /** 回答提问卡：`response` 由 [AskUi.response] / [AskUi.serializeBatch] 组装。 */
    fun respondAsk(response: JsonObject) {
        val request = pendingAsk ?: return
        val target = device ?: return
        if (askBusy) return
        askBusy = true
        askError = null
        viewModelScope.launch {
            try {
                api.respondUi(target, token, AskUi.body(request, response))
                // 不本地猜结果：下一轮轮询会把 pendingUiRequests 换成服务端真值
                pendingAsk = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                askError = dev.pideck.nativeapp.data.describeConnectionError(e)
            } finally {
                askBusy = false
            }
        }
    }

    // ---- 子代理（工作区「子任务」页） ----

    var subagents by mutableStateOf<List<SubagentEntry>>(emptyList())
        private set

    /** 当前模型支持的思考档位（模型清单还没拉到就是空 —— 选择器据此只列能用的）。 */
    /**
     * 可选的思考档位。
     *
     * 桌面端的口径是三层兜底（`sessionPickerOptions.ts:resolveThinkingPickerLevels`）：
     * 模型表里的 `thinkingLevels` → 运行时 capability 快照 → 完整档位表。这里照抄最后那层，
     * 否则「会话记录的 modelId 跟 /api/models 里的 id 对不上」时（比如 `group/auto-x` vs `auto/x`）
     * 列表就空了，顶栏那颗思考按钮整颗消失 —— 用户看到的就是「要点一下才出来」。
     */
    val thinkingLevels: List<String>
        get() {
            val entry = modelEntry()
            return entry?.thinkingLevels?.takeIf { it.isNotEmpty() } ?: STANDARD_THINKING_LEVELS
        }

    /** 当前模型在 /api/models 里的条目：先精确对，再按 id 尾部宽松对。 */
    private fun modelEntry(): AvailableModel? {
        // 记录里没选过时用运行时真值：手机新建的会话只有这一份，档位表得按它查
        val current = displayModel ?: return null
        availableModels.firstOrNull {
            it.provider == current.provider && it.id == current.modelId
        }?.let { return it }
        val wanted = normalizeModelId(current.modelId)
        if (wanted.length < 6) return null
        return availableModels.firstOrNull {
            it.provider == current.provider && normalizeModelId(it.id).endsWith(wanted)
        }
    }

    private fun normalizeModelId(raw: String): String =
        raw.substringAfterLast('/').lowercase().filter { it.isLetterOrDigit() }

    fun dismissPrefError() {
        prefError = null
    }

    fun loadModels(force: Boolean = false) {
        val target = device ?: return
        if (modelsBusy) return
        viewModelScope.launch {
            modelsBusy = true
            try {
                availableModels = api.models(target, token, force)
            } catch (e: Exception) {
                prefError = describeConnectionError(e)
            } finally {
                modelsBusy = false
            }
        }
    }

    fun applyModel(model: SessionModel) {
        patchSession(
            buildJsonObject {
                put(
                    "model",
                    buildJsonObject {
                        put("provider", model.provider)
                        put("modelId", model.modelId)
                        put("modelName", model.modelName)
                    },
                )
            },
        ) { sessionModel = model }
    }

    fun applyThinking(level: String) {
        patchSession(buildJsonObject { put("thinkingLevel", level) }) { thinkingLevel = level }
    }

    /** 改偏好后把记录重拉一遍：列表页与侧栏读的是同一份缓存，不能只改聊天页的内存。 */
    private fun patchSession(patch: JsonObject, onApplied: () -> Unit) {
        val target = device ?: return
        viewModelScope.launch {
            try {
                api.updateSession(target, token, sessionId, patch)
                onApplied()
                val fresh = runCatching { api.state(target, token, null) }.getOrNull()
                if (fresh is StateFetch.Fresh) StateCache.put(deviceId, fresh.state)
            } catch (e: Exception) {
                prefError = describeConnectionError(e)
            }
        }
    }

    // ---- 工作区（抽屉里的三页） ----
    // 三份数据都是「点开抽屉才拉」，别处跑完一轮回到前台时顺带刷一下。

    var workspaceTodo by mutableStateOf<WorkspaceTodo?>(null)
        private set

    var workspaceChanges by mutableStateOf<WorkspaceChanges?>(null)
        private set

    var workspaceFiles by mutableStateOf<List<FileNode>>(emptyList())
        private set

    /** 文件页当前所在目录（项目内相对路径，"" 是根）。 */
    var workspaceDir by mutableStateOf("")
        private set

    var workspaceBusy by mutableStateOf(false)
        private set

    var workspaceError by mutableStateOf<String?>(null)
        private set

    private var workspaceLoaded = false

    /** 抽屉打开时调用；同一会话只自动拉一次，之后靠下拉/切页手动刷。 */
    fun loadWorkspace(force: Boolean = false) {
        if (workspaceLoaded && !force) return
        workspaceLoaded = true
        refreshWorkspace()
    }

    fun refreshWorkspace() {
        val device = device ?: return
        val projectId = projectIdOf() ?: run {
            workspaceError = "这个会话没有关联项目，看不到改动与文件"
            return
        }
        workspaceBusy = true
        workspaceError = null
        viewModelScope.launch {
            try {
                val session = StateCache.session(deviceId, sessionId)
                val todo = runCatching { api.todo(device, token, sessionId) }.getOrNull()
                val changes = runCatching {
                    api.changes(device, token, projectId, session?.projectPath)
                }.getOrNull()
                val files = runCatching {
                    api.files(device, token, projectId, workspaceDir)
                }.getOrDefault(emptyList())
                val agents = runCatching { api.subagents(device, token, sessionId) }
                    .getOrDefault(emptyList())
                workspaceTodo = todo
                workspaceChanges = changes
                workspaceFiles = files
                subagents = agents
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                workspaceError = dev.pideck.nativeapp.data.describeConnectionError(e)
            } finally {
                workspaceBusy = false
            }
        }
    }

    fun openDir(dir: String) {
        workspaceDir = dir
        refreshWorkspace()
    }

    // ---- `@` 文件候选 ----
    // 候选只用当前目录这一层：深目录全量扫太贵，而用户打 `@` 时通常只想引当前层或已知路径。
    // 拿不到时输入框不弹候选，不报错。

    var mentionFiles by mutableStateOf<List<FileNode>>(emptyList())
        private set

    /** 正在扫工作区（候选条显示「正在扫…」）。 */
    var mentionIndexing by mutableStateOf(false)
        private set

    private var indexJob: Job? = null

    /**
     * 扫一遍工作区建 `@` 索引。
     *
     * `/api/files` 只列一层，所以这里按目录广度优先走一遍（上限 [FileIndex.MAX_DIRS] 个目录 /
     * [FileIndex.MAX_NODES] 个节点，跳过 `.git`、`node_modules`、`build` 这类噪音目录），
     * 边扫边更新候选，用户打 `@` 立刻就有东西可选，不用等全扫完。
     */
    fun buildMentionIndex() {
        if (indexJob?.isActive == true) return
        val target = device ?: return
        val projectId = projectIdOf() ?: return
        indexJob = viewModelScope.launch {
            mentionIndexing = true
            val seen = LinkedHashSet<String>()
            val nodes = mutableListOf<FileNode>()
            val queue = ArrayDeque<String>()
            queue.add(workspaceDir)
            var dirs = 0
            try {
                while (queue.isNotEmpty() && dirs < FileIndex.MAX_DIRS && nodes.size < FileIndex.MAX_NODES) {
                    val dir = queue.removeFirst()
                    dirs++
                    val batch = runCatching { api.files(target, token, projectId, dir) }
                        .getOrDefault(emptyList())
                    for (node in batch) {
                        if (seen.add(node.path)) nodes.add(node)
                        if (node.isDir && !FileIndex.shouldSkipDir(node.name)) queue.add(node.path)
                    }
                    mentionFiles = nodes.toList()
                }
            } finally {
                mentionIndexing = false
            }
        }
    }

    // ---- 斜杠命令（`runtime/commands`：扩展 / 提示词模板 / 技能） ----

    var hostCommands by mutableStateOf<List<HostCommand>>(emptyList())
        private set

    private var commandsRequested = false

    fun ensureCommands() {
        if (commandsRequested) return
        val target = device ?: return
        val runtime = runtimeTarget() ?: return
        commandsRequested = true
        viewModelScope.launch {
            runCatching {
                parseHostCommands(PideckJson.parseToJsonElement(
                    api.runtimeCommand(target, token, runtime, "commands"),
                ))
            }.onSuccess { list ->
                hostCommands = list
            }.onFailure {
                // 运行时不可用（草稿会话等）：允许下次再试，列表里只剩客户端接管的两条
                commandsRequested = false
            }
        }
    }

    fun openParentDir() {
        openDir(workspaceDir.substringBeforeLast('/', ""))
    }

    // ---- 文件预览（点开一个文件看正文，只读） ----

    var previewPath by mutableStateOf<String?>(null)
        private set

    var previewText by mutableStateOf("")
        private set

    var previewBusy by mutableStateOf(false)
        private set

    fun openFile(path: String) {
        val device = device ?: return
        val projectId = projectIdOf() ?: return
        previewPath = path
        previewText = ""
        previewBusy = true
        viewModelScope.launch {
            try {
                previewText = api.fileText(device, token, projectId, path, sessionId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                previewText = dev.pideck.nativeapp.data.describeConnectionError(e)
            } finally {
                previewBusy = false
            }
        }
    }

    fun closePreview() {
        previewPath = null
        previewText = ""
    }

    // ---- 改动 diff（点「改动」里的文件看具体改了哪几行） ----

    var diffPath by mutableStateOf<String?>(null)
        private set

    var diffText by mutableStateOf("")
        private set

    var diffBusy by mutableStateOf(false)
        private set

    var diffGroup by mutableStateOf("workingTree")
        private set

    fun openDiff(file: ChangedFile) {
        val device = device ?: return
        val projectId = projectIdOf() ?: return
        diffPath = file.path
        diffGroup = file.group
        diffText = ""
        diffBusy = true
        viewModelScope.launch {
            try {
                val payload = api.fileDiff(device, token, projectId, file.group, file.path)
                diffText = if (payload == null) {
                    ""
                } else {
                    LineDiff.render(payload.original, payload.modified)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                diffText = dev.pideck.nativeapp.data.describeConnectionError(e)
            } finally {
                diffBusy = false
            }
        }
    }

    fun closeDiff() {
        diffPath = null
        diffText = ""
    }

    private fun projectIdOf(): String? =
        StateCache.session(deviceId, sessionId)?.projectId
            ?: StateCache.get(deviceId)?.sessions?.firstOrNull { it.id == sessionId }?.projectId

    /**
     * 回到前台：重拉历史 + 重开订阅。
     *
     * 后台时系统可能把 SSE 掐掉而 OkHttp 那边还挂着「已连接」，光等它自己发现要很久；
     * 直接重开一次最省事（服务端会在订阅时回放本轮已产出的帧）。
     */
    fun onForeground() {
        if (firstResume) {
            firstResume = false
            return
        }
        refreshHistory()
        watchJob?.cancel()
        watchJob = null
        startWatching()
        startStatePolling()
    }

    /** 常驻订阅会话流；断线退避重连，一轮结束后不退订（对齐服务端 keepAlive）。 */
    private fun startWatching() {
        val target = device ?: return
        if (watchJob != null) return
        watchJob = viewModelScope.launch {
            var backoff = 0L
            while (true) {
                try {
                    api.sessionStream(target, token, sessionId).collect { frame ->
                        if (sending) return@collect
                        val snapshot = applyWatchFrame(frame.data) ?: return@collect
                        live = snapshot
                        if (snapshot.finished) {
                            snapshot.errorText?.takeIf { it.isNotBlank() }?.let {
                                SessionActivity.markTurnResult(sessionId, failed = true)
                            }
                            live = null
                            // 收尾帧比落盘早一点点，等一下再以磁盘为准对齐。
                            delay(400)
                            refreshHistory()
                        }
                    }
                    backoff = 0L
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // 断线：吞掉错误，下面退避重连。连不上时 keepAlive 会一直断，
                    // 退避从 1.5s 起、翻倍到 15s 封顶，避免后台烧电。
                }
                delay(if (backoff == 0L) 1500L else backoff)
                backoff = if (backoff == 0L) 3000L else (backoff * 2).coerceAtMost(15_000L)
            }
        }
    }

    /** 订阅帧 → 快照。`start` 表示新一轮（服务端会重放帧），合并器从零重建。 */
    private fun applyWatchFrame(data: String): ReplySnapshot? {
        if (data.trim() == "[DONE]") return watchMerger.finish()
        val parsed = runCatching {
            PideckJson.parseToJsonElement(data) as? JsonObject
        }.getOrNull() ?: return null
        val type = parsed["type"]?.toString()?.trim('"')
        if (type == "start") {
            watchMerger.reset()
            live = null
        }
        return watchMerger.applyFrame(parsed)
    }

    /** 拉最新一页并替换列表（进入页面 / 发送收尾 / 下拉刷新都走这里）。 */
    /** 这一轮是成功还是失败：宿主只在消息里给 errorText，就用它判定侧栏颜色。 */
    private fun markLastTurnResult() {
        val last = items.lastOrNull { it is TimelineItem.AssistantMessage } as? TimelineItem.AssistantMessage
            ?: return
        SessionActivity.markTurnResult(sessionId, failed = !last.errorText.isNullOrBlank())
    }

    // ---- 统计行的打点与结算（首字时间 / tok/s，口径见 data/TurnStats.kt）----

    private fun resetTurnClock(now: Long = System.currentTimeMillis()) {
        turnStartedAt = now
        turnFirstFrameAt = 0L
        turnFirstTextAt = 0L
        turnFirstAnyAt = 0L
        turnEndedAt = 0L
        turnStats = null
    }

    /**
     * `/api/chat` 的 SSE 首帧到达 —— 首字的计时起点。
     *
     * 按下发送到首帧之间是「手机 → 宿主的建连与排队」，不是模型在想；桌面端从宿主收到
     * 请求就开始算，手机若也从按下算，同一条回复会凭空多出这段（实测能差 20s+），
     * 两端口径就对不上了。
     */
    private fun markTurnFirstFrame(now: Long = System.currentTimeMillis()) {
        if (turnStartedAt <= 0L || turnFirstFrameAt > 0L) return
        turnFirstFrameAt = now
    }

    /**
     * 首个增量到达。正文优先，思考阶段只有 reasoning 时先记下作兜底
     * （对应桌面端 `firstTextAt > 0 ? firstTextAt : firstDeltaAt`）。
     */
    private fun markTurnFirstContent(snapshot: ReplySnapshot) {
        if (turnStartedAt <= 0L) return
        val now = System.currentTimeMillis()
        when {
            snapshot.text.isNotEmpty() && turnFirstTextAt == 0L -> turnFirstTextAt = now
            snapshot.reasoning.isNotEmpty() && turnFirstAnyAt == 0L -> turnFirstAnyAt = now
            else -> return
        }
        val first = if (turnFirstTextAt > 0L) turnFirstTextAt else turnFirstAnyAt
        val base = if (turnFirstFrameAt > 0L) turnFirstFrameAt else turnStartedAt
        // 流式期间先上「首字 x.xs · 生成中…」，tok/s 要等落盘后拿到 usage 才算得出来。
        turnStats = TurnStats(ttftMs = (first - base).coerceAtLeast(0L))
    }

    /**
     * 收尾后结算统计行。
     *
     * 本机发的回合用秒表（任何后端都有）；翻历史 / 换设备看的回合只能靠 meta 里的
     * `durationMs`（只有 pi 后端有）算端到端速度，首字无从得知。
     */
    private fun settleTurnStats(messages: List<ChatMessage>) {
        val last = messages.lastOrNull { it.role == "assistant" } ?: return
        if (last.id == statsForMessageId) return
        val clock = if (turnStartedAt > 0L && turnEndedAt > 0L) {
            TurnClock(
                startedAt = turnStartedAt,
                firstFrameAt = turnFirstFrameAt,
                firstContentAt = if (turnFirstTextAt > 0L) turnFirstTextAt else turnFirstAnyAt,
                endedAt = turnEndedAt,
            )
        } else {
            null
        }
        statsForMessageId = last.id
        turnStats = turnStatsFromMeta(last.meta, clock).takeIf { !it.isEmpty }
        // 秒表只能用一次：下次重拉历史不能拿旧起点再算一遍。
        turnStartedAt = 0L
        turnFirstFrameAt = 0L
        turnFirstTextAt = 0L
        turnFirstAnyAt = 0L
        turnEndedAt = 0L
    }

    fun refreshHistory() {
        val target = device ?: return
        viewModelScope.launch {
            loading = items.isEmpty()
            error = null
            try {
                val page = api.messagesPage(target, token, sessionId, pageSize = 40)
                unavailable = page.unavailable
                items = page.messages.toTimelineItems()
                markLastTurnResult()
                settleTurnStats(page.messages)
                reconcileQueue()
                before = page.nextBefore
                hasOlder = page.nextBefore != null && page.messages.isNotEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = describeConnectionError(e)
            } finally {
                loading = false
            }
        }
    }

    /** 往前翻一页（列表顶部触发）。 */
    fun loadOlder() {
        val target = device ?: return
        val cursor = before ?: return
        if (loadingOlder) return
        viewModelScope.launch {
            loadingOlder = true
            try {
                val page = api.messagesPage(target, token, sessionId, pageSize = 40, before = cursor)
                val older = page.messages.toTimelineItems()
                items = older + items
                before = page.nextBefore
                hasOlder = page.nextBefore != null && older.isNotEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = describeConnectionError(e)
            } finally {
                loadingOlder = false
            }
        }
    }

    // ---- 图片附件 ----
    // 只活到「发出去」为止：服务端的 /api/chat 只从消息 parts 里取图，历史接口不回传图片，
    // 所以重拉历史之后这张图就只剩文字了（协议面的既有限制，不是这里的取舍）。

    var images by mutableStateOf<List<PendingImage>>(emptyList())
        private set

    var imageError by mutableStateOf<String?>(null)
        private set

    fun addImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        imageError = null
        var added = images
        for (uri in uris) {
            if (added.size >= ImagePrep.MAX_COUNT) {
                imageError = "一条消息最多带 ${ImagePrep.MAX_COUNT} 张图"
                break
            }
            val prepared = ImagePrep.fromUri(context, uri)
            if (prepared == null) {
                imageError = "这张图片读不出来（或者压完还是太大）"
                continue
            }
            added = added + prepared
        }
        images = added
    }

    fun pasteImage() {
        imageError = null
        if (images.size >= ImagePrep.MAX_COUNT) {
            imageError = "一条消息最多带 ${ImagePrep.MAX_COUNT} 张图"
            return
        }
        val prepared = ImagePrep.fromClipboard(context)
        if (prepared == null) {
            imageError = "剪贴板里没有图片"
            return
        }
        images = images + prepared
    }

    fun removeImage(id: String) {
        images = images.filterNot { it.id == id }
    }

    fun dismissImageError() {
        imageError = null
    }

    fun send(text: String) {
        val target = device ?: return
        val body = text.trim()
        val attached = images
        if ((body.isEmpty() && attached.isEmpty()) || sending) return
        sending = true
        error = null
        images = emptyList()
        // 秒表从「按下发送」开始（含排队与建连），与桌面端「请求发出」的口径一致。
        resetTurnClock()
        // 前台起服务是稳的（后台启动会被系统拒），run 一结束服务自己退出。
        RunWatcher.start(context)
        merger = ReplyMerger()
        live = null
        // 用户消息先上屏：等磁盘落盘再显示会有半秒的「没反应」。
        val localKey = "local-${System.currentTimeMillis()}"
        // 图片直接带在气泡里（宿主回传的也是 base64，形状一致），不再用「（N 张图片）」占位。
        val localImages = attached.map { image ->
            MessageImage(
                mimeType = image.dataUrl.substringBefore(';').substringAfter("data:").ifBlank { "image/jpeg" },
                data = image.dataUrl.substringAfter("base64,"),
            )
        }
        val localText = body
        items = items + TimelineItem.UserMessage(
            key = localKey,
            text = localText,
            timestamp = System.currentTimeMillis(),
            images = localImages,
        )

        sendJob = viewModelScope.launch {
            val payload = ChatPayloads.chatBody(
                sessionId = sessionId,
                text = body,
                imageDataUrls = attached.map { it.dataUrl },
            )
            try {
                api.chat(target, token, payload).collect { frame ->
                    markTurnFirstFrame()
                    val snapshot = when {
                        frame.data.trim() == "[DONE]" -> merger.finish()
                        else -> {
                            val parsed = runCatching {
                                PideckJson.parseToJsonElement(frame.data) as? JsonObject
                            }.getOrNull()
                            if (parsed != null) merger.applyFrame(parsed) else merger.snapshot()
                        }
                    }
                    live = snapshot
                    if (snapshot != null) markTurnFirstContent(snapshot)
                    if (snapshot?.errorText != null) error = snapshot.errorText
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = describeConnectionError(e)
            } finally {
                sending = false
                live = null
                if (turnEndedAt == 0L) turnEndedAt = System.currentTimeMillis()
                refreshHistory()
                // 落盘比收尾帧略晚：第一次重拉可能还没这条 assistant 消息，
                // 补一次把统计行的 tokens 补上（已经结算过就什么都不做）。
                if (turnStartedAt > 0L) {
                    delay(600)
                    if (turnStartedAt > 0L) refreshHistory()
                }
            }
        }
    }

    /** 停止按钮：先让服务端停 runtime，本地流跟着结束（finally 里会重拉历史对齐）。 */
    fun stop() {
        val target = device ?: return
        val runtime = StateCache.runtime(deviceId, sessionId)
        viewModelScope.launch {
            try {
                if (runtime != null) {
                    api.stopRuntime(target, token, sessionId, runtime.agentId, runtime.runtimeGeneration.toLong())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = describeConnectionError(e)
            } finally {
                sendJob?.cancel()
                sendJob = null
            }
        }
    }

    // ---- 运行中发送：加入当前回合 / 排队到下一轮 / 并行发送 ----
    //
    // 桌面端这三档分在两个地方：「忙碌时发送」是设置项（`settings.busySendDelivery`，
    // steer / followUp），「并行发送」是另一个入口（并行问询：另开匿名会话去问，答案单独看）。
    // 手机上不设独立设置页，只记上次用哪一档，每次发还能临时换。

    // 运行中发送的状态（busySendMode / queuedTexts / parallelAsk / parallelInsert）
    // **故意声明在 init 之前**：init 里要读 Prefs 补默认档位，而 Kotlin 按文本顺序初始化属性，
    // 声明在 init 之后的话委托字段还是 null，赋值就是 NPE —— 表现是「会话页进不去」。
    // （0.5.22 踩过一次，见 AGENTS.md）

    /** Prefs 的镜像在 App 启动时就装好了，这里只是把上次的选择拿过来。 */
    private fun restoreBusySendMode() {
        busySendMode = Prefs.busySendMode
    }

    fun consumeParallelInsert(): String? {
        val value = parallelInsert ?: return null
        parallelInsert = null
        return value
    }

    /**
     * 会话还在跑时按 [mode] 发一条。
     *
     * [BusySendMode.STEER] 走原来的 `/api/chat` 流式通道（宿主在忙碌时默认就是 steer，
     * 这一轮回复照样在本端逐字渲染）；另外两档换别的通道，见各自实现。
     */
    /** 记住这一档：下次进会话还是它（只记偏好，不改会话记录）。 */
    fun rememberBusySendMode(mode: BusySendMode) {
        if (busySendMode == mode) return
        busySendMode = mode
        viewModelScope.launch { runCatching { Prefs.setBusySendMode(context, mode) } }
    }

    fun sendWhileBusy(text: String, mode: BusySendMode) {
        rememberBusySendMode(mode)
        if (!streaming) {
            send(text)
            return
        }
        if (images.isNotEmpty()) {
            // 运行中这三条通道都不吃图片（`prompt` 的 body 里就没有 images 字段），
            // 与其悄悄丢图，不如明说一句。
            actionNote = "运行中的消息不带图片，等这一轮结束再发"
            return
        }
        when (mode) {
            BusySendMode.STEER -> steerDuringRun(text)
            BusySendMode.FOLLOW_UP -> enqueueFollowUp(text)
            BusySendMode.PARALLEL -> startParallelAsk(text)
        }
    }

    /**
     * 排队到下一轮：`POST /api/sessions/:id/prompt` + `streamingBehavior: "followUp"`。
     *
     * 这条通道只投递、不返回回复流，所以本端不动 `sending` / `live`：等 pi 把消息取走、
     * 开始新一轮时，常驻的会话订阅（[startWatching]）会把它当成「别处发起的回合」渲染出来。
     */
    /**
     * 加入当前回合（steer）：`prompt` + `streamingBehavior: "steer"`。
     *
     * 不新开回复流 —— 这条消息是注入到**正在跑的**那一轮里，文本会顺着已有的流
     * （本端 `/api/chat` 的 sendJob，或后台起的会话订阅）继续过来，再开一条只会两份互相覆盖。
     */
    private fun steerDuringRun(text: String) {
        val target = device ?: return
        val body = text.trim()
        if (body.isEmpty()) return
        // 先上屏：等落盘再显示会有半秒「没反应」，重拉历史时这条本地气泡会被真实记录替掉
        items = items + TimelineItem.UserMessage(
            key = "local-${System.currentTimeMillis()}",
            text = body,
            timestamp = System.currentTimeMillis(),
        )
        viewModelScope.launch {
            try {
                val delivery = api.sendPrompt(
                    target,
                    token,
                    sessionId,
                    ChatPayloads.promptBody(newRequestId(), body, "steer"),
                )
                if (!delivery.accepted) error = delivery.error ?: "宿主没有接受这条消息"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = describeConnectionError(e)
            }
        }
    }

    private fun enqueueFollowUp(text: String) {
        val target = device ?: return
        val body = text.trim()
        if (body.isEmpty()) return
        viewModelScope.launch {
            try {
                val delivery = api.sendPrompt(
                    target,
                    token,
                    sessionId,
                    ChatPayloads.promptBody(newRequestId(), body, "followUp"),
                )
                if (!delivery.accepted) {
                    error = delivery.error ?: "宿主没有接受这条消息"
                    return@launch
                }
                queuedTexts = queuedTexts + body
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = describeConnectionError(e)
            }
        }
    }

    /**
     * 并行发送：开一个匿名会话（`noSession: true`，不落盘、不进项目列表）去问。
     *
     * 模型与思考档位由宿主按启动默认值填，客户端不用传；答案走该会话的回复流，
     * 面板里逐字显示，收尾后可以把它插回输入框。
     */
    private fun startParallelAsk(text: String) {
        val target = device ?: return
        val question = text.trim()
        if (question.isEmpty()) return
        val projectId = projectIdOf()
        if (projectId == null) {
            actionNote = "这个会话没有归属项目，没法另开匿名会话"
            return
        }
        askJob?.cancel()
        stopParallelRuntime()
        askMerger = ReplyMerger()
        parallelAsk = ParallelAsk(question = question, sessionId = "", busy = true)
        askJob = viewModelScope.launch {
            try {
                val anon = api.createAnonymousSession(target, token, projectId)
                parallelAsk = parallelAsk?.copy(sessionId = anon)
                val delivery = api.sendPrompt(
                    target,
                    token,
                    anon,
                    ChatPayloads.promptBody(newRequestId(), question),
                )
                askTarget = delivery.agentId?.let { agentId ->
                    RuntimeTarget(anon, agentId, delivery.runtimeGeneration ?: 0L)
                }
                if (!delivery.accepted) {
                    parallelAsk = parallelAsk?.copy(busy = false, error = delivery.error ?: "宿主没有接受这条问题")
                    return@launch
                }
                // 先投递后订阅也不丢帧：服务端在订阅时会回放本轮已产出的帧。
                try {
                    api.sessionStream(target, token, anon).collect { frame ->
                        val snapshot = when {
                            frame.data.trim() == "[DONE]" -> askMerger.finish()
                            else -> {
                                val parsed = runCatching {
                                    PideckJson.parseToJsonElement(frame.data) as? JsonObject
                                }.getOrNull()
                                if (parsed != null) askMerger.applyFrame(parsed) else askMerger.snapshot()
                            }
                        } ?: return@collect
                        parallelAsk = parallelAsk?.copy(
                            answer = snapshot.text,
                            busy = !snapshot.finished,
                            error = snapshot.errorText,
                        )
                        // 这一轮收尾就别再挂着 keepAlive 的订阅（服务端不会主动断）
                        if (snapshot.finished) throw AskStreamDone()
                    }
                } catch (e: AskStreamDone) {
                    // 正常收尾
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                parallelAsk = parallelAsk?.copy(busy = false, error = describeConnectionError(e))
            }
        }
    }

    /** 面板上「插入输入框」：把答案交出去，然后收掉匿名会话。 */
    fun insertParallelAnswer() {
        val ask = parallelAsk ?: return
        if (ask.answer.isBlank()) return
        parallelInsert = ask.answer.trim()
        closeParallelAsk()
    }

    /** 关面板：取消订阅并停掉匿名会话的 runtime（`noSession` 会话不会自己出现在项目列表里）。 */
    fun closeParallelAsk() {
        askJob?.cancel()
        askJob = null
        parallelAsk = null
        stopParallelRuntime()
    }

    private fun stopParallelRuntime() {
        val target = device
        val runtime = askTarget ?: return
        askTarget = null
        if (target == null) return
        viewModelScope.launch {
            runCatching {
                api.stopRuntime(target, token, runtime.sessionId, runtime.agentId, runtime.runtimeGeneration)
            }
        }
    }

    /** 排队的消息一旦出现在历史里就把提示撤掉（宿主那边已经没有「待发」这回事）。 */
    private fun reconcileQueue() {
        if (queuedTexts.isEmpty()) return
        val landed = items.filterIsInstance<TimelineItem.UserMessage>().map { it.text }.toSet()
        val rest = queuedTexts.filterNot { it in landed }
        if (rest.size != queuedTexts.size) queuedTexts = rest
    }

    override fun onCleared() {
        stopParallelRuntime()
        super.onCleared()
    }
}

/** 并行问询面板的状态。 */
data class ParallelAsk(
    val question: String,
    val sessionId: String,
    val answer: String = "",
    val busy: Boolean = true,
    val error: String? = null,
)

/** 匿名会话这一轮已经收尾：用它退出 keepAlive 的回复流订阅。 */
private class AskStreamDone : Exception()

private fun newRequestId(): String = java.util.UUID.randomUUID().toString()

/** pi 的标准档位表（桌面端在拿不到模型能力时也用这一份）。 */
private val STANDARD_THINKING_LEVELS =
    listOf("off", "minimal", "low", "medium", "high", "xhigh", "max")
