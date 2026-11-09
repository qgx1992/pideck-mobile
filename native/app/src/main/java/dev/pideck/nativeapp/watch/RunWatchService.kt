package dev.pideck.nativeapp.watch

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.pideck.nativeapp.data.Device
import dev.pideck.nativeapp.data.DeviceRepository
import dev.pideck.nativeapp.data.Prefs
import dev.pideck.nativeapp.data.PideckApi
import dev.pideck.nativeapp.data.PideckJson
import dev.pideck.nativeapp.data.PendingUiRequest
import dev.pideck.nativeapp.data.PideckState
import dev.pideck.nativeapp.data.StateFetch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

/**
 * 后台看护：`/api/sessions/runtimes`（约 3KB）轮询，把「跑完了」变成一条通知。
 *
 * 存在理由：回复是服务端在跑，App 在后台时本进程收不到任何东西。前台服务 + 12s 轮询
 * 是唯一不依赖推送服务器的办法（桌面端就在局域网里，没有 FCM 可用）。
 *
 * 结束条件：连续 3 次轮询都没有活跃 run，而且已经跑够 90s（刚发出消息时 runtime 可能
 * 还没登记，立刻退出会漏掉这一轮）。
 */
@AndroidEntryPoint
class RunWatchService : Service() {

    @Inject
    lateinit var api: PideckApi

    @Inject
    lateinit var repository: DeviceRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        val notification = Notifications.active(this, 1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                Notifications.ACTIVE_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(Notifications.ACTIVE_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (job == null) job = scope.launch { watchLoop() }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun watchLoop() {
        val wasRunning = mutableMapOf<String, Boolean>()
        val titles = mutableMapOf<String, String>()
        /** 每台设备上一次 `/api/state` 的 ETag：没变化就是 304，不带 body。 */
        val etags = mutableMapOf<String, String?>()
        /** 每台设备上一次拿到的待回答提问（304 时沿用，不然会把「还在等」误判成「已经答了」）。 */
        val pendingByDevice = mutableMapOf<String, List<PendingUiRequest>>()
        /** 已经通知过的提问（`deviceId|sessionId|requestId`）：同一张卡别每 12s 弹一次。 */
        val notifiedAsks = mutableSetOf<String>()
        val startedAt = SystemClock.elapsedRealtime()
        var idlePolls = 0

        while (true) {
            // 整轮兜一层：这里的异常没人接就是进程级崩溃（SupervisorJob 只防兄弟协程被取消）。
            // 从前 `tokenOf` 读 Keystore 失败一次就能把整个 App 带走 —— 表现是「冷启动闪退」。
            try {
                val devices = runCatching { repository.devices.first() }.getOrDefault(emptyList())
                var active = 0

                for (device in devices) {
                    val token = runCatching { repository.tokenOf(device.id) }.getOrNull()

                    // 待回答的提问（问答卡）。先问 `/api/state`，因为 `/api/sessions/runtimes`
                    // 里没有 pending 这一项 —— 以前后台**永远不会**因为「它问你一句」而提醒你，
                    // 只有「回复完成」会弹（而提问卡出现时 agent 可能正好从 running 转 idle，
                    // 于是弹出来的还是那句错的「回复完成」）。
                    // 带 If-None-Match：没变化时宿主直接回 304、不带 body，可以每轮问。
                    val stateFetch = runCatching {
                        withTimeout(8_000) { api.state(device, token, etags[device.id]) }
                    }.getOrNull()
                    if (stateFetch is StateFetch.Fresh) {
                        etags[device.id] = stateFetch.etag
                        pendingByDevice[device.id] = stateFetch.state.pendingUiRequests
                        stateFetch.state.sessions.forEach { session ->
                            session.title.takeIf { it.isNotBlank() }?.let { titles[session.id] = it }
                        }
                    }
                    val pending = pendingByDevice[device.id].orEmpty()
                    val pendingSessions = pending.map { it.sessionId }.toSet()

                    if (!RunWatcher.appVisible && Prefs.notifyDone) {
                        for (ask in pending) {
                            val askKey = "${device.id}|${ask.sessionId}|${ask.requestId}"
                            if (!notifiedAsks.add(askKey)) continue
                            runCatching {
                                Notifications.question(
                                    this,
                                    device.id,
                                    ask.sessionId,
                                    titles[ask.sessionId].orEmpty(),
                                    ask.title,
                                )
                            }
                        }
                    }

                    val runtimes = runCatching {
                        withTimeout(8_000) { api.runtimes(device, token) }
                    }.getOrNull() ?: continue

                    for (runtime in runtimes) {
                        val key = device.id + "|" + runtime.sessionId
                        val running = runtime.status == "running" || runtime.status == "busy"
                        if (running) active++
                        if (wasRunning[key] == true && !running) {
                            // 等着人回答的时候别报「回复完成」：那张提问通知已经把人叫过去了。
                            val waitingForAnswer = runtime.sessionId in pendingSessions
                            // 只在看不见 App 的时候打扰人；前台时用户自己就看到了。
                            if (!waitingForAnswer && !RunWatcher.appVisible && Prefs.notifyDone) {
                                val title = titles[runtime.sessionId]
                                    ?: runCatching { titleOf(device, token, runtime.sessionId) }.getOrNull()
                                if (!title.isNullOrBlank()) titles[runtime.sessionId] = title
                                runCatching {
                                    Notifications.finished(
                                        this,
                                        device.id,
                                        runtime.sessionId,
                                        title.orEmpty(),
                                        failed = false,
                                    )
                                }
                            }
                            wasRunning.remove(key)
                        } else {
                            wasRunning[key] = running
                        }
                    }
                }

                // 已经回答 / 撤回的提问腾出去重表，否则同一个 requestId 再出现就被静音了。
                val liveAskKeys = buildSet {
                    for ((deviceId, asks) in pendingByDevice) {
                        for (ask in asks) add("$deviceId|${ask.sessionId}|${ask.requestId}")
                    }
                }
                notifiedAsks.retainAll(liveAskKeys)

                runCatching {
                    getSystemService(NotificationManager::class.java)
                        ?.notify(Notifications.ACTIVE_ID, Notifications.active(this, active.coerceAtLeast(1)))
                }

                idlePolls = if (active == 0) idlePolls + 1 else 0
                val alive = SystemClock.elapsedRealtime() - startedAt
                if (idlePolls >= 3 && alive > 90_000) {
                    stopSelf()
                    return
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w("PiDeckWatch", "看护轮询失败：${e::class.java.simpleName}: ${e.message}")
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    /** 会话标题：拿不到 `/api/state` 时兜底用（正常路径上标题已经随 state 一起拿到了）。 */
    private suspend fun titleOf(device: Device, token: String?, sessionId: String): String? {
        val state = PideckJson.decodeFromString(PideckState.serializer(), api.rawGet(device, token, "/api/state"))
        return state.sessions.firstOrNull { it.id == sessionId }?.title
    }

    private companion object {
        const val POLL_INTERVAL_MS = 12_000L
    }
}
