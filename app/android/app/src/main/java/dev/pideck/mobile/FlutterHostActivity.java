package dev.pideck.mobile;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

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
 *   pickImage()        → 弹系统相册，回 `{data, mimeType, width, height, bytes, name}`
 *                        （`data` 是裸 base64，已按 1600px / 0.82 压过）
 *   saveFile({name, mime, data}) → 存到「下载/PiDeck」，回 `{location}`
 *   openWebSheet(name) → 关掉本页并让 WebView 打开指定弹层（扫码配对这类只有
 *                        WebView 侧有实现的流程走这条，别在 Flutter 里重写一遍）
 *   close()            → 关掉 Flutter 页，回到 WebView
 *
 * 反向（原生 → Dart）：`setSettings(json)` / `setChat(json)` / `showNotice(text)`，由
 * FlutterLauncherPlugin 在 JS 推数据或要求提示时调进来。
 */
public class FlutterHostActivity extends FlutterFragmentActivity {

    static final String EXTRA_ROUTE = "pideckFlutterRoute";
    static final String EXTRA_SESSION_ID = "pideckFlutterSessionId";
    /** 当前活动端点的 baseUrl / token：聊天页的 Dart 运行时自己连服务端要用。 */
    static final String EXTRA_ENDPOINT = "pideckFlutterEndpoint";
    static final String EXTRA_TOKEN = "pideckFlutterToken";
    /** 是否让 Dart 自己跑聊天数据面（帧流 / 历史 / 发送）。老包没有这些 extra → false。 */
    static final String EXTRA_NATIVE_RUNTIME = "pideckFlutterNativeRuntime";

    private static final String TAG = "PiDeckFlutterHost";
    private static final String CHANNEL = "pideck/flutter_host";

    /** 当前活着的宿主 Activity（插件要能反向调用它；同一时刻只可能有一个）。 */
    static volatile FlutterHostActivity current;

    private MethodChannel channel;

    /** 选图是异步的：结果先存在这里，选完（或取消）再回给 Dart。 */
    private MethodChannel.Result pendingPick;
    private ActivityResultLauncher<String> imagePicker;

    private String route = "";
    private String sessionId = "";
    private String endpoint = "";
    private String token = "";
    private boolean nativeRuntime = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 孤儿页处理：进程被系统回收后，恢复任务栈时会先重建**栈顶**的 Activity，
        // 而栈顶通常是这个 Flutter 页 —— 可这时 WebView 宿主（MainActivity）根本没起，
        // 没有任何人给它喂快照：用户看到的是一个空壳页面（没有会话、没有标题、侧栏全空），
        // 按返回键还会走 `exitApp` 把 App 直接退掉，等于再也回不到主界面。
        //
        // 判据用插件实例是否存在（由 MainActivity 建桥时 load）：null = 冷启动重建。
        // 这时把主界面拉起来再 finish 自己，让启动流程照常从头走一遍。
        if (!FlutterLauncherPlugin.webViewAlive()) {
            Log.i(TAG, "冷启动恢复到 Flutter 页，但 WebView 宿主不在：拉回主界面");
            Intent main = new Intent(this, MainActivity.class);
            main.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(main);
            finish();
            return;
        }

        // 系统相册选择器（GetContent 不需要任何权限；API 33+ 系统会自动换成照片选择器）
        imagePicker = registerForActivityResult(
                new ActivityResultContracts.GetContent(), this::handlePickedImage);
        // getIntent() 在 onCreate 之后可能被替换（singleTask 的重入），这里只取一次
        Intent intent = getIntent();
        route = safe(intent.getStringExtra(EXTRA_ROUTE));
        sessionId = safe(intent.getStringExtra(EXTRA_SESSION_ID));
        endpoint = safe(intent.getStringExtra(EXTRA_ENDPOINT));
        token = safe(intent.getStringExtra(EXTRA_TOKEN));
        nativeRuntime = intent.getBooleanExtra(EXTRA_NATIVE_RUNTIME, false);
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
                case "pickImage":
                    requestPick(result);
                    break;
                case "saveFile":
                    saveFileAsync(call, result);
                    break;
                case "startRunWatch": {
                    // Dart 侧自己发提问时，必须由它把后台看护拉起来：
                    // 用户常常发完就上滑回桌面，等 WebView 的 SSE 推到 running 再启动
                    // 前台服务时，进程可能已经被冻结（Android 12+ 也不允许后台启动）。
                    Object args = call.arguments();
                    String baseUrl = "";
                    String token = "";
                    String titles = "{}";
                    if (args instanceof Map) {
                        Map<?, ?> map = (Map<?, ?>) args;
                        baseUrl = stringOf(map.get("baseUrl"));
                        token = stringOf(map.get("token"));
                        Object rawTitles = map.get("titles");
                        if (rawTitles instanceof Map) {
                            titles = new JSONObject((Map<?, ?>) rawTitles).toString();
                        }
                    }
                    if (!baseUrl.isEmpty() && !token.isEmpty()) {
                        RunWatchService.start(getApplicationContext(), baseUrl, token, titles);
                    }
                    result.success(null);
                    break;
                }
                case "stopRunWatch":
                    RunWatchService.stop(getApplicationContext());
                    result.success(null);
                    break;
                case "openWebSheet": {
                    Object args = call.arguments();
                    Object raw = args instanceof Map ? ((Map<?, ?>) args).get("name") : null;
                    String sheet = stringOf(raw);
                    result.success(null);
                    // 先让 JS 把弹层准备好，再关自己 —— 反过来的话弹层会在没人看的页面上播动画
                    FlutterLauncherPlugin.dispatchWebSheet(sheet);
                    finish();
                    break;
                }
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

    /** Dart 请求选图：弹系统相册，选完（或取消）再把图回给它。 */
    private void requestPick(MethodChannel.Result result) {
        if (pendingPick != null) {
            result.error("pickImage", "已经有一个选图正在进行", null);
            return;
        }
        if (imagePicker == null) {
            result.error("pickImage", "选图器没有初始化", null);
            return;
        }
        pendingPick = result;
        try {
            imagePicker.launch("image/*");
        } catch (Exception error) {
            pendingPick = null;
            result.error("pickImage", "打不开相册: " + error.getMessage(), null);
        }
    }

    /** 相册回调：取消就回 null（Dart 侧当成「用户放弃」）。 */
    private void handlePickedImage(Uri uri) {
        MethodChannel.Result pending = pendingPick;
        pendingPick = null;
        if (pending == null) return;
        if (uri == null) {
            pending.success(null);
            return;
        }
        // 解码 + 压缩在主线程会卡几百毫秒，放后台线程
        new Thread(() -> {
            try {
                Map<String, Object> image = FlutterFiles.readImage(getApplicationContext(), uri);
                runOnUiThread(() -> pending.success(image));
            } catch (Exception error) {
                String message = error.getMessage();
                runOnUiThread(() -> pending.error(
                        "pickImage",
                        message == null || message.isEmpty() ? "读取图片失败" : message,
                        null));
            }
        }, "pideck-pick-image").start();
    }

    /** Dart 请求保存文件：解码 + 落盘放后台线程。 */
    private void saveFileAsync(MethodCall call, MethodChannel.Result result) {
        Object args = call.arguments();
        if (!(args instanceof Map)) {
            result.error("saveFile", "参数缺失", null);
            return;
        }
        Map<?, ?> map = (Map<?, ?>) args;
        String name = stringOf(map.get("name"));
        String mime = stringOf(map.get("mime"));
        String data = stringOf(map.get("data"));
        if (name.isEmpty()) {
            result.error("saveFile", "文件名不能为空", null);
            return;
        }
        if (data.isEmpty()) {
            result.error("saveFile", "内容为空", null);
            return;
        }
        final String type = mime.isEmpty() ? "text/plain" : mime;
        new Thread(() -> {
            try {
                byte[] bytes = Base64.decode(data, Base64.NO_WRAP);
                String location = SavedFiles.save(getApplicationContext(), bytes, name, type);
                Map<String, Object> payload = new HashMap<>();
                payload.put("location", location);
                runOnUiThread(() -> result.success(payload));
            } catch (IllegalArgumentException error) {
                runOnUiThread(() -> result.error("saveFile", "内容解码失败", null));
            } catch (Exception error) {
                String message = error.getMessage();
                runOnUiThread(() -> result.error(
                        "saveFile",
                        message == null || message.isEmpty() ? "保存失败" : message,
                        null));
            }
        }, "pideck-save-file").start();
    }

    private static String stringOf(Object value) {
        return value == null ? "" : String.valueOf(value);
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
            // 端点信息只给「自己跑数据面」的页面用（聊天页）。设置页仍走 getSettings
            // 里那份 connection，两处来源不同：这里是**当前真正连着**的那台。
            args.put("endpoint", endpoint);
            args.put("token", token);
            args.put("nativeRuntime", nativeRuntime);
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
