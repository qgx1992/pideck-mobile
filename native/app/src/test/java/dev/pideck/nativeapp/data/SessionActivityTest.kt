package dev.pideck.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionActivityTest {

    private fun state(running: List<String>, waiting: List<String> = emptyList()): PideckState = PideckState(
        projects = emptyList(),
        sessions = emptyList(),
        runtimes = running.map {
            RuntimeInfo(sessionId = it, agentId = "a-$it", status = "running", cwd = null)
        },
        pendingUiRequests = waiting.map {
            PendingUiRequest(sessionId = it, agentId = "a", requestId = "r-$it", method = "ask")
        },
    )

    @Test
    fun `running session turns blue, waiting turns orange`() {
        SessionActivity.reset()
        SessionActivity.observe(state(listOf("s1"), waiting = listOf("s2")))
        assertEquals(SessionActivity.Status.Running, SessionActivity.statusOf("s1"))
        assertEquals(SessionActivity.Status.Waiting, SessionActivity.statusOf("s2"))
        assertEquals(SessionActivity.Status.Idle, SessionActivity.statusOf("s3"))
    }

    @Test
    fun `a session that stops running becomes done`() {
        SessionActivity.reset()
        SessionActivity.observe(state(listOf("s1")))
        SessionActivity.observe(state(emptyList()))
        assertEquals(SessionActivity.Status.Done, SessionActivity.statusOf("s1"))
    }

    @Test
    fun `real turn result overrides the optimistic done`() {
        SessionActivity.reset()
        SessionActivity.observe(state(listOf("s1")))
        SessionActivity.observe(state(emptyList()))
        SessionActivity.markTurnResult("s1", failed = true)
        assertEquals(SessionActivity.Status.Failed, SessionActivity.statusOf("s1"))
        // 再轮询一次也不能把它改回 Done
        SessionActivity.observe(state(emptyList()))
        assertEquals(SessionActivity.Status.Failed, SessionActivity.statusOf("s1"))
    }

    @Test
    fun `forget clears the entry`() {
        SessionActivity.reset()
        SessionActivity.markTurnResult("s9", failed = false)
        assertEquals(SessionActivity.Status.Done, SessionActivity.statusOf("s9"))
        SessionActivity.forget("s9")
        assertEquals(SessionActivity.Status.Idle, SessionActivity.statusOf("s9"))
    }
}
