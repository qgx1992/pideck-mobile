package dev.pideck.nativeapp.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 对着**真**桌面端跑一遍 `/api/state`，验证模型能吃下真实响应。
 *
 * 只有本机存在 `%APPDATA%/PiDeck/settings.json`（拿得到 webServiceToken）时才执行，
 * 否则直接跳过 —— 免得在没有桌面端的环境里变成红灯。
 */
class LiveStateTest {

    private fun token(): String? {
        val home = System.getenv("APPDATA") ?: return null
        val settings = File(home, "PiDeck/settings.json")
        if (!settings.isFile) return null
        val text = settings.readText(Charsets.UTF_8)
        return Regex("\"webServiceToken\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
    }

    @Test
    fun `live state decodes`() {
        val token = token()
        assumeTrue("本机没有桌面端设置，跳过", token != null)
        val base = System.getenv("PIDECK_BASE") ?: "http://10.10.0.172:8765"

        val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
        val request = Request.Builder()
            .url("$base/api/state")
            .header("authorization", "Bearer $token")
            .get()
            .build()
        val body = client.newCall(request).execute().use { response ->
            assertTrue("HTTP ${response.code}", response.isSuccessful)
            response.body!!.string()
        }

        val state = PideckJson.decodeFromString(PideckState.serializer(), body)
        println("live state: projects=${state.projects.size} sessions=${state.sessions.size} runtimes=${state.runtimes.size}")
        assertTrue("projects 不该是空的", state.projects.isNotEmpty())
        assertTrue("sessions 不该是空的", state.sessions.isNotEmpty())
        assertTrue("至少有一个会话带更新时间", state.sessions.any { it.updatedAt > 0 })
    }
}
