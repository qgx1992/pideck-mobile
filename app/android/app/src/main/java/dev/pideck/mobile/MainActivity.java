package dev.pideck.mobile;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    /** 点通知进来时要跳转的会话（一次性，JS 取走后清空） */
    static final String EXTRA_SESSION_ID = "pideckSessionId";
    private static volatile String pendingSessionId = null;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // 自定义插件必须在 super.onCreate 之前注册，否则 JS 侧调用会报 "not implemented"
        registerPlugin(NavigationBarPlugin.class);
        registerPlugin(RunWatchPlugin.class);
        registerPlugin(FileSaverPlugin.class);
        // SSE 走原生栈：CapacitorHttp 不支持流式响应体，所以自己读流
        // （见 platform/sse-stream.ts 与 SsePlugin.java 的注释）
        registerPlugin(SsePlugin.class);
        // WebView → Flutter 页的开关（add-to-app）
        registerPlugin(FlutterLauncherPlugin.class);
        // 「用桌面网页版」：全屏裸 WebView 打开桌面端自带的那份 UI
        registerPlugin(DesktopWebPlugin.class);
        super.onCreate(savedInstanceState);

        // 扫码（BarcodeScanner.startScan）时相机预览铺在 WebView 之下，
        // WebView 背景不透明就只能看到一片纯色，取景框里是空的。
        // 页面自己的背景色在 body 上，不受影响。
        if (bridge != null && bridge.getWebView() != null) {
            bridge.getWebView().setBackgroundColor(Color.TRANSPARENT);
        }

        captureSession(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // singleTask：从通知再次进来走这里，必须手动换掉 getIntent()
        setIntent(intent);
        captureSession(intent);
    }

    @Override
    public void onResume() {
        super.onResume();
        // 可见性不再在这里维护：加了 FlutterHostActivity 之后「一个 Activity」的假设不成立，
        // 统一搬到 PiDeckApp 的 ActivityLifecycleCallbacks（按 started 计数）
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    private void captureSession(Intent intent) {
        if (intent == null) return;
        String sessionId = intent.getStringExtra(EXTRA_SESSION_ID);
        if (sessionId != null && !sessionId.isEmpty()) {
            pendingSessionId = sessionId;
            // 清掉 extra，避免旋转屏幕等重建时又跳一次
            intent.removeExtra(EXTRA_SESSION_ID);
        }
    }

    /** JS 取走待跳转的会话 id。 */
    static String takePendingSessionId() {
        String value = pendingSessionId;
        pendingSessionId = null;
        return value;
    }
}
