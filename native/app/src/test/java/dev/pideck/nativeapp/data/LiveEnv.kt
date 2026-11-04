package dev.pideck.nativeapp.data

import java.io.File

/** 真机联调用的环境信息：本机桌面端的 token / 地址，缺失时返回 null 让用例跳过。 */
internal object LiveEnv {

    fun token(): String? {
        val home = System.getenv("APPDATA") ?: return null
        val settings = File(home, "PiDeck/settings.json")
        if (!settings.isFile) return null
        val text = settings.readText(Charsets.UTF_8)
        return Regex("\"webServiceToken\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
    }

    fun baseUrl(): String = System.getenv("PIDECK_BASE") ?: "http://127.0.0.1:8765"

    fun device(): Device = Device(id = "live", name = "live", baseUrl = baseUrl(), createdAt = 0L, lastUsedAt = 0L)
}
