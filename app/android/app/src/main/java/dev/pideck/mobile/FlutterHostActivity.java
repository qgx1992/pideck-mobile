package dev.pideck.mobile;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import java.util.Map;

import org.json.JSONObject;

import io.flutter.embedding.android.FlutterFragmentActivity;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

/**
 * 承载 Flutter 页面的宿主 Activity（add-to-app）。
 *
 * 为什么不把 MainActivity 改成 FlutterFragmentActivity：
 * MainActivity 继承的是 Capacitor 的 BridgeActivity（AppCompatActivity），
 * Java 单继承，改不了。所以另开一个 Activity，WebView 和 Flutter 各自一个
 * Activity，用 Intent 传递参数、用 finish() 返回，栈行为最省心。
 *
 * 参数约定（都放在 Intent extra 里，Flutter 侧通过 MethodChannel 取）：
 *   pideckFlutterRoute      要打开的路由名，例如 "settings" / "history"
 *   pideckFlutterSessionId  可选的会话 id（从聊天页跳过来时带上）
 *
 * Dart 侧（flutter_module/lib/main.dart）调 channel "pideck/flutter_host"：
 *   getArgs()          → {"route": "...", "sessionId": "..."}
 *   getSettings()      → 宿主缓存的 SettingsData JSON（首帧用，不等回程）
 *   settingsAction(s)  → 把用户在 Flutter 页上的动作回传给 WebView 执行
 *   getChat()          → 宿主缓存的聊天页视图模型 JSON（同上，首帧用）
 *   chatAction(s)      → 聊天页上的动作（发消息 / 取消 / 切模型…）回传给 WebView
 *   getSidebar()       → 取最近一次侧栏快照（Drawer 首帧直接用，不等往返）
 *   sidebarAction(s)   → 侧栏上的动作（开会话 / 切分段 / 搜索…）回传给 WebView
 *   close()            → 关掉 Flutter 页，回到 WebView
 *
 * 反向（原生 → Dart）：`setSettings(json)` / `setChat(json)` / `showNotice(text)`，由
 * FlutterLauncherPlugin 在 JS 推数据或要求提示时调进来。
 */
public class FlutterHostActivity extends FlutterFragmentActivity {

    static final String EXTRA_ROUTE = "pideckFlutterRoute";
    static final String EXTRA_SESSION_ID = "pideckFlutterSessionId";

    private static final String TAG = "PiDeckFlutterHost";
    private static final String CHANNEL = "pideck/flutter_host";

    /** 当前活着的宿主 Activity（插件要能反向调用它；同一时刻只可能有一个）。 */
    static volatile FlutterHostActivity current;

    private MethodChannel channel;

    private String route = "";
    private String sessionId = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // getIntent() 在 onCreate 之后可能被替换（singleTask 的重入），这里只取一次
        Intent intent = getIntent();
        route = safe(intent.getStringExtra(EXTRA_ROUTE));
        sessionId = safe(intent.getStringExtra(EXTRA_SESSION_ID));
    }

    /**
     * 告诉 WebView 侧「Flutter 页盖在上面了」。
     *
     * 这个 Activity 一 onStart，MainActivity 就会被 pause，WebView 的
     * document.visibilityState 变成 hidden。JS 没法自己分辨「被盖住」还是「退到后台」，
     * 所以由这里把真实情况报过去（否则在 Flutter 页停留时还会弹完成通知）。
     */
    @Override
    protected void onStart() {
        super.onStart();
        current = this;
        FlutterLauncherPlugin.notifyPageVisibility(true);
    }

    @Override
    protected void onStop() {
        if (current == this) current = null;
        FlutterLauncherPlugin.notifyPageVisibility(false);
        super.onStop();
    }

    /** JS 推来新数据时由插件调用（`FlutterHost.pushSettings`）。 */
    static void pushSettingsToFlutter(String json) {
        FlutterHostActivity activity = current;
        if (activity == null) return;
        activity.invokeOnFlutter("setSettings", json);
    }

    /** JS 推来新的聊天页视图模型时由插件调用（`FlutterHost.pushChat`）。 */
    static void pushChatToFlutter(String json) {
        FlutterHostActivity activity = current;
        if (activity == null) return;
        activity.invokeOnFlutter("setChat", json);
    }

    /** JS 推来新的侧栏快照时由插件调用（`FlutterHost.pushSidebar`）。 */
    static void pushSidebarToFlutter(String json) {
        FlutterHostActivity activity = current;
        if (activity == null) return;
        activity.invokeOnFlutter("setSidebar", json);
    }

    /** JS 要求弹一句提示时由插件调用（`FlutterHost.pushNotice`）。 */
    static void pushNoticeToFlutter(String text) {
        FlutterHostActivity activity = current;
        if (activity == null) return;
        activity.invokeOnFlutter("showNotice", text);
    }

    /** channel 只能在主线程用；插件回调可能在别的线程。 */
    private void invokeOnFlutter(String method, String argument) {
        final MethodChannel target = channel;
        if (target == null) return;
        runOnUiThread(() -> {
            try {
                target.invokeMethod(method, argument);
            } catch (Exception error) {
                Log.w(TAG, method + " 下发失败: " + error.getMessage());
            }
        });
    }

    @Override
    public void configureFlutterEngine(FlutterEngine flutterEngine) {
        super.configureFlutterEngine(flutterEngine);
        MethodChannel methodChannel =
                new MethodChannel(flutterEngine.getDartExecutor().getBinaryMessenger(), CHANNEL);
        methodChannel.setMethodCallHandler((call, result) -> {
            switch (call.method) {
                case "getArgs":
                    result.success(buildArgs());
                    break;
                case "getSettings":
                    result.success(FlutterLauncherPlugin.currentSettings());
                    break;
                case "settingsAction":
                    FlutterLauncherPlugin.dispatchSettingsAction(settingsJsonFrom(call));
                    result.success(null);
                    break;
                case "getChat":
                    result.success(FlutterLauncherPlugin.currentChat());
                    break;
                case "chatAction":
                    FlutterLauncherPlugin.dispatchChatAction(settingsJsonFrom(call));
                    result.success(null);
                    break;
                case "getSidebar":
                    result.success(FlutterLauncherPlugin.currentSidebar());
                    break;
                case "sidebarAction":
                    FlutterLauncherPlugin.dispatchSidebarAction(settingsJsonFrom(call));
                    result.success(null);
                    break;
                case "close":
                    result.success(null);
                    finish();
                    break;
                default:
                    result.notImplemented();
                    break;
            }
        });
        channel = methodChannel;
    }

    /**
     * 取 Dart 传来的动作 JSON。
     *
     * 不能直接用 `call.argument("json")`：`MethodCall.argument` 在 arguments 不是 Map 时
     * 会 **抛 ClassCastException**（不是返回 null），异常从 setMethodCallHandler 里逃出去，
     * Dart 侧只会拿到一个被 catch 掉的 PlatformException，表现就是「点了没反应」。
     * 所以三种形态都认：Map（约定的 `{json: "..."}`）、String（旧版直接传字符串）、JSONObject。
     */
    private static String settingsJsonFrom(MethodCall call) {
        Object args = call.arguments();
        if (args instanceof Map) {
            Object value = ((Map<?, ?>) args).get("json");
            return value instanceof String ? (String) value : null;
        }
        if (args instanceof JSONObject) {
            return ((JSONObject) args).optString("json", null);
        }
        return args instanceof String ? (String) args : null;
    }

    private String buildArgs() {
        try {
            JSONObject args = new JSONObject();
            args.put("route", route);
            args.put("sessionId", sessionId);
            return args.toString();
        } catch (Exception error) {
            Log.w(TAG, "参数序列化失败: " + error.getMessage());
            return "{}";
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
