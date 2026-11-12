package dev.pideck.nativeapp.data

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

/** `attempt { }` 与 `runCatching { }` 的区别只有一条：取消照常往上抛。 */
class CancellationAttemptTest {

    @Test
    fun `success keeps the value`() {
        assertEquals(2, attempt { 1 + 1 }.getOrNull())
    }

    @Test
    fun `ordinary failure becomes a Result failure`() {
        val result = attempt<Int> { error("boom") }
        assertTrue(result.isFailure)
        assertEquals("boom", result.exceptionOrNull()?.message)
    }

    @Test
    fun `cancellation is rethrown instead of swallowed`() {
        assertThrows(CancellationException::class.java) {
            attempt<Int> { throw CancellationException("停止") }
        }
    }

    @Test
    fun `failure can still be mapped like runCatching`() {
        assertEquals(
            0,
            attempt<Int> { error("x") }.getOrDefault(0),
        )
    }
}
