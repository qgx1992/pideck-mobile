package dev.pideck.nativeapp.ui.chat

import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.pideck.nativeapp.data.MessageImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 消息里带的图片。
 *
 * 宿主的 `/api/sessions/:id/messages/page` 对 user 消息会附带 `images: [{type, data, mimeType}]`，
 * `data` 是裸 base64。之前本地的 `ChatMessage` 没声明这个字段（kotlinx 直接丢掉），
 * 会话页就只剩一句「（1 张图片）」—— 图从来没被读进来。
 *
 * 解码在这里做，不进 ViewModel：base64 解出来是几十 KB 的位图，只有真正滚到这一条时才需要它。
 * 解码按缩略图尺寸降采样（`inSampleSize`），几张 1568px 的原图不会每张都占几 MB。
 */
private const val THUMB_MAX_PIXELS = 512

/** 全屏查看时的解码上限（长边像素）。 */
private const val FULL_MAX_PIXELS = 1600

private object ThumbCache {
    /** 键是 `长度:hashCode`，够区分不同图片，又不用拿整段 base64 当键。 */
    private val cache = object : LruCache<String, ImageBitmap>(24) {}

    fun get(key: String): ImageBitmap? = cache.get(key)

    fun put(key: String, value: ImageBitmap) {
        cache.put(key, value)
    }

    fun keyOf(image: MessageImage): String = "${image.data.length}:${image.data.hashCode()}"
}

/**
 * 全屏大图单独一份缓存，**不能**和缩略图共用键。
 *
 * 共用时有两种坏结局（0.5.55 修的）：先滚列表再点开 → 拿到 512px 的缩略图放大显示，看大图永远是糊的；
 * 先点全屏 → 1600px 位图写进按「条数 24」算容量的缩略图缓存（≈180MB）→ OOM。
 * 这里按字节计容量 24MB，最多只留两张。
 */
private const val FULL_CACHE_BYTES = 24 * 1024 * 1024

private object FullImageCache {
    private val cache = object : LruCache<String, ImageBitmap>(FULL_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    fun get(key: String): ImageBitmap? = cache.get(key)

    fun put(key: String, value: ImageBitmap) {
        cache.put(key, value)
    }
}

/** 解码成缩略图；解不出来返回 null（气泡里留一个占位块，不崩）。 */
private fun decodeThumb(base64: String, maxPixels: Int): ImageBitmap? = runCatching {
    // 宿主给的是裸 base64；万一某个版本带上了 `data:…;base64,` 前缀也别整张图丢掉
    val payload = if (base64.contains(",base64,")) base64.substringAfter(",base64,") else base64
    val bytes = Base64.decode(payload, Base64.DEFAULT)
    if (bytes.isEmpty()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    var longest = maxOf(bounds.outWidth, bounds.outHeight)
    while (longest / 2 >= maxPixels) {
        longest /= 2
        sample *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}.getOrNull()

/**
 * 用户气泡里的图片条：一张时大图、多张时两列网格，点开全屏看。
 *
 * 尺寸固定（单张 200dp、网格 132dp），不跟着原图比例走 —— 手机发的图动不动 3:4 或 16:9，
 * 跟着比例排版会让同一条消息的气泡忽长忽短。
 */
@Composable
fun MessageImages(
    images: List<MessageImage>,
    keyPrefix: String,
    modifier: Modifier = Modifier,
) {
    if (images.isEmpty()) return
    var preview by remember(keyPrefix) { mutableStateOf<MessageImage?>(null) }
    val single = images.size == 1
    val cell = if (single) 200.dp else 132.dp

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (single) {
            HistoryThumb(images.first(), cell, fill = false) { preview = images.first() }
        } else {
            images.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { image ->
                        HistoryThumb(image, cell, fill = true) { preview = image }
                    }
                }
            }
        }
    }

    preview?.let { image ->
        FullscreenImage(image) { preview = null }
    }
}

@Composable
private fun HistoryThumb(
    image: MessageImage,
    size: Dp,
    fill: Boolean,
    onClick: () -> Unit,
) {
    val key = ThumbCache.keyOf(image)
    val bitmap by produceState(initialValue = ThumbCache.get(key), key) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { decodeThumb(image.data, THUMB_MAX_PIXELS) }
                ?.also { ThumbCache.put(key, it) }
        }
    }
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.18f))
            .clickable(enabled = bitmap != null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let { loaded ->
            Image(
                bitmap = loaded,
                contentDescription = "图片",
                contentScale = if (fill) ContentScale.Crop else ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 点开后的全屏查看：黑底 + Fit，点任意处关闭（不引入第三方的缩放组件）。 */
@Composable
private fun FullscreenImage(image: MessageImage, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            val key = ThumbCache.keyOf(image)
            val bitmap by produceState(initialValue = FullImageCache.get(key), key) {
                if (value == null) {
                    value = withContext(Dispatchers.IO) { decodeThumb(image.data, FULL_MAX_PIXELS) }
                        ?.also { FullImageCache.put(key, it) }
                }
            }
            bitmap?.let { loaded ->
                Image(
                    bitmap = loaded,
                    contentDescription = "图片",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
