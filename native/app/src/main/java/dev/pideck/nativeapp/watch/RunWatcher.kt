package dev.pideck.nativeapp.watch

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * 后台看护开关 + App 前后台可见性。
 *
 * 为什么要有这个：回复是服务端在跑的，手机切到后台、锁屏之后本进程什么都收不到。
 * 一个 `dataSync` 前台服务每 12s 问一次 `/api/sessions/runtimes`，就能在「回复完成」的
 * 那一刻推一条通知出来。服务自己在没有活跃 run 时退出，不需要谁记得关它。
 */
object RunWatcher {

    /** App 是否处在可见状态（由 ProcessLifecycleOwner 维护，服务用它决定要不要弹通知）。 */
    @Volatile
    var appVisible: Boolean = true
        internal set

    /** 启动看护。前台时调用才稳（Android 12+ 限制后台启动前台服务）。 */
    fun start(context: Context) {
        val intent = Intent(context, RunWatchService::class.java)
        runCatching { ContextCompat.startForegroundService(context, intent) }
    }

    fun stop(context: Context) {
        runCatching { context.stopService(Intent(context, RunWatchService::class.java)) }
    }
}
