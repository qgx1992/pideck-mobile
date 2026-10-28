package dev.pideck.mobile;

import android.graphics.Color;
import android.os.Build;
import android.view.Window;

import androidx.core.view.WindowCompat;
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
}
