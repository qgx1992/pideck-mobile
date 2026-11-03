package dev.pideck.mobile;

import android.util.Log;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * 「打开桌面网页版」的唯一入口。
 *
 * 这里刻意只做一件事：拿到完整 URL（含 `?token=`）就 startActivity。
 * 页面加载、返回键、文件选择全在 {@link DesktopWebActivity} 里，这样
 * 将来换成「本地反代 + 内置资源」时（阶段 2），只要换 URL 与上游，
 * JS 侧调用不用动。
 */
@CapacitorPlugin(name = "DesktopWeb")
public class DesktopWebPlugin extends Plugin {

    private static final String TAG = "PiDeckDesktopWeb";

    @PluginMethod
    public void open(PluginCall call) {
        String url = call.getString("url");
        String title = call.getString("title", "PiDeck");
        if (url == null || url.isEmpty()) {
            call.reject("url 不能为空");
            return;
        }
        try {
            getActivity().startActivity(DesktopWebActivity.intentFor(getContext(), url, title));
            JSObject result = new JSObject();
            result.put("opened", true);
            call.resolve(result);
        } catch (Exception error) {
            Log.w(TAG, "启动桌面网页版失败: " + error);
            call.reject("启动失败: " + error.getMessage());
        }
    }

    /** 桌面网页版是否可用（只有原生壳里才为 true，浏览器里调用会直接是 undefined）。 */
    @PluginMethod
    public void available(PluginCall call) {
        JSObject result = new JSObject();
        result.put("available", true);
        call.resolve(result);
    }
}
