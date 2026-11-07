package dev.pideck.nativeapp

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import dev.pideck.nativeapp.data.CrashLog
import dev.pideck.nativeapp.data.Prefs
import dev.pideck.nativeapp.data.QuickMessages
import dev.pideck.nativeapp.data.TurnStatsStore
import dev.pideck.nativeapp.ui.sessions.GroupCollapse
import dev.pideck.nativeapp.watch.Notifications
import dev.pideck.nativeapp.watch.RunWatcher

@HiltAndroidApp
class PiDeckApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 最先装：崩溃是从这里往后任何一行抛的都归它记（设置页「诊断」里能看）
        CrashLog.install(this)
        Notifications.ensureChannels(this)
        // 偏好读取是 IO，扔到独立 scope；镜像值在 Prefs 里，非 Compose 的地方直接读
        val prefsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        Prefs.load(this, prefsScope)
        // 折叠状态要落盘，也要在启动时按设置恢复
        GroupCollapse.attach(this, prefsScope)
        // 统计行的结算结果：切会话/重启后还要显示同一份数
        TurnStatsStore.attach(this)
        QuickMessages.attach(this)
        dev.pideck.nativeapp.data.LocalPrompts.attach(this)
        // 前后台切换：切后台时尝试起看护服务（Android 12+ 可能拒绝，外面有 runCatching），
        // 回前台时把可见标记置回来，这样「跑完了」的通知只在真看不见 App 时弹。
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                RunWatcher.appVisible = true
            }

            override fun onStop(owner: LifecycleOwner) {
                RunWatcher.appVisible = false
                RunWatcher.start(this@PiDeckApp)
            }
        })
    }
}
