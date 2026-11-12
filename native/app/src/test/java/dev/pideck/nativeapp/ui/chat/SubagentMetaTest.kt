package dev.pideck.nativeapp.ui.chat

import dev.pideck.nativeapp.data.SubagentEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 子代理行的元信息文案。
 *
 * 宿主这几项都是可选的：跑 acp_delegate 推导出来的条目只有 `type/status/startedAt`，
 * 插件落的 record 才有 `toolUses/tokens/completedAt`。缺项要整段消失，
 * 不能写 `0 tok` —— 那会被读成「真的一次工具都没调」。
 */
class SubagentMetaTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `full entry reports every field`() {
        val entry = SubagentEntry(
            id = "a1",
            type = "researcher",
            status = "completed",
            startedAt = now - 65_000L,
            completedAt = now,
            toolUses = 2,
            tokens = 1500,
        )
        assertEquals("researcher · 2 次工具 · 1500 tok · 1 分 5 秒", subagentMetaText(entry, now))
    }

    @Test
    fun `missing counters are not rendered as zero`() {
        val entry = SubagentEntry(id = "a2", type = "reviewer", status = "completed")
        assertEquals("reviewer", subagentMetaText(entry, now))
    }

    @Test
    fun `running entry counts up to now`() {
        val entry = SubagentEntry(
            id = "a3",
            type = "worker",
            status = "running",
            startedAt = now - 30_000L,
        )
        assertEquals("worker · 30 秒（跑着）", subagentMetaText(entry, now))
    }

    @Test
    fun `long running entry shows minutes`() {
        val entry = SubagentEntry(
            id = "a4",
            type = "worker",
            status = "running",
            startedAt = now - 3_600_000L,
        )
        assertTrue(subagentMetaText(entry, now).contains("60 分 0 秒（跑着）"))
    }

    @Test
    fun `finished entry without start falls back to relative time`() {
        val entry = SubagentEntry(
            id = "a5",
            type = "oracle",
            status = "completed",
            completedAt = now - 3 * 60_000L,
        )
        assertEquals("oracle · 3 分钟前", subagentMetaText(entry, now))
    }

    @Test
    fun `entry without type renders nothing`() {
        val entry = SubagentEntry(id = "a6", status = "failed", startedAt = now, completedAt = now)
        assertEquals("0 秒", subagentMetaText(entry, now))
    }
}
