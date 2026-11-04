package dev.pideck.nativeapp.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 桌面端 WebService 的 Kotlin 客户端。
 *
 * 只做「拿到数据」这一层，不缓存、不合并：状态合并与缓存由上层 ViewModel 决定，
 * 这样 Ktor mock 与真机可以用同一份实现跑同一批断言。
 */
interface PideckApi {
    suspend fun state(device: Device, token: String?, etag: String? = null): StateFetch

    suspend fun messagesPage(
        device: Device,
        token: String?,
        sessionId: String,
        pageSize: Int = 30,
        before: Long? = null,
    ): SessionMessagePage

    /** 按会话订阅「别处发起」的回复流：UIMessageStream 帧。 */
    fun sessionStream(device: Device, token: String?, sessionId: String): Flow<SseFrame>

    /** `/api/chat`：发一条用户消息，响应本身也是一条 SSE 流。 */
    fun chat(device: Device, token: String?, body: JsonObject): Flow<SseFrame>

    /** `POST /api/sessions`：按项目新建草稿会话，返回新会话 id。 */
    suspend fun createSession(device: Device, token: String?, projectId: String): String

    /** `POST /api/sessions/:id/runtime/stop`：停掉正在跑的 runtime。 */
    suspend fun stopRuntime(device: Device, token: String?, sessionId: String, agentId: String, runtimeGeneration: Long)

    /** 改会话标题（侧栏长按 → 重命名）。运行中的会话也允许改，桌面端显示的是同一份记录。 */
    suspend fun renameSession(device: Device, token: String?, sessionId: String, title: String)

    /** 删会话记录（连文件一起，桌面端也一样，不可撤销）。 */
    suspend fun deleteSession(device: Device, token: String?, sessionId: String)

    /**
     * 复制会话（浅拷贝出新记录）。只有 `pi` 后端的会话能复制 ——
     * dsh / acp 在服务端会 500，所以返回 null 表示「这个后端不支持」。
     */
    suspend fun copySession(device: Device, token: String?, sessionId: String): String?

    /** 从项目列表移除工作区（桌面端语义：删的是记录，磁盘目录不动）。 */
    suspend fun removeProject(device: Device, token: String?, projectId: String)

    suspend fun rawGet(device: Device, token: String?, path: String): String

    /** `/api/sessions/runtimes`：所有会话的 runtime 概要（约 3KB，后台看护轮询用）。 */
    suspend fun runtimes(device: Device, token: String?): List<RuntimeInfo>

    /** 工作区「待办」：会话的 todo 计划，没计划时返回 null。 */
    suspend fun todo(device: Device, token: String?, sessionId: String): WorkspaceTodo?

    /** 工作区「改动」：git status（含分支名与未跟踪文件）。 */
    suspend fun changes(device: Device, token: String?, projectId: String, projectPath: String?): WorkspaceChanges

    /** 工作区「文件」：某一层的目录列表，[path] 为空表示项目根。 */
    suspend fun files(device: Device, token: String?, projectId: String, path: String): List<FileNode>
    /** 点开文件时读正文（桌面端会按大小截断，这里再兜一次底）。 */
    suspend fun fileText(device: Device, token: String?, projectId: String, path: String): String

    /** 可选模型清单（`GET /api/models`，带缓存，force 才重新扫）。 */
    suspend fun models(device: Device, token: String?, force: Boolean = false): List<AvailableModel>

    /**
     * 改会话偏好（模型 / 思考档位 / 标题）。
     *
     * 运行中的会话由主机在下次激活时 applyPreferences；草稿会话直接写记录。
     * 两条路径都是这一个端点，所以调用方改完只需重读 `/api/state` 对账。
     */
    suspend fun updateSession(device: Device, token: String?, sessionId: String, patch: JsonObject)
}

class OkHttpPideckApi(private val client: OkHttpClient) : PideckApi {

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    override suspend fun state(device: Device, token: String?, etag: String?): StateFetch =
        withContext(Dispatchers.IO) {
            val request = requestBuilder(device, token, "/api/state")
                .apply { if (!etag.isNullOrBlank()) header("if-none-match", etag) }
                .build()
            client.newCall(request).await().use { response ->
                when {
                    response.code == 304 -> StateFetch.NotModified
                    !response.isSuccessful ->
                        throw PideckException("state ${response.code}", code = response.code)
                    else -> {
                        val body = response.body?.string() ?: throw PideckException("state: 空响应")
                        StateFetch.Fresh(
                            PideckJson.decodeFromString(PideckState.serializer(), body),
                            response.header("etag"),
                        )
                    }
                }
            }
        }

    override suspend fun messagesPage(
        device: Device,
        token: String?,
        sessionId: String,
        pageSize: Int,
        before: Long?,
    ): SessionMessagePage = withContext(Dispatchers.IO) {
        val query = buildString {
            append("?pageSize=").append(pageSize)
            if (before != null) append("&before=").append(before)
        }
        val path = "/api/sessions/${encode(sessionId)}/messages/page$query"
        client.newCall(requestBuilder(device, token, path).build()).await().use { response ->
            if (!response.isSuccessful) {
                throw PideckException("messages ${response.code}", code = response.code)
            }
            val body = response.body?.string() ?: throw PideckException("messages: 空响应")
            PideckJson.decodeFromString(SessionMessagePage.serializer(), body)
        }
    }

    override fun sessionStream(device: Device, token: String?, sessionId: String): Flow<SseFrame> {
        val request = requestBuilder(device, token, "/api/sessions/${encode(sessionId)}/stream")
            .header("accept", "text/event-stream")
            .header("cache-control", "no-cache")
            .build()
        return streamOf(request)
    }

    override fun chat(device: Device, token: String?, body: JsonObject): Flow<SseFrame> {
        val request = requestBuilder(device, token, "/api/chat")
            .header("accept", "text/event-stream")
            .post(body.toString().toRequestBody(jsonMedia))
            .build()
        return streamOf(request)
    }

    override suspend fun runtimes(device: Device, token: String?): List<RuntimeInfo> =
        withContext(Dispatchers.IO) {
            val text = rawGet(device, token, "/api/sessions/runtimes")
            val root = PideckJson.parseToJsonElement(text) as? JsonObject ?: return@withContext emptyList()
            (root["runtimes"] as? JsonArray)?.mapNotNull { element ->
                runCatching {
                    PideckJson.decodeFromJsonElement(RuntimeInfo.serializer(), element)
                }.getOrNull()
            } ?: emptyList()
        }

    override suspend fun todo(device: Device, token: String?, sessionId: String): WorkspaceTodo? =
        WorkspaceParser.todo(
            PideckJson.parseToJsonElement(
                rawGet(device, token, "/api/sessions/$sessionId/todo"),
            ),
        )

    override suspend fun changes(
        device: Device,
        token: String?,
        projectId: String,
        projectPath: String?,
    ): WorkspaceChanges = WorkspaceParser.changes(
        PideckJson.parseToJsonElement(rawGet(device, token, "/api/git/status?projectId=$projectId")),
        projectPath,
    )

    override suspend fun files(
        device: Device,
        token: String?,
        projectId: String,
        path: String,
    ): List<FileNode> = WorkspaceParser.files(
        PideckJson.parseToJsonElement(
            rawGet(
                device,
                token,
                "/api/files?projectId=$projectId&path=${java.net.URLEncoder.encode(path, "UTF-8")}",
            ),
        ),
    )

    override suspend fun fileText(
        device: Device,
        token: String?,
        projectId: String,
        path: String,
    ): String {
        val text = rawGet(
            device,
            token,
            "/api/file-content?projectId=$projectId&path=${java.net.URLEncoder.encode(path, "UTF-8")}",
        )
        return runCatching {
            val obj = PideckJson.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
            val content = (obj["content"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            if (content != null) content else text
        }.getOrDefault(text)
    }

    override suspend fun rawGet(device: Device, token: String?, path: String): String =
        withContext(Dispatchers.IO) {
            client.newCall(requestBuilder(device, token, path).build()).await().use { response ->
                if (!response.isSuccessful) {
                    throw PideckException("GET $path → ${response.code}", code = response.code)
                }
                response.body?.string().orEmpty()
            }
        }

    override suspend fun createSession(device: Device, token: String?, projectId: String): String =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject { put("projectId", projectId) }
            val text = postJson(device, token, "/api/sessions", body)
            val parsed = runCatching {
                PideckJson.parseToJsonElement(text).jsonObject["session"]?.jsonObject?.get("id")?.jsonPrimitive?.content
            }.getOrNull()
            parsed?.takeIf { it.isNotBlank() } ?: throw PideckException("新建会话没有返回 id")
        }

    override suspend fun deleteSession(
        device: Device,
        token: String?,
        sessionId: String,
    ) {
        withContext(Dispatchers.IO) {
            postJson(
                device,
                token,
                "/api/sessions/${URLEncoder.encode(sessionId, "UTF-8")}/delete",
                buildJsonObject { },
            )
        }
    }

    override suspend fun copySession(
        device: Device,
        token: String?,
        sessionId: String,
    ): String? = withContext(Dispatchers.IO) {
        val raw = runCatching {
            postJson(
                device,
                token,
                "/api/sessions/${URLEncoder.encode(sessionId, "UTF-8")}/copy",
                buildJsonObject { },
            )
        }.getOrNull() ?: return@withContext null
        runCatching {
            PideckJson.parseToJsonElement(raw).jsonObject["result"]?.jsonObject
                ?.get("targetSessionId")?.jsonPrimitive?.content
        }.getOrNull()
    }

    override suspend fun removeProject(
        device: Device,
        token: String?,
        projectId: String,
    ) {
        withContext(Dispatchers.IO) {
            postJson(
                device,
                token,
                "/api/projects/${URLEncoder.encode(projectId, "UTF-8")}/delete",
                buildJsonObject { },
            )
        }
    }

    override suspend fun renameSession(
        device: Device,
        token: String?,
        sessionId: String,
        title: String,
    ) {
        withContext(Dispatchers.IO) {
            postJson(
            device,
            token,
                "/api/sessions/${URLEncoder.encode(sessionId, "UTF-8")}/update",
                buildJsonObject { put("title", title) },
            )
        }
    }

    override suspend fun stopRuntime(
        device: Device,
        token: String?,
        sessionId: String,
        agentId: String,
        runtimeGeneration: Long,
    ) = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put(
                "target",
                buildJsonObject {
                    put("sessionId", sessionId)
                    put("agentId", agentId)
                    put("runtimeGeneration", runtimeGeneration)
                },
            )
        }
        postJson(device, token, "/api/sessions/${encode(sessionId)}/runtime/stop", body)
        Unit
    }

    override suspend fun models(device: Device, token: String?, force: Boolean): List<AvailableModel> =
        withContext(Dispatchers.IO) {
            val raw = rawGet(device, token, if (force) "/api/models?force=1" else "/api/models")
            val root = PideckJson.parseToJsonElement(raw) as? JsonObject
            val list = root?.get("models") as? JsonArray ?: return@withContext emptyList()
            list.toList().toAvailableModels()
        }

    override suspend fun updateSession(
        device: Device,
        token: String?,
        sessionId: String,
        patch: JsonObject,
    ) {
        withContext(Dispatchers.IO) {
            postJson(
                device,
                token,
                "/api/sessions/${URLEncoder.encode(sessionId, "UTF-8")}/update",
                patch,
            )
        }
    }

    private suspend fun postJson(device: Device, token: String?, path: String, body: JsonObject): String {
        val request = requestBuilder(device, token, path)
            .post(body.toString().toRequestBody(jsonMedia))
            .build()
        return client.newCall(request).await().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw PideckException("POST $path → ${response.code}", code = response.code)
            }
            text
        }
    }

    private fun requestBuilder(device: Device, token: String?, path: String): Request.Builder =
        Request.Builder()
            .url(device.baseUrl.trimEnd('/') + path)
            .apply { if (!token.isNullOrBlank()) header("authorization", "Bearer $token") }

    private fun streamOf(request: Request): Flow<SseFrame> = callbackFlow {
        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                trySend(SseFrame(id = id, type = type, data = data))
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val reason = when {
                    response != null -> "SSE ${response.code}"
                    t != null -> t.message ?: t::class.java.simpleName
                    else -> "SSE 断开"
                }
                close(PideckException(reason, t))
            }
        }
        val source = EventSources.createFactory(client).newEventSource(request, listener)
        awaitClose { source.cancel() }
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

/** OkHttp 的 Call 包成挂起函数（不引入 kotlinx-coroutines-okhttp 之外的依赖）。 */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}

/** 默认 client：SSE 需要「读不超时」，否则长时间无帧会被断开。 */
fun defaultOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(0, TimeUnit.MILLISECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .pingInterval(0, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .build()
