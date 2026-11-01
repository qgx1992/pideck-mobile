package dev.pideck.mobile;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

/**
 * 全局可见性判定（替代 MainActivity 里原来那对 onResume/onPause）。
 *
 * 为什么必须改：RunWatchService 用「App 是否可见」决定要不要发完成通知
 * （RunWatchService.java:277 与 :322）。原来只有 MainActivity 一个 Activity，
 * 在它 onResume/onPause 里直接喂 setAppVisible 就够了。
 *
 * 加了 FlutterHostActivity 之后这个假设失效：跳到 Flutter 页时 MainActivity 会
 * onPause，而 App 明明还在前台 —— 监控会误判成「退到后台」，开始给用户发通知，
 * 和 JS 侧的通知重复。所以改成按「还有几个 Activity 处于 started 状态」计数：
 * 计数 > 0 就算可见。以后再加第三个 Activity 也不用动这里。
 *
 * 注意：onActivityStopped 的调用顺序是「新 Activity onStart → 旧 Activity onStop」，
 * 所以切页过程中计数会短暂到 2，不会误落回 0。
 */
public class PiDeckApp extends Application implements Application.ActivityLifecycleCallbacks {

    private int startedCount = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(this);
    }

    @Override
    public void onActivityStarted(Activity activity) {
        startedCount++;
        if (startedCount == 1) RunWatchService.setAppVisible(true);
    }

    @Override
    public void onActivityStopped(Activity activity) {
        startedCount--;
        if (startedCount <= 0) {
            startedCount = 0;
            RunWatchService.setAppVisible(false);
        }
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
        /* 不需要 */
    }

    @Override
    public void onActivityResumed(Activity activity) {
        /* 不需要 */
    }

    @Override
    public void onActivityPaused(Activity activity) {
        /* 不需要 */
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
        /* 不需要 */
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
        /* 不需要 */
    }
}
