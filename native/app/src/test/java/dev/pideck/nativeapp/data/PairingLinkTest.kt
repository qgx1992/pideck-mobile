package dev.pideck.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingLinkTest {

    @Test
    fun `网页地址带令牌`() {
        val parsed = PairingLink.parse("http://10.10.0.172:8765/?token=abc123")
        assertEquals("http://10.10.0.172:8765", parsed?.baseUrl)
        assertEquals("abc123", parsed?.token)
    }

    @Test
    fun `hash 路由上的令牌也要认`() {
        val parsed = PairingLink.parse("http://10.10.0.172:8080/#/?token=xyz")
        assertEquals("http://10.10.0.172:8080", parsed?.baseUrl)
        assertEquals("xyz", parsed?.token)
    }

    @Test
    fun `深链形态`() {
        val parsed = PairingLink.parse("pideck://pair?host=192.168.1.7&port=8765&token=t0k")
        assertEquals("http://192.168.1.7:8765", parsed?.baseUrl)
        assertEquals("t0k", parsed?.token)
    }

    @Test
    fun `裸地址补默认端口`() {
        val parsed = PairingLink.parse("192.168.1.7")
        assertEquals("http://192.168.1.7:8765", parsed?.baseUrl)
        assertNull(parsed?.token)
    }

    @Test
    fun `裸地址带端口与查询串`() {
        val parsed = PairingLink.parse("10.0.0.5:9000?token=qq")
        assertEquals("http://10.0.0.5:9000", parsed?.baseUrl)
        assertEquals("qq", parsed?.token)
    }

    @Test
    fun `空输入直接拒绝`() {
        assertNull(PairingLink.parse("   "))
    }

    @Test
    fun `显式令牌优先于链接里的`() {
        assertEquals("typed", PairingLink.mergeToken("typed", "from-link"))
        assertEquals("from-link", PairingLink.mergeToken("  ", "from-link"))
        assertNull(PairingLink.mergeToken(null, null))
    }
}
