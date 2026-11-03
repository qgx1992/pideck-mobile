package dev.pideck.mobile;

import android.app.Activity;
import android.content.Intent;
import android.provider.Settings;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Window;
import android.webkit.WebView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * 导航栏（底部手势小横条）配色。
 *
 * 为什么需要自己写：@capacitor/status-bar 只处理状态栏，没有导航栏 API。
 * 而 Android 15 起 navigationBarColor 在强制 edge-to-edge 下会被忽略——
 * 本项目已在 styles.xml 里通过 windowOptOutEdgeToEdgeEnforcement 退出该强制行为，
 * 所以这里设置的颜色与图标明暗才会生效。
 *
 * 同时兼一个「用系统浏览器打开外链」的入口：WebView 里 window.open(_blank)
 * 要么被吞掉、要么把会话顶掉，所以由 JS 拦下改走 Intent。
 *
 * 图标明暗用 WindowInsetsControllerCompat.setAppearanceLightNavigationBars：
 *   true  = 浅色背景 → 深色图标
 *   false = 深色背景 → 浅色图标
 */
@CapacitorPlugin(name = "NavigationBar")
public class NavigationBarPlugin extends Plugin {

    @PluginMethod
    public void setColor(PluginCall call) {
        final String color = call.getString("color", "#000000");
        // lightIcons = true 表示「深色背景上要浅色图标」
        final boolean lightIcons = Boolean.TRUE.equals(call.getBoolean("lightIcons", false));

        getActivity().runOnUiThread(() -> {
            try {
                Window window = getActivity().getWindow();
                window.setNavigationBarColor(Color.parseColor(color));

                // API 29+ 系统会给导航栏叠一层半透明遮罩，会把我们的颜色冲淡；
                // 关掉它，颜色才与 App 背景真正一致。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.setNavigationBarContrastEnforced(false);
                }

                WindowInsetsControllerCompat controller =
                        WindowCompat.getInsetsController(window, window.getDecorView());
                controller.setAppearanceLightNavigationBars(!lightIcons);

                JSObject result = new JSObject();
                result.put("color", color);
                result.put("lightIcons", lightIcons);
                call.resolve(result);
            } catch (Exception error) {
                call.reject("设置导航栏颜色失败: " + error.getMessage(), error);
            }
        });
    }

    /** 用系统浏览器 / 对应 App 打开链接（http/https/mailto…）。 */
    @PluginMethod
    public void openUrl(PluginCall call) {
        final String url = call.getString("url", "");
        if (url == null || url.isEmpty()) {
            call.reject("url required");
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
            call.resolve();
        } catch (Exception error) {
            String message = error.getMessage();
            call.reject(message != null && !message.isEmpty() ? message : "没有可处理该链接的 App");
        }
    }

    /** 整块屏幕高度（不受输入法影响；`getDefaultDisplay` 会在分屏/弹键盘时缩水）。 */
    private static int screenHeight(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return activity.getWindowManager().getCurrentWindowMetrics().getBounds().height();
        }
        DisplayMetrics metrics = new DisplayMetrics();
        activity.getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
        return metrics.heightPixels;
    }

    /**
     * 系统栏「吃掉了多少内容区」（dp）。
     *
     * 不直接返回系统栏高度：本项目在 styles.xml 里用 `windowOptOutEdgeToEdgeEnforcement`
     * 想退出 Android 15 的强制 edge-to-edge，但部分 ROM 直接忽略——两种布局下 WebView 的
     * 几何不同（退出成功：WebView 只占系统栏之间，重叠为 0；被忽略：WebView 铺满全屏，
     * 底部手势条压住输入框）。所以这里直接量 WebView 与系统栏的真实重叠，交给 JS 只补差值。
     */
    @PluginMethod
    public void getInsets(PluginCall call) {
        final Activity activity = getActivity();
        final WebView webView = getBridge() == null ? null : getBridge().getWebView();
        if (activity == null || webView == null) {
            call.reject("页面还没准备好");
            return;
        }
        activity.runOnUiThread(() -> {
            try {
                WindowInsetsCompat compat = ViewCompat.getRootWindowInsets(activity.getWindow().getDecorView());
                Insets bars = compat == null ? Insets.NONE : compat.getInsets(WindowInsetsCompat.Type.systemBars());

                int[] location = new int[2];
                webView.getLocationOnScreen(location);
                int viewTop = location[1];
                int viewBottom = viewTop + webView.getHeight();
                int screenHeight = screenHeight(activity);

                float density = activity.getResources().getDisplayMetrics().density;
                // 只报「WebView 真被系统栏压住」的部分：退出 edge-to-edge 成功时它是 0，
                // 被 ROM 忽略（或 Android 16 强制 e2e）时它等于系统栏高度。
                float overlapTop = Math.max(0, bars.top - viewTop) / density;
                float overlapBottom = Math.max(0, viewBottom - (screenHeight - bars.bottom)) / density;

                JSObject result = new JSObject();
                result.put("top", overlapTop);
                result.put("bottom", overlapBottom);
                result.put("statusBar", bars.top / density);
                result.put("navBar", bars.bottom / density);
                result.put("screenHeight", screenHeight / density);
                call.resolve(result);
            } catch (Exception error) {
                call.reject("读取系统栏高度失败: " + error.getMessage(), error);
            }
        });
    }

    /**
     * 打开本应用的「通知」系统设置页。
     *
     * Android 13+ 的 POST_NOTIFICATIONS 被拒过两次之后系统不再弹框，
     * 唯一的出口就是这一页——所以启动页的「去设置」按钮直接调它，
     * 免得用户自己翻三层设置还找不到。
     */
    @PluginMethod
    public void openNotificationSettings(PluginCall call) {
        try {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, getContext().getPackageName());
            if (getActivity() != null) {
                getActivity().startActivity(intent);
            } else {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(intent);
            }
            call.resolve();
        } catch (Exception error) {
            String message = error.getMessage();
            call.reject(message != null && !message.isEmpty() ? message : "打不开通知设置");
        }
    }
}
