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
        SessionActivity.observe("dev-1", state(listOf("s1"), waiting = listOf("s2")))
        assertEquals(SessionActivity.Status.Running, SessionActivity.statusOf("s1"))
        assertEquals(SessionActivity.Status.Waiting, SessionActivity.statusOf("s2"))
        assertEquals(SessionActivity.Status.Idle, SessionActivity.statusOf("s3"))
    }

    @Test
    fun `a session that stops running becomes done`() {
        SessionActivity.reset()
        SessionActivity.observe("dev-1", state(listOf("s1")))
        SessionActivity.observe("dev-1", state(emptyList()))
        assertEquals(SessionActivity.Status.Done, SessionActivity.statusOf("s1"))
    }

    @Test
    fun `real turn result overrides the optimistic done`() {
        SessionActivity.reset()
        SessionActivity.observe("dev-1", state(listOf("s1")))
        SessionActivity.observe("dev-1", state(emptyList()))
        SessionActivity.markTurnResult("dev-1", "s1", failed = true)
        assertEquals(SessionActivity.Status.Failed, SessionActivity.statusOf("s1"))
        // 再轮询一次也不能把它改回 Done
        SessionActivity.observe("dev-1", state(emptyList()))
        assertEquals(SessionActivity.Status.Failed, SessionActivity.statusOf("s1"))
    }

    @Test
    fun `forget clears the entry`() {
        SessionActivity.reset()
        SessionActivity.markTurnResult("dev-1", "s9", failed = false)
        assertEquals(SessionActivity.Status.Done, SessionActivity.statusOf("s9"))
        SessionActivity.forget("dev-1", "s9")
        assertEquals(SessionActivity.Status.Idle, SessionActivity.statusOf("s9"))
    }

    @Test
    fun `two devices with the same session id do not bleed into each other`() {
        SessionActivity.reset()
        SessionActivity.observe("dev-a", state(listOf("s1")))
        SessionActivity.observe("dev-b", state(emptyList()))
        // 渲染的是 B：A 的运行中不该出现在 B 的列表里
        assertEquals(SessionActivity.Status.Idle, SessionActivity.statusOf("s1"))
        assertEquals(SessionActivity.Status.Running, SessionActivity.statusOf("dev-a", "s1"))
        assertEquals(SessionActivity.Status.Idle, SessionActivity.statusOf("dev-b", "s1"))
    }
}
