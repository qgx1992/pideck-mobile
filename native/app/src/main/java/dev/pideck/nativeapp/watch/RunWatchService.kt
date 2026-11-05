package dev.pideck.nativeapp.watch

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.pideck.nativeapp.data.Device
import dev.pideck.nativeapp.data.DeviceRepository
import dev.pideck.nativeapp.data.Prefs
import dev.pideck.nativeapp.data.PideckApi
import dev.pideck.nativeapp.data.PideckJson
import dev.pideck.nativeapp.data.PideckState
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
        val startedAt = SystemClock.elapsedRealtime()
        var idlePolls = 0

        while (true) {
            val devices = runCatching { repository.devices.first() }.getOrDefault(emptyList())
            var active = 0

            for (device in devices) {
                val token = repository.tokenOf(device.id)
                val runtimes = runCatching {
                    withTimeout(8_000) { api.runtimes(device, token) }
                }.getOrNull() ?: continue

                for (runtime in runtimes) {
                    val key = device.id + "|" + runtime.sessionId
                    val running = runtime.status == "running" || runtime.status == "busy"
                    if (running) active++
                    if (wasRunning[key] == true && !running) {
                        // 只在看不见 App 的时候打扰人；前台时用户自己就看到了。
                        if (!RunWatcher.appVisible && Prefs.notifyDone) {
                            val title = titles[runtime.sessionId]
                                ?: runCatching { titleOf(device, token, runtime.sessionId) }.getOrNull()
                            if (!title.isNullOrBlank()) titles[runtime.sessionId] = title
                            Notifications.finished(
                                this,
                                device.id,
                                runtime.sessionId,
                                title.orEmpty(),
                                failed = false,
                            )
                        }
                        wasRunning.remove(key)
                    } else {
                        wasRunning[key] = running
                    }
                }
            }

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
            delay(POLL_INTERVAL_MS)
        }
    }

    /** 会话标题：只有要发通知时才拉一次 `/api/state`（55KB，别每轮都拉）。 */
    private suspend fun titleOf(device: Device, token: String?, sessionId: String): String? {
        val state = PideckJson.decodeFromString(PideckState.serializer(), api.rawGet(device, token, "/api/state"))
        return state.sessions.firstOrNull { it.id == sessionId }?.title
    }

    private companion object {
        const val POLL_INTERVAL_MS = 12_000L
    }
}
