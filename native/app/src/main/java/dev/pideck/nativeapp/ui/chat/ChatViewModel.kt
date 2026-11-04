package dev.pideck.nativeapp.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pideck.nativeapp.data.Device
import dev.pideck.nativeapp.data.DeviceRepository
import dev.pideck.nativeapp.data.PideckApi
import dev.pideck.nativeapp.data.PideckJson
import dev.pideck.nativeapp.data.ReplyMerger
import dev.pideck.nativeapp.data.ReplySnapshot
import dev.pideck.nativeapp.data.StateCache
import dev.pideck.nativeapp.data.TimelineItem
import dev.pideck.nativeapp.data.describeConnectionError
import dev.pideck.nativeapp.data.toTimelineItem
import dev.pideck.nativeapp.data.toTimelineItems
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
) : ViewModel() {

    private val deviceId: String = savedStateHandle["deviceId"] ?: error("缺少 deviceId")
    private val sessionId: String = savedStateHandle["sessionId"] ?: error("缺少 sessionId")

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
            title = StateCache.session(deviceId, sessionId)?.title?.ifBlank { null } ?: "会话"
            refreshHistory()
            startWatching()
        }
    }

    fun dismissError() {
        error = null
    }

    /** 是否有正在往外冒的回复（本端发送 or 别处发起的订阅）。UI 用它决定停止按钮与光标。 */
    val streaming: Boolean
        get() = sending || live?.finished == false

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

    fun send(text: String) {
        val target = device ?: return
        val body = text.trim()
        if (body.isEmpty() || sending) return
        sending = true
        error = null
        merger = ReplyMerger()
        live = null
        // 用户消息先上屏：等磁盘落盘再显示会有半秒的「没反应」。
        val localKey = "local-${System.currentTimeMillis()}"
        items = items + TimelineItem.UserMessage(key = localKey, text = body, timestamp = System.currentTimeMillis())

        sendJob = viewModelScope.launch {
            val payload = buildJsonObject {
                put("id", sessionId)
                put(
                    "messages",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("role", "user")
                                put("content", body)
                            },
                        )
                    },
                )
            }
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
