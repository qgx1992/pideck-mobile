package dev.pideck.nativeapp.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重连外壳：退避怎么算、什么时候停、失败轮数怎么给。
 *
 * 这里刻意用 `runBlocking` 而不是 `runTest`：外壳里是无限循环，只有在 `connect` 返回 null 时才退出，
 * 用虚拟时间也只是把 `delay` 变成立刻 —— 关键要证明的是「连不上就退出」而不是「永远挂着」。
 */
class ReconnectTest {

    @Test
    fun `first failure waits one and a half seconds`() {
        assertEquals(1_500L, reconnectDelay(0L))
        assertEquals(3_000L, reconnectDelay(3_000L))
    }

    @Test
    fun `backoff starts at three seconds and doubles`() {
        assertEquals(3_000L, nextBackoff(failures = 1, current = 0L))
        assertEquals(6_000L, nextBackoff(failures = 2, current = 3_000L))
        assertEquals(12_000L, nextBackoff(failures = 3, current = 6_000L))
    }

    @Test
    fun `backoff is capped at fifteen seconds`() {
        assertEquals(15_000L, nextBackoff(failures = 4, current = 12_000L))
        assertEquals(15_000L, nextBackoff(failures = 9, current = 15_000L))
    }

    @Test
    fun `null connection stops the loop`() = runBlocking {
        var connects = 0
        reconnectingStream(
            connect = { connects += 1; null },
            stream = { _, _ -> error("不该走到这里") },
        )
        assertEquals(1, connects)
    }

    @Test
    fun `failing stream counts as a round and reports the cause`() = runBlocking {
        val counts = mutableListOf<Int>()
        var connects = 0
        reconnectingStream(
            connect = {
                connects += 1
                if (connects > 2) null else FAKE
            },
            stream = { _, _ -> error("断线") },
            onFailure = { failures, cause -> counts += failures },
        )
        assertEquals(listOf(1, 2), counts)
        assertEquals(3, connects)
    }

    @Test
    fun `frames reset the failure counter`() = runBlocking {
        val counts = mutableListOf<Int>()
        var connects = 0
        reconnectingStream(
            connect = {
                connects += 1
                if (connects > 3) null else FAKE
            },
            stream = { _, onFrame ->
                onFrame()
                error("又断了")
            },
            onFailure = { failures, _ -> counts += failures },
        )
        // 每轮都收到过帧 → 计数一直是 1，不会一路涨到上限
        assertEquals(listOf(1, 1, 1), counts)
    }

    @Test
    fun `cancellation is not treated as a failure`() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                reconnectingStream(
                    connect = { FAKE },
                    stream = { _, _ -> throw CancellationException("停止") },
                )
            }
        }
    }

    @Test
    fun `normal close also counts as a round`() = runBlocking {
        val counts = mutableListOf<Int>()
        var connects = 0
        reconnectingStream(
            connect = {
                connects += 1
                if (connects > 1) null else FAKE
            },
            stream = { _, _ -> Unit },
            onFailure = { failures, _ -> counts += failures },
        )
        // 服务端正常收尾（比如 404 落成空 body）时以前会把退避归零 → 变成 1.5 秒一次的忙循环。
        // 现在正常收尾也算一轮：退避 → 再取连接（第二次拿到 null 才退出），所以是 2 次。
        assertEquals(2, connects)
        assertTrue(counts.isEmpty())
    }

    private companion object {
        val FAKE = Connection(
            Device(id = "d1", name = "本机", baseUrl = "http://127.0.0.1:8765"),
            "tok",
        )
    }
}
