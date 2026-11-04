package dev.pideck.nativeapp

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.HiltAndroidApp
import dev.pideck.nativeapp.watch.Notifications
import dev.pideck.nativeapp.watch.RunWatcher

@HiltAndroidApp
class PiDeckApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
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
