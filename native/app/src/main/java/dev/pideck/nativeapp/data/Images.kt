package dev.pideck.nativeapp.data

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * 待发送的图片。
 *
 * 服务端（桌面端 `/api/chat`）只从 user 消息的 `file` parts 提取图片，而且有硬限制：
 * **最多 4 张、每张 base64 ≤ 2MB、必须是 image/\*** —— 超了的会被静默丢掉（不是报错）。
 * 所以这里先压到 1568px / JPEG q85 再编码，和桌面网页版 `webImageCompress.ts` 同一套参数。
 */
data class PendingImage(
    val id: String,
    /** `data:image/jpeg;base64,…`，直接作为 file part 的 url 发出去 */
    val dataUrl: String,
    /** 只用于本地缩略图，不进请求体 */
    val bitmap: Bitmap?,
    val label: String,
)

object ImagePrep {

    const val MAX_COUNT = 4
    /** 服务端上限 2MB，这里留一点余量（base64 之后才是它比对的长度）。 */
    const val MAX_BASE64_LENGTH = 2 * 1024 * 1024 - 4096
    private const val MAX_DIMENSION = 1568
    private const val JPEG_QUALITY = 85

    /** 从相册/相机返回的 Uri 读一张，压好，编码成 data URL。 */
    fun fromUri(context: Context, uri: Uri): PendingImage? = runCatching {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, MAX_DIMENSION)
        }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return null
        val scaled = scaleDown(decoded, MAX_DIMENSION)
        encode(scaled, uri.lastPathSegment ?: "图片")
    }.getOrNull()

    /** 剪贴板里的图片（截图后粘过来最常见）。 */
    fun fromClipboard(context: Context): PendingImage? = runCatching {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return null
        val clip = manager.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        for (index in 0 until clip.itemCount) {
            val uri = clip.getItemAt(index).uri ?: continue
            fromUri(context, uri)?.let { return it }
        }
        null
    }.getOrNull()

    /** 已经解出来的 Bitmap（比如相机回调）也能走同一条压缩路径。 */
    fun fromBitmap(bitmap: Bitmap, label: String): PendingImage? =
        encode(scaleDown(bitmap, MAX_DIMENSION), label)

    private fun encode(bitmap: Bitmap, label: String): PendingImage? {
        var quality = JPEG_QUALITY
        var bytes: ByteArray
        while (true) {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            bytes = out.toByteArray()
            val base64Length = (bytes.size * 4 / 3) + 4
            if (base64Length <= MAX_BASE64_LENGTH || quality <= 40) break
            quality -= 15
        }
        if ((bytes.size * 4 / 3) + 4 > MAX_BASE64_LENGTH) return null
        val dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        return PendingImage(
            id = UUID.randomUUID().toString(),
            dataUrl = dataUrl,
            bitmap = bitmap,
            label = label,
        )
    }

    private fun sampleSizeFor(width: Int, height: Int, max: Int): Int {
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / 2 >= max) {
            longest /= 2
            sample *= 2
        }
        return sample
    }

    private fun scaleDown(bitmap: Bitmap, max: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= max) return bitmap
        val ratio = max.toFloat() / longest
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }
}

/**
 * `/api/chat` 的请求体。
 *
 * 图片必须作为 user 消息的 `file` part 随行（服务端注释原话：只从 parts 里提取图片），
 * 放别的字段一律不读。文本同时写 `parts[text]` 与 `content`：前者是新协议，
 * 后者是老路径的兜底，服务端两个都会看。
 */
object ChatPayloads {

    fun chatBody(sessionId: String, text: String, imageDataUrls: List<String>): JsonObject =
        buildJsonObject {
            put("id", sessionId)
            put("messages", buildJsonArray { add(userMessage(text, imageDataUrls)) })
        }

    /**
     * `POST /api/sessions/:id/prompt` 的请求体。
     *
     * 和 [chatBody] 的区别：这条通道只投递一条 prompt，返回的是回执而不是回复流；
     * 也只有它吃 `streamingBehavior` —— 宿主把它原样塞进 pi 的 prompt 请求，
     * 「排队到下一轮」才成立（`/api/chat` 的 body 没有这个字段，
     * 宿主忙碌时一律按 steer 处理）。
     */
    fun promptBody(requestId: String, text: String, behavior: String? = null): JsonObject =
        buildJsonObject {
            put("requestId", requestId)
            put("message", text.trim())
            if (!behavior.isNullOrBlank()) put("streamingBehavior", behavior)
        }

    /**
     * `POST /api/sessions` 的请求体。
     *
     * 宿主的路由只校验 `projectId`，其余字段整个透传给 `createSessionDraft`：
     * `backend` 只认 `dsh` / `acp`（pi 是「不写」），`model` 只在**非 ACP** 时被落盘
     * （`!acp && input.model ? … : void 0`），形状 `{provider, modelId, modelName}`。
     *
     * 本端只在 pi 上带模型：DSH 有自己的一套模型体系，ACP 宿主根本不看这个字段 ——
     * 带过去只会让会话记录里多一份谁都不用的偏好。不带时宿主按它自己的默认链路解析
     * （`welcomeModel ?? 设置里的默认模型 ?? 第一个启用模型 ?? 上次用的`），这正是
     * 「跟随电脑端默认」那一档的意思。
     */
    fun createSessionBody(
        projectId: String,
        backend: String?,
        acpToolId: String?,
        model: SessionModel?,
    ): JsonObject = buildJsonObject {
        put("projectId", projectId)
        backend?.takeIf { it.isNotBlank() }?.let { put("backend", it) }
        acpToolId?.takeIf { it.isNotBlank() }?.let { put("acpToolId", it) }
        val isPi = backend.isNullOrBlank()
        if (isPi && model != null && model.provider.isNotBlank() && model.modelId.isNotBlank()) {
            putJsonObject("model") {
                put("provider", model.provider)
                put("modelId", model.modelId)
                put("modelName", model.modelName)
            }
        }
    }

    fun userMessage(text: String, imageDataUrls: List<String>): JsonObject = buildJsonObject {
        put("role", "user")
        put("content", text)
        put("parts", parts(text, imageDataUrls))
    }

    private fun parts(text: String, imageDataUrls: List<String>): JsonArray = buildJsonArray {
        if (text.isNotBlank()) {
            add(
                buildJsonObject {
                    put("type", "text")
                    put("text", text)
                },
            )
        }
        imageDataUrls.take(ImagePrep.MAX_COUNT).forEach { url ->
            add(
                buildJsonObject {
                    put("type", "file")
                    put("mediaType", "image/jpeg")
                    put("url", url)
                },
            )
        }
    }
}
