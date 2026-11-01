package dev.pideck.mobile;

import android.content.Intent;
import android.util.Log;

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

    private static final String TAG = "PiDeckFlutterHost";

    /** 事件名：Flutter 页打开/关闭。JS 侧用它把「WebView 被盖住」和「App 真在后台」区分开。 */
    static final String EVENT_PAGE_VISIBILITY = "pageVisibility";

    /**
     * 事件名：Flutter 页上的一次操作，需要 WebView 去执行。
     *
     * 设置页是「视图迁移」：状态仍在 WebView（主题/通知/设备列表本来就存在那儿），
     * Flutter 只负责画，用户点了什么原样回传，由 `app/src/main.ts` 的
     * `dispatchFlutterSettingsAction` 分发到与 Web 版同一套 handler。
     */
    static final String EVENT_SETTINGS_ACTION = "settingsAction";

    /**
     * 事件名：聊天页上的一次操作（发消息、取消、切模型…）。
     *
     * 与 `settingsAction` 同构，但分开两条：聊天页的帧流很密，混在一条通道里
     * 排查时看不出是谁的动作；分开后 JS 侧两个订阅各管各的。
     */
    static final String EVENT_CHAT_ACTION = "chatAction";

    /**
     * 事件名：侧栏（Drawer）上的一次操作。
     *
     * 侧栏快照的分段/折叠/搜索规则全在 WebView（`ui/sidebar-model.ts` 与
     * `ui/sidebar-tabs-model.ts`），Flutter 只画。所以它只需要“给数据 + 收回动作”两个方法。
     */
    static final String EVENT_SIDEBAR_ACTION = "sidebarAction";

    /**
     * 事件名：Flutter 页要求「关掉我，并让 WebView 开某个弹层」。
     *
     * 现在只有扫码配对用这条：`BarcodeScanner.scan()` 是 Google 原生全屏扫描器，
     * 它靠 Capacitor bridge 接 ActivityResult，在 FlutterHostActivity 里拿不到结果，
     * 重写一份 ML Kit + CameraX 不值得 —— 关页回 WebView 弹原来的面板更省事也不分叉。
     */
    static final String EVENT_WEB_SHEET = "webSheet";

    /** Capacitor 只创建一个插件实例，这里存一份给 FlutterHostActivity 回传生命周期用。 */
    private static FlutterLauncherPlugin instance;

    /**
     * 最近一次由 JS 推来的 SettingsData JSON。
     *
     * 为什么缓存：JS 在 startActivity **之前**就先 push 一份，Flutter 首帧直接同步取走，
     * 不用等一次「Dart 要数据 → 原生 → WebView → 原生 → Dart」的往返，避免白屏。
     */
    private static volatile String lastSettingsJson = "{}";

    /**
     * 最近一次由 JS 推来的聊天页视图模型 JSON。
     *
     * 比设置页更需要缓存：聊天页每次打开都对应不同会话，Dart 侧首帧要立刻有东西可画，
     * 等一次往返就会看到空白列表。
     */
    private static volatile String lastChatJson = "{}";

    /** 最近一次由 JS 推来的侧栏快照 JSON（打开 Drawer 时立刻有东西可画）。 */
    private static volatile String lastSidebarJson = "{}";

    @Override
    public void load() {
        instance = this;
    }

    @Override
    protected void handleOnDestroy() {
        if (instance == this) instance = null;
    }

    static String currentSettings() {
        return lastSettingsJson;
    }

    static String currentChat() {
        return lastChatJson;
    }

    static String currentSidebar() {
        return lastSidebarJson;
    }

    /** Flutter 页要一个只有 WebView 侧实现的弹层（扫码配对等）。 */
    static void dispatchWebSheet(String name) {
        FlutterLauncherPlugin plugin = instance;
        if (plugin == null) return;
        JSObject data = new JSObject();
        data.put("name", name == null ? "" : name);
        try {
            plugin.notifyListeners(EVENT_WEB_SHEET, data);
        } catch (Exception error) {
            Log.w(TAG, "webSheet 下发失败: " + error.getMessage());
        }
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

    /**
     * Flutter 页把动作回传给 WebView（`sendSettingsAction`）。
     *
     * 转发成 JS 事件而不是直接在这里处理：处理器（切主题、切设备、调接口）
     * 全在 `app/src/main.ts`，原生这边不该复制一份业务逻辑。
     */
    static void dispatchSettingsAction(String json) {
        FlutterLauncherPlugin plugin = instance;
        if (plugin == null) return;
        try {
            JSObject data = new JSObject();
            data.put("json", json == null ? "{}" : json);
            plugin.notifyListeners(EVENT_SETTINGS_ACTION, data);
        } catch (Exception ignored) {
            /* WebView 已经销毁时忽略 */
        }
    }

    /** Flutter 聊天页把动作回传给 WebView（`sendChatAction`）。 */
    static void dispatchChatAction(String json) {
        FlutterLauncherPlugin plugin = instance;
        if (plugin == null) return;
        try {
            JSObject data = new JSObject();
            data.put("json", json == null ? "{}" : json);
            plugin.notifyListeners(EVENT_CHAT_ACTION, data);
        } catch (Exception ignored) {
            /* WebView 已经销毁时忽略 */
        }
    }

    /** Flutter 侧栏把动作回传给 WebView（`sendSidebarAction`）。 */
    static void dispatchSidebarAction(String json) {
        FlutterLauncherPlugin plugin = instance;
        if (plugin == null) return;
        try {
            JSObject data = new JSObject();
            data.put("json", json == null ? "{}" : json);
            plugin.notifyListeners(EVENT_SIDEBAR_ACTION, data);
        } catch (Exception ignored) {
            /* WebView 已经销毁时忽略 */
        }
    }

    /** JS 推一份最新的设置数据（`pushFlutterSettings`）。 */
    @PluginMethod
    public void pushSettings(PluginCall call) {
        String json = call.getString("json", "{}");
        if (json == null || json.isEmpty()) json = "{}";
        lastSettingsJson = json;
        FlutterHostActivity.pushSettingsToFlutter(json);
        call.resolve();
    }

    /** JS 推一份最新的聊天页视图模型（`pushFlutterChat`）。 */
    @PluginMethod
    public void pushChat(PluginCall call) {
        String json = call.getString("json", "{}");
        if (json == null || json.isEmpty()) json = "{}";
        lastChatJson = json;
        FlutterHostActivity.pushChatToFlutter(json);
        call.resolve();
    }

    /** JS 推一份最新的侧栏快照（`pushFlutterSidebar`）。 */
    @PluginMethod
    public void pushSidebar(PluginCall call) {
        String json = call.getString("json", "{}");
        if (json == null || json.isEmpty()) json = "{}";
        lastSidebarJson = json;
        FlutterHostActivity.pushSidebarToFlutter(json);
        call.resolve();
    }

    /** JS 让 Flutter 页弹一句提示（WebView 的 toast 在 Flutter 页上根本看不见）。 */
    @PluginMethod
    public void pushNotice(PluginCall call) {
        String text = call.getString("text", "");
        if (text != null && !text.isEmpty()) FlutterHostActivity.pushNoticeToFlutter(text);
        call.resolve();
    }

    /** JS 主动关掉 Flutter 页（比如 handler 里要切到别的界面）。 */
    @PluginMethod
    public void closePage(PluginCall call) {
        FlutterHostActivity activity = FlutterHostActivity.current;
        if (activity != null) activity.finish();
        call.resolve();
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
