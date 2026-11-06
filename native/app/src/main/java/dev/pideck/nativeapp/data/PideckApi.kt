package dev.pideck.nativeapp.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
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

    /**
     * `POST /api/sessions`：按项目新建草稿会话，返回新会话 id。
     *
     * [backend] / [acpToolId] 原样放进 body —— 宿主的路由只校验 `projectId`，其余字段整个透传给
     * `createSessionDraft`（它只认 `dsh` / `acp`，写别的会退回 pi）。见 [AgentBackend]。
     */
    suspend fun createSession(
        device: Device,
        token: String?,
        projectId: String,
        backend: String? = null,
        acpToolId: String? = null,
        model: SessionModel? = null,
        thinkingLevel: String? = null,
    ): String

    /**
     * `POST /api/sessions/:id/prompt`：投递一条 prompt，返回回执（不是回复流）。
     *
     * 唯一能吃 `streamingBehavior` 的通道 —— 「排队到下一轮」靠它；回复本身从
     * 会话流（[sessionStream]）里来，和「别处发起的回合」是同一条路。
     */
    suspend fun sendPrompt(
        device: Device,
        token: String?,
        sessionId: String,
        body: kotlinx.serialization.json.JsonObject,
    ): PromptDelivery

    /**
     * `POST /api/sessions/anonymous`：开一个匿名会话（`noSession: true`，不落盘、
     * 不进项目列表），并行问询用。会话里模型 / 思考档位由宿主按启动默认值填，
     * 客户端不用管。
     */
    suspend fun createAnonymousSession(device: Device, token: String?, projectId: String): String

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
    suspend fun fileText(
        device: Device,
        token: String?,
        projectId: String,
        path: String,
        sessionId: String? = null,
    ): String

    /**
     * 单个文件的 git diff 文本。
     *
     * [group] 必须是 `merge` / `index` / `workingTree` / `untracked` 之一（桌面端按这四组分类，
     * 传别的值直接 400），[path] 是**仓库内相对路径**。
     */
    suspend fun fileDiff(
        device: Device,
        token: String?,
        projectId: String,
        group: String,
        path: String,
    ): LineDiff.FileDiffPayload?

    /** 可选模型清单（`GET /api/models`，带缓存，force 才重新扫）。 */
    suspend fun models(device: Device, token: String?, force: Boolean = false): List<AvailableModel>

    /**
     * 改会话偏好（模型 / 思考档位 / 标题）。
     *
     * 运行中的会话由主机在下次激活时 applyPreferences；草稿会话直接写记录。
     * 两条路径都是这一个端点，所以调用方改完只需重读 `/api/state` 对账。
     */
    suspend fun updateSession(device: Device, token: String?, sessionId: String, patch: JsonObject)

    /**
     * 切 pi 会话的安全等级（`POST /api/sessions/:id/security-level`，`levelId = null` = 清除会话覆盖、跟随全局默认）。
     *
     * 与 DSH 权限预设是两套机制：服务端校验会话/后端/等级后写策略快照，返回最新投影；
     * 不是 pi 会话会被拒（所以调用前先看 [privilegeControlFor]）。
     */
    suspend fun setSessionSecurityLevel(
        device: Device,
        token: String?,
        sessionId: String,
        levelId: String?,
    ): SecurityState

    /** 回答 AI 的提问卡（`POST /api/ui-response`，body 由 [AskUi.body] 组装）。 */
    suspend fun respondUi(device: Device, token: String?, body: JsonObject)

    /** 会话的子代理（`/api/sessions/:id/subagents`）。 */
    suspend fun subagents(device: Device, token: String?, sessionId: String): List<SubagentEntry>

    /**
     * 运行时状态投影（`POST /api/sessions/:id/runtime/state`）：上下文用量与权限预设都从这里取。
     *
     * 桌面端头部那个圆环就是它；运行时没起来时服务端返回空对象，这里也就给 [ContextUsage] 默认值。
     * 同一份回包里还带着当前真正生效的模型与思考档位（[RuntimeSnapshot.model] / [RuntimeSnapshot.thinkingLevel]）。
     */
    suspend fun runtimeState(device: Device, token: String?, target: RuntimeTarget): RuntimeSnapshot

    /** 通用运行时动作（`runtime/permission`、`runtime/compact` …），body 里自动带上 target。 */
    suspend fun runtimeCommand(
        device: Device,
        token: String?,
        target: RuntimeTarget,
        action: String,
        extra: JsonObject = JsonObject(emptyMap()),
    ): String

    /** 导出会话 HTML（`GET /api/sessions/:id/export-html`，响应体就是整份 HTML）。 */
    suspend fun exportHtml(device: Device, token: String?, sessionId: String): String
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
                // 桌面端的参数名是 `dir`（见 vendor/pideck/src/renderer/src/web/webApi.ts 的
                // fetchFileList）；早先写成 `path` 会被服务端忽略，于是永远只列项目根目录。
                "/api/files?projectId=$projectId&dir=${java.net.URLEncoder.encode(path, "UTF-8")}",
            ),
        ),
    )

    override suspend fun fileDiff(
        device: Device,
        token: String?,
        projectId: String,
        group: String,
        path: String,
    ): LineDiff.FileDiffPayload? {
        val query = buildString {
            append("/api/git/diff?projectId=").append(java.net.URLEncoder.encode(projectId, "UTF-8"))
            append("&group=").append(java.net.URLEncoder.encode(group, "UTF-8"))
            append("&path=").append(java.net.URLEncoder.encode(path, "UTF-8"))
        }
        val text = rawGet(device, token, query)
        return runCatching {
            val root = PideckJson.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
            val diff = root["diff"] ?: return@runCatching null
            LineDiff.parsePayload(diff)
        }.getOrNull()
    }

    override suspend fun fileText(
        device: Device,
        token: String?,
        projectId: String,
        path: String,
        sessionId: String?,
    ): String {
        val base = "/api/file-content?projectId=$projectId&path=${java.net.URLEncoder.encode(path, "UTF-8")}" +
            (sessionId?.let { "&sessionId=$it" } ?: "")
        // 桌面端对「项目外路径 / 敏感文件」会先拒一次（code 里带 needsConfirm），
        // 带上 confirm=1 才会读；用户是自己在文件树里点的这个文件，就等于确认过了。
        val text = try {
            rawGet(device, token, base)
        } catch (e: PideckException) {
            if (e.code == null) throw e
            rawGet(device, token, "$base&confirm=1")
        }
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

    override suspend fun createSession(
        device: Device,
        token: String?,
        projectId: String,
        backend: String?,
        acpToolId: String?,
        model: SessionModel?,
        thinkingLevel: String?,
    ): String =
        withContext(Dispatchers.IO) {
            val body = ChatPayloads.createSessionBody(projectId, backend, acpToolId, model, thinkingLevel)
            val text = postJson(device, token, "/api/sessions", body)
            val parsed = runCatching {
                PideckJson.parseToJsonElement(text).jsonObject["session"]?.jsonObject?.get("id")?.jsonPrimitive?.content
            }.getOrNull()
            parsed?.takeIf { it.isNotBlank() } ?: throw PideckException("新建会话没有返回 id")
        }

    override suspend fun sendPrompt(
        device: Device,
        token: String?,
        sessionId: String,
        body: kotlinx.serialization.json.JsonObject,
    ): PromptDelivery = withContext(Dispatchers.IO) {
        val text = postJson(
            device,
            token,
            "/api/sessions/${URLEncoder.encode(sessionId, "UTF-8")}/prompt",
            body,
        )
        parsePromptDelivery(text)
    }

    override suspend fun createAnonymousSession(
        device: Device,
        token: String?,
        projectId: String,
    ): String = withContext(Dispatchers.IO) {
        val body = buildJsonObject { put("projectId", projectId) }
        val text = postJson(device, token, "/api/sessions/anonymous", body)
        val parsed = runCatching {
            PideckJson.parseToJsonElement(text).jsonObject["session"]?.jsonObject?.get("id")?.jsonPrimitive?.content
        }.getOrNull()
        parsed?.takeIf { it.isNotBlank() } ?: throw PideckException("匿名会话没有返回 id")
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

    override suspend fun runtimeState(
        device: Device,
        token: String?,
        target: RuntimeTarget,
    ): RuntimeSnapshot = withContext(Dispatchers.IO) {
        val text = postJson(
            device,
            token,
            "/api/sessions/${encode(target.sessionId)}/runtime/state",
            runtimeTargetBody(target),
        )
        parseRuntimeSnapshot(unwrapRuntimeState(runCatching {
            PideckJson.parseToJsonElement(text)
        }.getOrNull()))
    }

    override suspend fun runtimeCommand(
        device: Device,
        token: String?,
        target: RuntimeTarget,
        action: String,
        extra: JsonObject,
    ): String = withContext(Dispatchers.IO) {
        val body = JsonObject(runtimeTargetBody(target) + extra)
        postJson(device, token, "/api/sessions/${encode(target.sessionId)}/runtime/$action", body)
    }

    override suspend fun exportHtml(
        device: Device,
        token: String?,
        sessionId: String,
    ): String = withContext(Dispatchers.IO) {
        val path = "/api/sessions/${encode(sessionId)}/export-html"
        client.newCall(requestBuilder(device, token, path).build()).await().use { response ->
            if (!response.isSuccessful) {
                throw PideckException("export ${response.code}", code = response.code)
            }
            response.body?.string().orEmpty()
        }
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

    override suspend fun setSessionSecurityLevel(
        device: Device,
        token: String?,
        sessionId: String,
        levelId: String?,
    ): SecurityState = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("levelId", levelId?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
        }
        val text = postJson(device, token, "/api/sessions/${encode(sessionId)}/security-level", body)
        val root = runCatching { PideckJson.parseToJsonElement(text) }.getOrNull() as? JsonObject
        parseSecurityState(root?.get("security"))
            ?: throw PideckException("security-level: 返回体里没有 security")
    }


    override suspend fun respondUi(device: Device, token: String?, body: JsonObject) {
        postJson(device, token, "/api/ui-response", body)
    }

    override suspend fun subagents(
        device: Device,
        token: String?,
        sessionId: String,
    ): List<SubagentEntry> = withContext(Dispatchers.IO) {
        val text = rawGet(device, token, "/api/sessions/$sessionId/subagents")
        val root = PideckJson.parseToJsonElement(text) as? JsonObject ?: return@withContext emptyList()
        val array = root["subagents"] as? JsonArray ?: return@withContext emptyList()
        array.mapNotNull { element ->
            runCatching {
                PideckJson.decodeFromJsonElement(SubagentEntry.serializer(), element)
            }.getOrNull()
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

/** 回执解析：`{"result":{"accepted":true,...}}`；拒绝时只有 `accepted:false` + `error`。 */
internal fun parsePromptDelivery(text: String): PromptDelivery {
    val root = runCatching { PideckJson.parseToJsonElement(text).jsonObject }.getOrNull()
    val result = root?.get("result") as? JsonObject
        ?: root?.get("state") as? JsonObject
        ?: root
    val accepted = (result?.get("accepted") as? JsonPrimitive)?.booleanOrNull ?: false
    fun str(key: String): String? =
        (result?.get(key) as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    val generation = (result?.get("runtimeGeneration") as? JsonPrimitive)?.let { p ->
        p.doubleOrNull?.toLong() ?: p.content.toLongOrNull()
    }
    return PromptDelivery(
        accepted = accepted,
        error = str("error"),
        agentId = str("agentId"),
        runtimeGeneration = generation,
        delivery = str("delivery"),
    )
}
