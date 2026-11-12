package dev.pideck.nativeapp.ui.chat

import dev.pideck.nativeapp.data.SubagentEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 子任务页里那行「耗时 / 已跑」。
 *
 * 宿主这两项都是可选的：跑 `acp_delegate` 推导出来的条目只有 `startedAt`，
 * 插件落的 record 才有 `completedAt`。缺时间戳要返回 null（整段不写），
 * 不能写「0 秒」—— 那会被读成「真的瞬间就完了」。
 */
class SubagentDurationTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `finished entry reports the span`() {
        val entry = SubagentEntry(
            id = "a1",
            status = "completed",
            startedAt = now - 65_000L,
            completedAt = now,
        )
        assertEquals("耗时 1 分 5 秒", subagentDurationText(entry, now))
    }

    @Test
    fun `running entry counts up to now`() {
        val entry = SubagentEntry(id = "a2", status = "running", startedAt = now - 30_000L)
        assertEquals("已跑 30 秒", subagentDurationText(entry, now))
    }

    @Test
    fun `long running entry shows minutes`() {
        val entry = SubagentEntry(id = "a3", status = "queued", startedAt = now - 3_600_000L)
        assertEquals("已跑 60 分 0 秒", subagentDurationText(entry, now))
    }

    @Test
    fun `finished entry without start has nothing to show`() {
        val entry = SubagentEntry(id = "a4", status = "completed", completedAt = now)
        assertNull(subagentDurationText(entry, now))
    }

    @Test
    fun `no timestamps at all renders nothing`() {
        val entry = SubagentEntry(id = "a5", status = "error")
        assertNull(subagentDurationText(entry, now))
    }

    @Test
    fun `clock skew does not produce negative time`() {
        val entry = SubagentEntry(id = "a6", status = "running", startedAt = now + 5_000L)
        assertEquals("已跑 0 秒", subagentDurationText(entry, now))
    }
}
