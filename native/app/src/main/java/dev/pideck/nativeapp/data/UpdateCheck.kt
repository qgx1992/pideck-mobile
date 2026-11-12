package dev.pideck.nativeapp.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 一条 GitHub Release 里我们关心的东西。
 */
data class RemoteRelease(
    val versionName: String,
    val versionCode: Int,
    val pageUrl: String,
    val apkUrl: String? = null,
    val apkSize: Long? = null,
    val publishedAt: String? = null,
    val notes: String? = null,
)

/**
 * 「检查更新」的纯逻辑 + 一次网络。
 *
 * 版本从 GitHub Release 的 tag 来（`v0.5.50` → `0.5.50`），比较用点分段逐段比，
 * 不依赖 `versionCode` 那套公式 —— 公式在 patch 超过 99 时会和 minor 撞上，
 * 而每次只比 tag 字符串里的数字永远单调。
 *
 * 没有走宿主的 /api 路由：宿主的 Web 服务只管桌面端自己，它不知道 Releases 里有什么。
 */
object UpdateCheck {

    const val REPO = "qgx1992/pideck-mobile"
    const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
    const val RELEASES_URL = "https://github.com/$REPO/releases"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    /** `v0.5.50` / `0.5.50 ` / `V0.5.50` → `0.5.50`。 */
    fun normalizeVersion(tag: String): String =
        tag.trim().removePrefix("v").removePrefix("V").trim()

    /** 点分数字；带 `-beta` 这类后缀的只取前半段。解析不出来给 null。 */
    fun versionParts(name: String): List<Int>? {
        val head = normalizeVersion(name).substringBefore('-').substringBefore('+')
        if (head.isBlank()) return null
        val parts = head.split('.').map { it.trim().toIntOrNull() ?: return null }
        if (parts.isEmpty()) return null
        if (parts.any { it < 0 }) return null
        return parts
    }

    /** 与 `versionCode = major*1000 + minor*100 + patch` 同一套公式（只用来显示/存档）。 */
    fun versionCodeOf(name: String): Int? {
        val parts = versionParts(name) ?: return null
        val major = parts.getOrElse(0) { 0 }
        val minor = parts.getOrElse(1) { 0 }
        val patch = parts.getOrElse(2) { 0 }
        return major * 1000 + minor * 100 + patch
    }

    /**
     * [remote] 比 [current] 新吗？
     *
     * 逐段比数字（`0.5.100` > `0.5.99`，公式算出来会撞车）；任一侧解析不出来就当「不是新的」，
     * 宁可少提示一次，也不要拿一句坏 tag 天天弹「有新版本」。
     */
    fun isNewer(remote: String, current: String): Boolean {
        val r = versionParts(remote) ?: return false
        val c = versionParts(current) ?: return false
        for (index in 0 until maxOf(r.size, c.size)) {
            val a = r.getOrElse(index) { 0 }
            val b = c.getOrElse(index) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /**
     * 解析 `/releases/latest` 的回包。
     *
     * 只认 `tag_name` + 可选 `assets` 里的第一个 `.apk`（Release 页与直链都给出来，
     * 用户想直接下就下，不想下就去页面看说明）。
     */
    fun parseLatestRelease(json: String): RemoteRelease? {
        val root = attempt { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
        if (root["message"]?.jsonPrimitive?.contentOrNull != null) return null // 404 / 限流
        if (root["draft"]?.jsonPrimitive?.contentOrNull == "true") return null
        val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
        val name = normalizeVersion(tag)
        val code = versionCodeOf(name) ?: return null
        val pageUrl = root["html_url"]?.jsonPrimitive?.contentOrNull ?: "$RELEASES_URL/tag/v$name"
        val asset = attempt {
            root["assets"]?.jsonArray
                ?.mapNotNull { it.jsonObject }
                ?.firstOrNull {
                    it["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk", ignoreCase = true) == true
                }
        }.getOrNull()
        return RemoteRelease(
            versionName = name,
            versionCode = code,
            pageUrl = pageUrl,
            apkUrl = asset?.get("browser_download_url")?.jsonPrimitive?.contentOrNull,
            apkSize = asset?.get("size")?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
            publishedAt = root["published_at"]?.jsonPrimitive?.contentOrNull,
            notes = root["body"]?.jsonPrimitive?.contentOrNull,
        )
    }

    /** 同步拉一次（调用方负责放到 IO 线程）。失败给带人话的提示。 */
    fun fetchLatestBlocking(): Result<String> = attempt {
        val request = Request.Builder()
            .url(LATEST_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "PiDeck-Mobile")
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("GitHub 回了 HTTP ${response.code}")
            }
            body
        }
    }
}
