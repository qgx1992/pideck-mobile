package dev.pideck.mobile;

import android.content.Intent;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * WebView 侧打开 Flutter 页的开关（`open({ route, sessionId })`）。
 *
 * 对应 JS 薄封装：app/src/platform/flutter-host.ts。
 * 只负责 startActivity，不缓存任何状态 —— Flutter 页自己通过
 * MethodChannel 取参数、自己 finish() 返回，两边不共享对象，也就没有生命周期问题。
 */
@CapacitorPlugin(name = "FlutterHost")
public class FlutterLauncherPlugin extends Plugin {

    /** 事件名：Flutter 页打开/关闭。JS 侧用它把「WebView 被盖住」和「App 真在后台」区分开。 */
    static final String EVENT_PAGE_VISIBILITY = "pageVisibility";

    /** Capacitor 只创建一个插件实例，这里存一份给 FlutterHostActivity 回传生命周期用。 */
    private static FlutterLauncherPlugin instance;

    @Override
    public void load() {
        instance = this;
    }

    @Override
    protected void handleOnDestroy() {
        if (instance == this) instance = null;
    }

    /**
     * Flutter 页开/关时由 FlutterHostActivity 调用。
     *
     * 为什么需要：跳进 Flutter 页时 WebView 会被 pause，document.visibilityState 变 hidden，
     * JS 会误以为「App 退到后台」而去发通知（而用户就看着屏幕）。
     * 拿到这个标志后 JS 才能把「被盖住」和「真后台」分开。
     */
    static void notifyPageVisibility(boolean open) {
        FlutterLauncherPlugin plugin = instance;
        if (plugin == null) return;
        try {
            JSObject data = new JSObject();
            data.put("open", open);
            plugin.notifyListeners(EVENT_PAGE_VISIBILITY, data);
        } catch (Exception ignored) {
            /* WebView 已经销毁时忽略 */
        }
    }

    @PluginMethod
    public void open(PluginCall call) {
        String route = call.getString("route", "");
        if (route == null || route.isEmpty()) {
            call.reject("route required");
            return;
        }
        String sessionId = call.getString("sessionId", "");

        try {
            Intent intent = new Intent(getContext(), FlutterHostActivity.class);
            intent.putExtra(FlutterHostActivity.EXTRA_ROUTE, route);
            if (sessionId != null && !sessionId.isEmpty()) {
                intent.putExtra(FlutterHostActivity.EXTRA_SESSION_ID, sessionId);
            }
            getActivity().startActivity(intent);
            JSObject result = new JSObject();
            result.put("opened", true);
            result.put("route", route);
            call.resolve(result);
        } catch (Exception error) {
            call.reject("打开 Flutter 页失败: " + error.getMessage(), error);
        }
    }

    /** 当前构建里是否编进了 Flutter 模块（老包没有这个 plugin，会走 reject）。 */
    @PluginMethod
    public void available(PluginCall call) {
        JSObject result = new JSObject();
        result.put("available", true);
        call.resolve(result);
    }
}
