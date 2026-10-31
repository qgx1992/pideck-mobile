package dev.pideck.mobile;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * 「把文件保存到手机」的 JS 接口。
 *
 * 只处理文本（桌面端 `/api/file-content` 只给文本，二进制拿不到字节），
 * JS 侧先把 UTF-8 文本转成 base64 再传进来，这里解码后落盘。
 *
 * 落盘位置：
 *   - API 29+：MediaStore 的公共「下载/PiDeck」—— 系统文件管理器、其他 App 都能看到，
 *     不需要任何存储权限（分区存储的既定规则）。
 *   - API <28（minSdk 23）：公共下载目录在没有 WRITE_EXTERNAL_STORAGE 时写不了，
 *     所以退到应用专属外部目录（`Android/data/<pkg>/files/Download`），不需要权限但用户
 *     得去那儿找，属于老系统的妥协。
 */
@CapacitorPlugin(name = "FileSaver")
public class FileSaverPlugin extends Plugin {

    private static final String SUBDIR = "PiDeck";

    @PluginMethod
    public void save(PluginCall call) {
        String rawName = call.getString("name", "");
        String mime = call.getString("mime", "text/plain");
        String data = call.getString("data", "");
        String name = sanitize(rawName);
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                String location = saveToDownloads(bytes, name, mime);
                JSObject result = new JSObject();
                result.put("ok", true);
                result.put("location", location);
                call.resolve(result);
            } else {
                String location = saveToAppStorage(bytes, name);
                JSObject result = new JSObject();
                result.put("ok", true);
                result.put("location", location);
                call.resolve(result);
            }
        } catch (Exception error) {
            String message = error.getMessage();
            call.reject(message != null && !message.isEmpty() ? message : "保存失败");
        }
    }

    /** 公共下载目录（分区存储）。重名时系统会自动改名成 `xxx (1).txt`。 */
    private String saveToDownloads(byte[] bytes, String name, String mime) throws Exception {
        Context context = getContext();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + SUBDIR);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IllegalStateException("系统没有返回下载条目");
        try {
            try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IllegalStateException("打不开下载条目");
                out.write(bytes);
                out.flush();
            }
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            context.getContentResolver().update(uri, values, null, null);
        } catch (Exception error) {
            // 写入失败别留下一个 0 字节的空条目
            try {
                context.getContentResolver().delete(uri, null, null);
            } catch (Exception ignored) {
                // 删不掉就算了，别把原始异常盖掉
            }
            throw error;
        }
        return Environment.DIRECTORY_DOWNLOADS + "/" + SUBDIR + "/" + name;
    }

    /** 老系统兜底：应用专属外部目录，无需权限。 */
    private String saveToAppStorage(byte[] bytes, String name) throws Exception {
        File dir = getContext().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) dir = new File(getContext().getFilesDir(), Environment.DIRECTORY_DOWNLOADS);
        File target = new File(dir, name);
        // 同名时覆盖，避免反复下载堆一串副本
        try (FileOutputStream out = new FileOutputStream(target, false)) {
            out.write(bytes);
            out.flush();
        }
        return target.getAbsolutePath();
    }

    /** 只留文件名本身：去掉路径分隔符与首尾空白，避免写到目录之外。 */
    private static String sanitize(String raw) {
        if (raw == null) return "";
        String name = raw.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.trim().replace("..", "");
        if (name.isEmpty()) return "";
        return name;
    }
}
