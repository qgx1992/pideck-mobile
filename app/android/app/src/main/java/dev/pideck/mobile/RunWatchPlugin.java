package dev.pideck.mobile;

import android.content.Context;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.util.Map;

/**
 * 后台任务看护的 JS 接口。
 *
 * 分工：
 *   - JS 负责「什么时候需要看护」（有会话在跑）
 *   - 原生负责「App 是否可见」（PiDeckApp.isAppVisible，按 Activity 计数）
 *   - 原生服务负责「App 被冻结后仍然能收到完成事件」
 *
 * 之所以要 JS 在运行开始时就启动服务：Android 12+ 禁止从后台启动前台服务，
 * 等到 App 已经切走再启动会被系统直接拒绝。
 */
@CapacitorPlugin(name = "RunWatch")
public class RunWatchPlugin extends Plugin {

    static final String PREFS = "pideck-runwatch";

    @PluginMethod
    public void start(PluginCall call) {
        String baseUrl = call.getString("baseUrl", "");
        String token = call.getString("token", "");
        JSObject titles = call.getObject("titles");
        if (baseUrl == null || baseUrl.isEmpty() || token == null || token.isEmpty()) {
            call.reject("baseUrl/token required");
            return;
        }
        String deviceId = call.getString("deviceId", "");
        RunWatchService.start(getContext(), baseUrl, token, titles != null ? titles.toString() : "{}",
            deviceId == null ? "" : deviceId);
        call.resolve();
    }

    @PluginMethod
    public void stop(PluginCall call) {
        RunWatchService.stop(getContext());
        call.resolve();
    }

    /**
     * 旧版的「JS 报告可见性」接口。
     *
     * 已废弃且故意保留成 no-op：JS 只能看到 WebView 的 visibilityState，
     * 跳进桌面网页版时那个值会变成 hidden，而 App 还在前台。
     * 可见性现在只由 PiDeckApp 的 Activity 计数决定，外部不要覆盖。
     */
    @Deprecated
    @PluginMethod
    public void setVisible(PluginCall call) {
        call.resolve();
    }

    /**
     * 取出「原生已经通知过」的会话 id 并清空。
     * JS 回到前台后据此跳过重复通知。
     */
    @PluginMethod
    public void consumeNotified(PluginCall call) {
        JSArray ids = new JSArray();
        try {
            Map<String, ?> all = getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).getAll();
            long now = System.currentTimeMillis();
            for (Map.Entry<String, ?> entry : all.entrySet()) {
                String key = entry.getKey();
                if (!key.startsWith("notified:")) continue;
                Object value = entry.getValue();
                long at = value instanceof Long ? (Long) value : 0L;
                // 只回看 10 分钟内的，旧的自动清掉
                if (now - at <= 10 * 60 * 1000L) ids.put(key.substring("notified:".length()));
            }
            getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
        } catch (Exception ignored) {
            /* 读不到就当没有 */
        }
        JSObject result = new JSObject();
        result.put("sessionIds", ids);
        call.resolve(result);
    }

    /** 用户点通知打开 App 时带的会话 id（一次性）。 */
    @PluginMethod
    public void consumeLaunchSession(PluginCall call) {
        JSObject result = new JSObject();
        String pending = MainActivity.takePendingSessionId();
        String deviceId = MainActivity.takePendingDeviceId();
        result.put("sessionId", pending == null ? "" : pending);
        result.put("deviceId", deviceId == null ? "" : deviceId);
        call.resolve(result);
    }
}
