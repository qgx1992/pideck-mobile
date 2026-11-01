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
