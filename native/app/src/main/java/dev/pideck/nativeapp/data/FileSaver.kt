package dev.pideck.nativeapp.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * 把文本落到手机「下载/PiDeck」。
 *
 * 两条路：Android 10 起走 MediaStore（不需要任何权限，文件对其他 App 可见）；更老的系统只能直接写
 * 公共下载目录（需要 `WRITE_EXTERNAL_STORAGE`，已在 manifest 里用 `maxSdkVersion=28` 限定）。
 */
object FileSaver {

    const val SUB_DIR = "PiDeck"

    /** @return 给用户看的落地路径，例如「下载/PiDeck/pideck-xxx.html」。 */
    fun saveText(context: Context, fileName: String, mime: String, content: String): String {
        val bytes = content.toByteArray(Charsets.UTF_8)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, fileName, mime, bytes)
        } else {
            saveToLegacyDir(fileName, bytes)
        }
    }

    private fun saveViaMediaStore(context: Context, fileName: String, mime: String, bytes: ByteArray): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + SUB_DIR)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("系统没有给出可写的下载目录")
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: throw IllegalStateException("写不进刚创建的文件")
        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
            null,
            null,
        )
        return "${Environment.DIRECTORY_DOWNLOADS}/$SUB_DIR/$fileName"
    }

    private fun saveToLegacyDir(fileName: String, bytes: ByteArray): String {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SUB_DIR)
        if (!dir.exists()) dir.mkdirs()
        val target = uniqueFile(dir, fileName)
        target.writeBytes(bytes)
        return "${Environment.DIRECTORY_DOWNLOADS}/$SUB_DIR/${target.name}"
    }

    private fun uniqueFile(dir: File, fileName: String): File {
        val base = fileName.substringBeforeLast('.', fileName)
        val ext = fileName.substringAfterLast('.', "")
        var candidate = File(dir, fileName)
        var index = 2
        while (candidate.exists()) {
            val suffix = if (ext.isEmpty()) "" else ".$ext"
            candidate = File(dir, "$base ($index)$suffix")
            index++
        }
        return candidate
    }
}
