package dev.pideck.nativeapp.data

import java.net.URI

/**
 * 配对输入的统一解析。
 *
 * 桌面端「连接面板」给出的形态有好几种，历史上都出现过：
 * 1. `http://10.10.0.172:8765/?token=xxxx`（网页版地址带令牌）
 * 2. `http://10.10.0.172:8080/#/?token=xxxx`
 * 3. `pideck://pair?host=10.10.0.172&port=8765&token=xxxx`（二维码用的深链）
 * 4. 手输 `10.10.0.172:8765` + 令牌分开填
 *
 * 统一归一成 `scheme://host[:port]` + token，端口缺省 8765。
 */
object PairingLink {

    private const val DEFAULT_PORT = 8765

    data class Parsed(
        val baseUrl: String,
        val token: String?,
        val label: String?,
    )

    fun parse(raw: String): Parsed? {
        val text = raw.trim().removeSurrounding("\"")
        if (text.isEmpty()) return null

        // 深链形态
        if (text.startsWith("pideck://", ignoreCase = true)) {
            val uri = runCatching { URI(text) }.getOrNull() ?: return null
            val params = queryParams(uri.rawQuery)
            val host = params["host"] ?: return null
            val port = params["port"]?.toIntOrNull() ?: DEFAULT_PORT
            return Parsed(baseUrl = buildBaseUrl("http", host, port), token = params["token"], label = params["name"])
        }

        // 带 scheme 的 URL
        if (text.contains("://")) {
            val uri = runCatching { URI(text) }.getOrNull() ?: return null
            val host = uri.host ?: return null
            val port = if (uri.port > 0) uri.port else DEFAULT_PORT
            val scheme = uri.scheme?.lowercase() ?: "http"
            return Parsed(buildBaseUrl(scheme, host, port), tokenFrom(uri), null)
        }

        // 裸 host[:port]，可能尾巴上带 /?token= 或 ?token=
        val (hostPart, queryPart) = text.splitFirst('?')
        val cleaned = hostPart.trimEnd('/').removeSuffix("/")
        val host = cleaned.substringBefore(':').ifBlank { return null }
        val port = cleaned.substringAfter(':', "").toIntOrNull() ?: DEFAULT_PORT
        return Parsed(buildBaseUrl("http", host, port), queryParams(queryPart)["token"], null)
    }

    /** 令牌可以单独填，也可以从链接里带出来；两边都有时以单独填的为准。 */
    fun mergeToken(explicit: String?, fromLink: String?): String? =
        explicit?.trim()?.takeIf { it.isNotEmpty() } ?: fromLink

    private fun buildBaseUrl(scheme: String, host: String, port: Int): String {
        val defaultPort = (scheme == "http" && port == 80) || (scheme == "https" && port == 443)
        return if (defaultPort) "$scheme://$host" else "$scheme://$host:$port"
    }

    private fun String.splitFirst(separator: Char): Pair<String, String> {
        val index = indexOf(separator)
        return if (index < 0) this to "" else substring(0, index) to substring(index + 1)
    }

    /**
     * 令牌可能落在两处：正常的 `?token=`，或者 hash 路由里的 `#/?token=`
     * （桌面端早期分享链接就是这种形态，URI 的 rawQuery 在它上面是 null）。
     */
    private fun tokenFrom(uri: URI): String? {
        queryParams(uri.rawQuery)["token"]?.let { return it }
        val fragment = uri.rawFragment ?: uri.fragment
        if (fragment != null && fragment.contains('?')) {
            return queryParams(fragment.substringAfter('?'))["token"]
        }
        return null
    }

    private fun queryParams(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split('&').mapNotNull { pair ->
            val index = pair.indexOf('=')
            if (index <= 0) null
            else {
                val key = decode(pair.substring(0, index))
                val value = decode(pair.substring(index + 1))
                key to value
            }
        }.toMap()
    }

    private fun decode(value: String): String =
        runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}
