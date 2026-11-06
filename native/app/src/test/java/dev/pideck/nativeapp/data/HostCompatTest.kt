package dev.pideck.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 宿主版本与适配基线。
 *
 * `/api/state.version` 只有桌面端 0.10.0 起才有：老宿主解析出来是 null，
 * 这里要保证「版本未知」不会变成「你的宿主太旧」的假警报。
 */
class HostCompatTest {

    @Test
    fun parsesFirstThreeNumbers() {
        assertEquals(listOf(0, 10, 0), HostCompat.parse("0.10.0"))
        assertEquals(listOf(0, 10, 0), HostCompat.parse("0.10.0-beta.1"))
        assertEquals(listOf(1, 2, 3), HostCompat.parse("v1.2.3"))
        assertNull(HostCompat.parse(null))
        assertNull(HostCompat.parse("  "))
    }

    @Test
    fun olderThanBaseline() {
        assertTrue(HostCompat.isOlderThanBaseline("0.9.9"))
        assertTrue(HostCompat.isOlderThanBaseline("0.9.99"))
        assertFalse(HostCompat.isOlderThanBaseline("0.10.0"))
        assertFalse(HostCompat.isOlderThanBaseline("0.10.1"))
        assertFalse(HostCompat.isOlderThanBaseline("1.0.0"))
    }

    @Test
    fun unknownVersionIsNotAWarning() {
        assertFalse(HostCompat.isOlderThanBaseline(null))
        assertNull(HostCompat.hint(null))
    }

    @Test
    fun hintOnlyForOlderHosts() {
        assertNull(HostCompat.hint("0.10.0"))
        val hint = HostCompat.hint("0.9.9")
        assertTrue(hint != null && hint.contains(HostCompat.BASELINE))
    }
}
