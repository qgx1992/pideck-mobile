package dev.pideck.mobile;

import android.util.Base64;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * 「把文件保存到手机」的 JS 接口。
 *
 * 只处理文本（桌面端 `/api/file-content` 只给文本，二进制拿不到字节），
 * JS 侧先把 UTF-8 文本转成 base64 再传进来，这里解码后落盘。
 *
 * 真正的落盘规则在 {@link SavedFiles} —— Flutter 侧走 `pideck/flutter_host` 通道的
 * `saveFile` 用的是同一份实现，两边不会分叉。
 */
@CapacitorPlugin(name = "FileSaver")
public class FileSaverPlugin extends Plugin {

    @PluginMethod
    public void save(PluginCall call) {
        String name = SavedFiles.sanitize(call.getString("name", ""));
        String mime = call.getString("mime", "text/plain");
        String data = call.getString("data", "");
        if (name.isEmpty()) {
            call.reject("文件名不能为空");
            return;
        }
        if (mime == null || mime.isEmpty()) mime = "text/plain";

        byte[] bytes;
        try {
            bytes = Base64.decode(data, Base64.NO_WRAP);
        } catch (IllegalArgumentException error) {
            call.reject("内容解码失败");
            return;
        }

        try {
            JSObject result = new JSObject();
            result.put("ok", true);
            result.put("location", SavedFiles.save(getContext(), bytes, name, mime));
            call.resolve(result);
        } catch (Exception error) {
            String message = error.getMessage();
            call.reject(message != null && !message.isEmpty() ? message : "保存失败");
        }
    }
}
