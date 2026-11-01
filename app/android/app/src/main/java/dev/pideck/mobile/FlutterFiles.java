package dev.pideck.mobile;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Flutter 侧「选图 → 压缩 → base64」的实现。
 *
 * 为什么不在 Dart 里做：
 *   - 解码一张 4000×3000 的 JPEG 在 Dart 里要整张进内存再缩放，手机上很容易 OOM；
 *     Android 的 `BitmapFactory.Options.inSampleSize` 是**解码时**就降采样，峰值内存差一个量级。
 *   - 压缩参数要和 WebView 版一致（`app/src/ui/image-attach-model.ts` 的
 *     `MAX_EDGE = 1600` / `JPEG_QUALITY = 0.82` / `MAX_IMAGES = 4`），两边各写一份迟早不同。
 *
 * 走的是系统相册选择器（`ACTION_PICK_IMAGES`，API 33+；更老的系统退 `ACTION_GET_CONTENT`），
 * 所以**不需要任何存储权限**，也不引第三方选图库。
 */
final class FlutterFiles {

    /** 压缩后长边上限，与 WebView 侧 `MAX_EDGE` 一致 */
    static final int MAX_EDGE = 1600;
    /** JPEG 质量，与 WebView 侧 `JPEG_QUALITY` 一致 */
    static final int JPEG_QUALITY = 82;
    /** 单张原图体积上限，与 WebView 侧 `MAX_IMAGE_FILE_BYTES` 一致 */
    static final long MAX_FILE_BYTES = 8L * 1024 * 1024;

    private FlutterFiles() {}

    /**
     * 读一张图并压成 JPEG。
     *
     * 返回 `{data, mimeType, width, height, bytes, name}`：
     * `data` 是**不带 data URL 前缀**的 base64，宿主侧拼 `data:image/jpeg;base64,` 就能用。
     */
    static Map<String, Object> readImage(Context context, Uri uri) throws Exception {
        String name = displayName(context, uri);
        long declaredSize = declaredBytes(context, uri);
        if (declaredSize > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("图片太大（" + mb(declaredSize) + "），请压缩到 8 MB 以内");
        }

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = open(context, uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IllegalArgumentException("这个文件不是图片");
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight);
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap decoded;
        try (InputStream in = open(context, uri)) {
            decoded = BitmapFactory.decodeStream(in, null, options);
        }
        if (decoded == null) throw new IllegalArgumentException("这张图读不出来（可能是不支持的格式）");

        Bitmap scaled = scaleDown(decoded);
        if (scaled != decoded) decoded.recycle();
        Bitmap rotated = rotateByExif(context, uri, scaled);
        if (rotated != scaled) scaled.recycle();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        rotated.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out);
        byte[] jpeg = out.toByteArray();
        Map<String, Object> result = new HashMap<>();
        result.put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP));
        result.put("mimeType", "image/jpeg");
        result.put("width", rotated.getWidth());
        result.put("height", rotated.getHeight());
        result.put("bytes", jpeg.length);
        result.put("name", name);
        rotated.recycle();
        return result;
    }

    private static InputStream open(Context context, Uri uri) throws Exception {
        InputStream in = context.getContentResolver().openInputStream(uri);
        if (in == null) throw new IllegalArgumentException("打不开这个文件");
        return in;
    }

    private static String displayName(Context context, Uri uri) {
        try (android.database.Cursor cursor =
                context.getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.isEmpty()) return name;
            }
        } catch (Exception ignored) {
            // 拿不到名字不影响保存，退回一个通用名
        }
        return "image.jpg";
    }

    private static long declaredBytes(Context context, Uri uri) {
        try (android.database.Cursor cursor =
                context.getContentResolver().query(uri, new String[] {OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0);
        } catch (Exception ignored) {
            // 查不到体积就交给后面的解码去兜底
        }
        return 0;
    }

    /** 解码时降采样的倍数：只要保证解码结果不小于目标长边即可。 */
    private static int sampleSize(int width, int height) {
        int longest = Math.max(width, height);
        int sample = 1;
        while (longest / (sample * 2) >= MAX_EDGE) sample *= 2;
        return sample;
    }

    private static Bitmap scaleDown(Bitmap source) {
        int longest = Math.max(source.getWidth(), source.getHeight());
        if (longest <= MAX_EDGE) return source;
        float ratio = (float) MAX_EDGE / longest;
        int width = Math.max(1, Math.round(source.getWidth() * ratio));
        int height = Math.max(1, Math.round(source.getHeight() * ratio));
        return Bitmap.createScaledBitmap(source, width, height, true);
    }

    /**
     * 按 EXIF 把方向掰正。
     *
     * 浏览器里 canvas 会自动应用 EXIF 方向，`BitmapFactory` 不会 —— 不处理的话
     * 竖拍的照片发出去是躺着的（WebView 版没这个问题，只有 Flutter 版有，很容易漏）。
     */
    private static Bitmap rotateByExif(Context context, Uri uri, Bitmap bitmap) {
        int orientation;
        try (InputStream in = open(context, uri)) {
            orientation = new ExifInterface(in).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (Exception ignored) {
            return bitmap;
        }
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.postRotate(90);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.postRotate(180);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.postRotate(270);
                break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.postScale(1, -1);
                break;
            default:
                return bitmap;
        }
        try {
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        } catch (Exception ignored) {
            return bitmap;
        }
    }

    private static String mb(long bytes) {
        return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
