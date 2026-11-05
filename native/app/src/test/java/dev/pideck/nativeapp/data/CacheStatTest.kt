package dev.pideck.nativeapp.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缓存命中率的口径测试。
 *
 * 钉住三件事：
 * 1. 公式是 `cacheRead / (input + cacheRead + cacheWrite)`（分母三个字段都要算）；
 * 2. 缺字段 / 分母为 0 → null（界面上显示 `—`，不是 0%）；
 * 3. 「最近一轮」取最新那一页的最后一条带 usage 的 assistant，平均是逐条简单平均。
 */
class CacheStatTest {

    private fun message(
        id: String,
        read: Long? = null,
        input: Long? = null,
        write: Long? = null,
        role: String = "assistant",
    ): ChatMessage {
        val usage = buildJsonObject {
            read?.let { put("cacheReadTokens", it) }
            input?.let { put("inputTokens", it) }
            write?.let { put("cacheWriteTokens", it) }
            put("outputTokens", 100)
        }
        val meta: JsonObject = buildJsonObject { put("usage", usage) }
        return ChatMessage(id = id, role = role, text = "x", meta = meta)
    }

    @Test
    fun `读缓存占分母四分之三`() {
        // read 300 / (input 100 + read 300 + write 0) = 75%
        assertEquals(75.0, cacheHitOf(message("a", read = 300, input = 100).meta)!!, 0.001)
    }

    @Test
    fun `写缓存也进分母`() {
        // read 100 / (100 + 100 + 100) = 33.3%
        assertEquals(33.33, cacheHitOf(message("a", read = 100, input = 100, write = 100).meta)!!, 0.01)
    }

    @Test
    fun `没有缓存字段就是零命中而不是没数`() {
        // 有输入、没 cacheRead：这是真 0%（本轮完全没命中），不是「没数据」
        val meta = buildJsonObject { put("usage", buildJsonObject { put("inputTokens", 500) }) }
        assertEquals(0.0, cacheHitOf(meta)!!, 0.001)
        // meta 整个是 null（acp）才算没数
        assertNull(cacheHitOf(null))
        assertEquals(false, cacheStatFrom(listOf(ChatMessage(id = "a", meta = null))).known)
        // dsh 只报 usage 的一部分时，同样按「有输入 → 0%」处理
        assertEquals(0, cacheStatFrom(listOf(message("d", input = 900))).latestPercent)
    }

    @Test
    fun `分母为零不报命中率`() {
        // 全零：不能算出 0/0，也不该显示 0%
        assertNull(cacheHitOf(message("z", read = 0, input = 0, write = 0).meta))
    }

    @Test
    fun `平均是逐条简单平均而不是 token 加权`() {
        // 90%（大量 token）与 0%（少量 token）的简单平均是 45%；
        // 若误用 token 加权会得到 ~90%，所以这条能挡住口径漂移。
        val big = message("big", read = 9000, input = 1000, write = 0)
        val small = message("small", read = 0, input = 10, write = 0)
        val stat = cacheStatFrom(listOf(big, small))
        assertEquals(0, stat.latestPercent)   // 最近一轮是 small（0%），不是最高的那条
        assertEquals(45, stat.averagePercent)
        assertEquals(2, stat.samples)
    }

    @Test
    fun `最近一轮取最新一页的最后一条带 usage 的 assistant`() {
        // 历史接口从新到旧翻页：先喂的那页是新的
        val builder = CacheStatBuilder()
        builder.addPage(listOf(message("new-1", read = 90, input = 10)))     // 90%
        builder.addPage(listOf(message("old-1", read = 10, input = 90)))     // 10%
        val stat = builder.build()
        assertEquals(90, stat.latestPercent)
        assertEquals(50, stat.averagePercent)
    }

    @Test
    fun `user 消息与空 meta 不参与统计`() {
        val stat = cacheStatFrom(
            listOf(
                message("u", read = 999, input = 1, role = "user"),
                ChatMessage(id = "tool", role = "assistant", text = "x", meta = null),
                message("a", read = 40, input = 60),
            ),
        )
        assertEquals(40, stat.latestPercent)
        assertEquals(40, stat.averagePercent)
        assertEquals(1, stat.samples)
    }

    @Test
    fun `全部为空时整项不可用`() {
        val stat = cacheStatFrom(emptyList())
        assertTrue(!stat.known)
        assertNull(stat.latestPercent)
        assertNull(stat.averagePercent)
        assertEquals(0, stat.samples)
    }
}
