package dev.pideck.nativeapp.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「取这台设备 + 令牌」的三种失败模式都得有结果，不能挂住：
 * 设备不存在、令牌读不出来、读盘迟迟不吐。以前每个 ViewModel 各写一遍这段，
 * 于是「设备被删 → 会话页永久转圈」在四个地方各犯一次（0.5.41 收口）。
 */
class ConnectionResolverTest {

    private val device = Device(
        id = "d1",
        name = "本机",
        baseUrl = "http://127.0.0.1:8765",
        createdAt = 1L,
        lastUsedAt = 2L,
    )

    private class FakeDirectory(
        override val devices: Flow<List<Device>>,
        private val token: String? = null,
        private val throwOnToken: Boolean = false,
    ) : DeviceDirectory {
        override fun tokenOf(deviceId: String): String? {
            if (throwOnToken) error("AEADBadTagException")
            return token
        }
    }

    @Test
    fun `resolves device and token`() = runTest {
        val conn = ConnectionResolver(FakeDirectory(flowOf(listOf(device)), token = "tok"))
            .resolve("d1")
        assertNotNull(conn)
        assertEquals("d1", conn!!.device.id)
        assertEquals("tok", conn.token)
    }

    @Test
    fun `missing token is null but device still resolves`() = runTest {
        val conn = ConnectionResolver(FakeDirectory(flowOf(listOf(device))))
            .resolve("d1")
        assertEquals("d1", conn?.device?.id)
        assertNull(conn?.token)
    }

    @Test
    fun `keystore failure does not blow up the resolver`() = runTest {
        val conn = ConnectionResolver(FakeDirectory(flowOf(listOf(device)), throwOnToken = true))
            .resolve("d1")
        assertNotNull(conn)
        assertNull(conn!!.token)
    }

    @Test
    fun `unknown device resolves to null immediately`() = runTest {
        assertNull(ConnectionResolver(FakeDirectory(flowOf(listOf(device)))).resolve("nope"))
    }

    @Test
    fun `a directory that never emits device does not hang forever`() = runTest {
        // 永不放行的流：模拟 DataStore 卡住 / 设备在别处被删掉。
        val stuck = FakeDirectory(flow { kotlinx.coroutines.awaitCancellation() })
        assertNull(ConnectionResolver(stuck).resolve("d1", timeoutMs = 100L))
    }
}
