package dev.pideck.nativeapp.data

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * 两类请求用的是两个 client，读超时不能混：
 *
 * - SSE（`/api/chat`、`/api/sessions/:id/stream`）必须「读不超时」，否则长时间没有帧就被断开；
 * - 普通 JSON 请求必须**有**读超时，否则半开连接会永远挂着（踩过：`startStatePolling` 的协程停摆）。
 */
class ApiClientTimeoutTest {

    private val api = OkHttpPideckApi(defaultOkHttpClient())

    @Test
    fun `共享 client 保持读不超时（SSE 用）`() {
        assertEquals(0, defaultOkHttpClient().readTimeoutMillis)
    }

    @Test
    fun `普通请求的 client 有读超时`() {
        assertEquals((SHORT_READ_TIMEOUT_SECONDS * 1000).toInt(), api.jsonClient.readTimeoutMillis)
        assertTrue("普通请求的读超时必须有限", api.jsonClient.readTimeoutMillis > 0)
    }

    @Test
    fun `派生 client 沿用共享 client 的连接池与连接超时`() {
        val base: OkHttpClient = defaultOkHttpClient()
        val api = OkHttpPideckApi(base)
        assertTrue("共用连接池，别每次 new 一个", api.jsonClient.connectionPool === base.connectionPool)
        assertEquals(base.connectTimeoutMillis, api.jsonClient.connectTimeoutMillis)
    }
}
