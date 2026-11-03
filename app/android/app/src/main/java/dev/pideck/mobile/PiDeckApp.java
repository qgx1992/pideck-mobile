package dev.pideck.mobile;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

/**
 * 全局可见性判定（替代 MainActivity 里原来那对 onResume/onPause）。
 *
 * RunWatchService 用「App 是否可见」决定要不要发完成通知
 * （RunWatchService.java:277 与 :322）。0.3.12 起只有 MainActivity 一个页面，
 * 但这里仍按「还有几个 Activity 处于 started 状态」计数：计数 > 0 就算可见。
 * 多页面时代（DesktopWebActivity）留下的机制，留着不影响行为，也省得将来加页面再改一遍。
 *
 * 注意：onActivityStopped 的调用顺序是「新 Activity onStart → 旧 Activity onStop」，
 * 所以切页过程中计数会短暂到 2，不会误落回 0。
 */
public class PiDeckApp extends Application implements Application.ActivityLifecycleCallbacks {

    /**
     * 全进程唯一的「App 是否在前台」真相源。
     *
     * 不要再让 JS 通过 RunWatch.setVisible 之类的方式覆盖它：WebView 的
     * document.visibilityState 只能说明「WebView 有没有被盖住」，跳进桌面网页版时
     * 它会变成 hidden，而 App 明明还在前台（踩过：0.1.19 上在别的页面停留时
     * 照样弹完成通知）。只有 Activity 落在 started 状态才算前台。
     */
    private static volatile boolean appVisible = true;

    private int startedCount = 0;

    public static boolean isAppVisible() {
        return appVisible;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(this);
    }

    @Override
    public void onActivityStarted(Activity activity) {
        startedCount++;
        if (startedCount == 1) appVisible = true;
    }

    @Override
    public void onActivityStopped(Activity activity) {
        startedCount--;
        if (startedCount <= 0) {
            startedCount = 0;
            appVisible = false;
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
