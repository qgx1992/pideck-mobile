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
import dev.pideck.nativeapp.data.DeviceRepository
import dev.pideck.nativeapp.data.PideckApi
import dev.pideck.nativeapp.data.ImagePrep
import dev.pideck.nativeapp.data.PendingImage
import dev.pideck.nativeapp.data.ChatPayloads
import dev.pideck.nativeapp.data.PideckJson
import dev.pideck.nativeapp.data.Prefs
import dev.pideck.nativeapp.data.ReplyMerger
import dev.pideck.nativeapp.data.ReplySnapshot
import dev.pideck.nativeapp.data.StateCache
import dev.pideck.nativeapp.data.StateFetch
import dev.pideck.nativeapp.data.AvailableModel
import dev.pideck.nativeapp.data.SessionModel
import dev.pideck.nativeapp.data.toSessionModel
import dev.pideck.nativeapp.data.FileNode
import dev.pideck.nativeapp.data.TimelineItem
import dev.pideck.nativeapp.data.WorkspaceChanges
import dev.pideck.nativeapp.data.WorkspaceTodo
import dev.pideck.nativeapp.data.describeConnectionError
import dev.pideck.nativeapp.data.toTimelineItem
import dev.pideck.nativeapp.data.toTimelineItems
import dev.pideck.nativeapp.watch.RunWatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

    var sending by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    /** 服务端说这个会话的消息拿不到（例如历史被清），直接显示原因。 */
    var unavailable by mutableStateOf<String?>(null)
        private set

    init {
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
            refreshHistory()
            startWatching()
            pendingDraft?.let { prompt -> send(prompt) }
        }
    }

    fun dismissError() {
        error = null
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

    var availableModels by mutableStateOf<List<AvailableModel>>(emptyList())
        private set

    var modelsBusy by mutableStateOf(false)
        private set

    var prefError by mutableStateOf<String?>(null)
        private set

    /** 当前模型支持的思考档位（模型清单还没拉到就是空 —— 选择器据此只列能用的）。 */
    val thinkingLevels: List<String>
        get() {
            val current = sessionModel ?: return emptyList()
            return availableModels
                .firstOrNull { it.provider == current.provider && it.id == current.modelId }
                ?.thinkingLevels
                .orEmpty()
        }

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
                workspaceTodo = todo
                workspaceChanges = changes
                workspaceFiles = files
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
                previewText = api.fileText(device, token, projectId, path)
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
    fun refreshHistory() {
        val target = device ?: return
        viewModelScope.launch {
            loading = items.isEmpty()
            error = null
            try {
                val page = api.messagesPage(target, token, sessionId, pageSize = 40)
                unavailable = page.unavailable
                items = page.messages.toTimelineItems()
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
        // 前台起服务是稳的（后台启动会被系统拒），run 一结束服务自己退出。
        RunWatcher.start(context)
        merger = ReplyMerger()
        live = null
        // 用户消息先上屏：等磁盘落盘再显示会有半秒的「没反应」。
        val localKey = "local-${System.currentTimeMillis()}"
        // 本地气泡里补一句图片数：历史接口不回传图片，不然重拉之后这条消息看着像空的
        val localText = when {
            body.isNotEmpty() && attached.isEmpty() -> body
            body.isNotEmpty() -> body + "\n（" + attached.size + " 张图片）"
            else -> "（" + attached.size + " 张图片）"
        }
        items = items + TimelineItem.UserMessage(key = localKey, text = localText, timestamp = System.currentTimeMillis())

        sendJob = viewModelScope.launch {
            val payload = ChatPayloads.chatBody(
                sessionId = sessionId,
                text = body,
                imageDataUrls = attached.map { it.dataUrl },
            )
            try {
                api.chat(target, token, payload).collect { frame ->
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
                    if (snapshot?.errorText != null) error = snapshot.errorText
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = describeConnectionError(e)
            } finally {
                sending = false
                live = null
                refreshHistory()
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
}
