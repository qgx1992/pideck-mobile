package dev.pideck.mobile;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * 把字节落到手机上的公共实现。
 *
 * 抽出来是因为现在有两条入口要用同一套规则：
 *   - WebView 侧的 `FileSaver` 插件（`FileSaverPlugin.save`）；
 *   - Flutter 侧的 `pideck/flutter_host` 通道（`saveFile`）。
 * 两边各写一份 MediaStore 代码，迟早会出现「网页版能保存、Flutter 版报错」这种分叉。
 *
 * 落盘位置（与 0.1.10 起的 WebView 版一致）：
 *   - API 29+：MediaStore 公共「下载/PiDeck」—— 系统文件管理器、其他 App 都能看到，
 *     不需要任何存储权限（分区存储的既定规则）。
 *   - API <29（minSdk 24）：公共下载目录在没有 WRITE_EXTERNAL_STORAGE 时写不了，
 *     所以退到应用专属外部目录（`Android/data/<pkg>/files/Download`），不需要权限但用户
 *     得去那儿找，属于老系统的妥协。
 */
final class SavedFiles {

    private static final String SUBDIR = "PiDeck";

    private SavedFiles() {}

    /** 保存字节，返回给用户看的落地位置。 */
    static String save(Context context, byte[] bytes, String name, String mime) throws Exception {
        String safeName = sanitize(name);
        if (safeName.isEmpty()) throw new IllegalArgumentException("文件名不能为空");
        String type = (mime == null || mime.isEmpty()) ? "text/plain" : mime;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return saveToDownloads(context, bytes, safeName, type);
        }
        return saveToAppStorage(context, bytes, safeName);
    }

    /** 公共下载目录（分区存储）。重名时系统会自动改名成 `xxx (1).txt`。 */
    private static String saveToDownloads(Context context, byte[] bytes, String name, String mime)
            throws Exception {
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
    private static String saveToAppStorage(Context context, byte[] bytes, String name) throws Exception {
        File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) dir = new File(context.getFilesDir(), Environment.DIRECTORY_DOWNLOADS);
        File target = new File(dir, name);
        // 同名时覆盖，避免反复下载堆一串副本
        try (FileOutputStream out = new FileOutputStream(target, false)) {
            out.write(bytes);
            out.flush();
        }
        return target.getAbsolutePath();
    }

    /** 只留文件名本身：去掉路径分隔符与首尾空白，避免写到目录之外。 */
    static String sanitize(String raw) {
        if (raw == null) return "";
        String name = raw.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.trim().replace("..", "");
        return name;
    }
}
