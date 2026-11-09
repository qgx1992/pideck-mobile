package dev.pideck.nativeapp.ui.common

import dev.pideck.nativeapp.data.PideckException
import java.net.ConnectException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 列表页的四种状态必须互斥且可判（0.5.42 把 `loading` + `error` 两个字段合成一份）。
 * 这里盯的是「界面该怎么读它」，以及异常→文案的转换不会把 401 说成网络问题。
 */
class LoadStateTest {

    @Test
    fun `only loading is loading`() {
        assertTrue(LoadState.Loading.isLoading)
        assertTrue(!LoadState.Idle.isLoading)
        assertTrue(!LoadState.Ready.isLoading)
        assertTrue(!LoadState.Failed("x").isLoading)
    }

    @Test
    fun `message only exists for failure`() {
        assertNull(LoadState.Idle.message)
        assertNull(LoadState.Loading.message)
        assertNull(LoadState.Ready.message)
        assertEquals("连不上", LoadState.Failed("连不上").message)
    }

    @Test
    fun `known network failures turn into chinese advice`() {
        // 认得的异常必须翻成人话（认不出的才带类名，那是给排查用的）
        val state = failureOf(ConnectException("Connection refused"))
        assertNotNull(state.message)
        assertTrue("应指向「桌面端没开 Web 服务」：${state.message}", state.message!!.contains("连不上"))

        val timeout = failureOf(SocketTimeoutException("timeout"))
        assertTrue("超时应指向网络/防火墙：${timeout.message}", timeout.message!!.contains("超时"))
    }

    @Test
    fun `401 points at the pairing flow not at the network`() {
        val state = failureOf(PideckException(code = 401, message = "unauthorized"))
        val text = state.message ?: ""
        assertTrue("401 应提示重新配对：$text", text.contains("令牌") || text.contains("配对"))
    }

    @Test
    fun `explicit message stays verbatim`() {
        assertEquals("设备已不存在，回设备页重新添加", failureOf("设备已不存在，回设备页重新添加").message)
    }
}
