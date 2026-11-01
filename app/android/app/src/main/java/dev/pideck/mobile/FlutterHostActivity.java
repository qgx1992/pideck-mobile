package dev.pideck.mobile;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import org.json.JSONObject;

import io.flutter.embedding.android.FlutterFragmentActivity;
import io.flutter.embedding.engine.FlutterEngine;
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
 *   getArgs() → {"route": "...", "sessionId": "..."}
 *   close()   → 关掉 Flutter 页，回到 WebView
 */
public class FlutterHostActivity extends FlutterFragmentActivity {

    static final String EXTRA_ROUTE = "pideckFlutterRoute";
    static final String EXTRA_SESSION_ID = "pideckFlutterSessionId";

    private static final String TAG = "PiDeckFlutterHost";
    private static final String CHANNEL = "pideck/flutter_host";

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
        FlutterLauncherPlugin.notifyPageVisibility(true);
    }

    @Override
    protected void onStop() {
        FlutterLauncherPlugin.notifyPageVisibility(false);
        super.onStop();
    }

    @Override
    public void configureFlutterEngine(FlutterEngine flutterEngine) {
        super.configureFlutterEngine(flutterEngine);
        new MethodChannel(flutterEngine.getDartExecutor().getBinaryMessenger(), CHANNEL)
                .setMethodCallHandler((call, result) -> {
                    switch (call.method) {
                        case "getArgs":
                            result.success(buildArgs());
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
