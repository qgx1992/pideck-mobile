package dev.pideck.nativeapp.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用真机上抓到的 `/api/sessions/:id/messages/page` 形状（工具消息的 meta、assistant 的
 * thinking + text、system 噪音）验证映射，别在真机上才发现工具卡不显示。
 */
class TimelineTest {

    private fun meta(json: String?): JsonObject? = json?.let { Json.parseToJsonElement(it) as JsonObject }

    @Test
    fun `user and assistant messages map to bubbles`() {
        val messages = listOf(
            ChatMessage(id = "u1", role = "user", text = "帮我看下构建", timestamp = 1_700_000_000_000),
            ChatMessage(
                id = "a1",
                role = "assistant",
                text = "构建通过了。",
                thinking = "先跑一次",
                timestamp = 1_700_000_001_000,
                meta = meta("""{"durationMs":5282,"usage":{"outputTokens":433}}"""),
            ),
        )
        val items = messages.toTimelineItems()
        assertEquals(2, items.size)
        val user = items[0] as TimelineItem.UserMessage
        assertEquals("帮我看下构建", user.text)
        val assistant = items[1] as TimelineItem.AssistantMessage
        assertEquals("构建通过了。", assistant.text)
        assertEquals("先跑一次", assistant.thinking)
        assertTrue(assistant.tools.isEmpty())
    }

    @Test
    fun `tool message becomes a tool card with args and result`() {
        val message = ChatMessage(
            id = "t1",
            role = "tool",
            text = "pwsh: Get-ChildItem",
            timestamp = 1_700_000_002_000,
            meta = meta(
                """{"toolCallId":"call_1","toolName":"pwsh","status":"ok","args":{"command":"Get-ChildItem"},"result":"a.txt","durationMs":120}""",
            ),
        )
        val item = listOf(message).toTimelineItems().single() as TimelineItem.AssistantMessage
        val tool = item.tools.single()
        assertEquals("pwsh", tool.name)
        assertEquals("output-available", tool.state)
        assertTrue(tool.input!!.contains("Get-ChildItem"))
        assertEquals("a.txt", tool.output)
    }

    @Test
    fun `error tool keeps its message`() {
        val message = ChatMessage(
            id = "t2",
            role = "tool",
            text = "✗ 命令失败",
            timestamp = 1_700_000_003_000,
            meta = meta("""{"toolName":"bash","isError":true,"detailText":"exit code 1"}"""),
        )
        val tool = (listOf(message).toTimelineItems().single() as TimelineItem.AssistantMessage).tools.single()
        assertEquals("output-error", tool.state)
        assertEquals("exit code 1", tool.errorText)
        assertTrue(tool.isError)
    }

    @Test
    fun `empty assistant shell is dropped`() {
        val messages = listOf(ChatMessage(id = "a9", role = "assistant", text = "  ", thinking = null))
        assertTrue(messages.toTimelineItems().isEmpty())
    }

    @Test
    fun `live snapshot maps tools and streaming flag`() {
        val merger = ReplyMerger(idFactory = { "live-1" })
        merger.applyFrame(meta("""{"type":"text-delta","delta":"好了"}""")!!)
        merger.applyFrame(meta("""{"type":"tool-input-available","toolCallId":"x","toolName":"read","input":{"path":"a.ts"}}""")!!)
        val item = merger.snapshot()!!.toTimelineItem(streaming = true)
        assertEquals("好了", item.text)
        assertTrue(item.streaming)
        assertEquals("read", item.tools.single().name)

        val finished = merger.finish()!!.toTimelineItem(streaming = true)
        assertTrue("收尾后不再显示光标", !finished.streaming)
        assertNull(finished.errorText)
    }

    @Test
    fun `live item only shows the latest thinking segment`() {
        val merger = ReplyMerger(idFactory = { "live-2" })
        merger.applyFrame(meta("""{"type":"reasoning-start"}""")!!)
        merger.applyFrame(meta("""{"type":"reasoning-delta","delta":"第一段"}""")!!)
        merger.applyFrame(meta("""{"type":"reasoning-end"}""")!!)
        merger.applyFrame(meta("""{"type":"reasoning-start"}""")!!)
        merger.applyFrame(meta("""{"type":"reasoning-delta","delta":"第二段"}""")!!)
        val snapshot = merger.snapshot()!!
        assertEquals(true, snapshot.reasoning.startsWith("第一段") && snapshot.reasoning.endsWith("第二段"))
        assertEquals("第二段", snapshot.latestReasoning)
        // 运行中那张卡只放最新一段，前面那段在过程组里已经各有一张卡
        assertEquals("第二段", snapshot.toTimelineItem(streaming = true).thinking)
    }

    @Test
    fun `user images survive the mapping`() {
        val message = ChatMessage(
            id = "u-img",
            role = "user",
            text = "看下这张图",
            timestamp = 1_700_000_000_000,
            images = listOf(MessageImage(type = "image", data = "/9j/AAAA", mimeType = "image/jpeg")),
        )
        val user = listOf(message).toTimelineItems().single() as TimelineItem.UserMessage
        assertEquals(1, user.images.size)
        assertEquals("data:image/jpeg;base64,/9j/AAAA", user.images.single().dataUrl)
    }

    @Test
    fun `empty image payloads are dropped`() {
        val message = ChatMessage(
            id = "u-empt",
            role = "user",
            text = "只有文字",
            images = listOf(MessageImage(type = "image", data = "")),
        )
        assertTrue((listOf(message).toTimelineItems().single() as TimelineItem.UserMessage).images.isEmpty())
    }

    @Test
    fun `history json with images decodes`() {
        val json = """{"messages":[{"id":"m1","role":"user","text":"看下这张图","timestamp":1700000000000,
            "images":[{"type":"image","data":"/9j/AAAA","mimeType":"image/jpeg"}]}],"total":1}"""
        val page = PideckJson.decodeFromString(SessionMessagePage.serializer(), json)
        val images = (page.messages.toTimelineItems().single() as TimelineItem.UserMessage).images
        assertEquals(1, images.size)
        assertEquals("image/jpeg", images.single().mimeType)
    }

    @Test
    fun `tool result images are kept on the tool card`() {
        val message = ChatMessage(
            id = "t-img",
            role = "tool",
            text = "✓ imagegen",
            timestamp = 1_700_000_000_000,
            meta = buildJsonObject {
                put("toolName", JsonPrimitive("imagegen"))
                put("status", JsonPrimitive("done"))
                put("result", JsonPrimitive("saved to /tmp/a.png"))
            },
            images = listOf(MessageImage(type = "image", data = "/9j/AAAA", mimeType = "image/png")),
        )
        val item = listOf(message).toTimelineItems().single() as TimelineItem.AssistantMessage
        assertEquals(1, item.tools.single().images.size)
        assertEquals("image/png", item.tools.single().images.single().mimeType)
    }

    @Test
    fun `history json with tool images decodes`() {
        val json = """{"messages":[{"id":"t1","role":"tool","text":"✓ imagegen","timestamp":1700000000000,
            "meta":{"toolName":"imagegen"},"images":[{"type":"image","data":"iVBORw0KGgo","mimeType":"image/png"}]}],"total":1}"""
        val page = PideckJson.decodeFromString(SessionMessagePage.serializer(), json)
        val item = page.messages.toTimelineItems().single() as TimelineItem.AssistantMessage
        assertEquals(1, item.tools.single().images.size)
    }

    @Test
    fun `think-replay wrapper is dropped, not shown as thinking`() {
        val raw = "<think-replay>上一轮的思考</think-replay>\n\n正文在这里"
        val (body, thinking) = splitThinking(raw)
        assertEquals("正文在这里", body)
        assertNull(thinking)
    }

    @Test
    fun `stray closing think-replay tag is dropped but body stays`() {
        val raw = "命令：git commit 不带 -a 会只提交</think-replay>"
        val (body, thinking) = splitThinking(raw)
        assertEquals("命令：git commit 不带 -a 会只提交", body)
        assertNull(thinking)
    }

    @Test
    fun `shorter think tag works too`() {
        val (body, thinking) = splitThinking("<think>想一下</think>答案是 42")
        assertEquals("答案是 42", body)
        assertEquals("想一下", thinking)
    }

    @Test
    fun `unclosed think-replay is dropped too`() {
        val (body, thinking) = splitThinking("先说一句<THINK-REPLAY>还没写完的思考")
        assertEquals("先说一句", body)
        assertNull(thinking)
    }

    @Test
    fun `internal custom message with display false is dropped`() {
        val json = """{"messages":[
            {"id":"g1","role":"system","text":"Continue the current goal. Rounds used: 0/32",
             "meta":{"type":"customMessage","customType":"pi-deck-goal-continue","display":false}},
            {"id":"a1","role":"assistant","text":"好的"}],"total":2}"""
        val page = PideckJson.decodeFromString(SessionMessagePage.serializer(), json)
        val items = page.messages.toTimelineItems()
        assertEquals(1, items.size)
        assertEquals("msg-a1", items.single().key)
    }

    @Test
    fun `custom message with display true is kept as text`() {
        val json = """{"messages":[{"id":"g2","role":"system","text":"子代理跑完了",
            "meta":{"type":"customMessage","customType":"subagent-done","display":true}}],"total":1}"""
        val page = PideckJson.decodeFromString(SessionMessagePage.serializer(), json)
        val item = page.messages.toTimelineItems().single() as TimelineItem.AssistantMessage
        assertEquals("子代理跑完了", item.text)
    }

    @Test
    fun `internal custom entry is dropped but extension output stays`() {
        val json = """{"messages":[
            {"id":"c1","role":"system","text":"snapshot",
             "meta":{"type":"customEntry","customType":"pi-deck-plan"}},
            {"id":"c2","role":"system","text":"扩展输出：已生成 3 个文件",
             "meta":{"type":"customEntry","customType":"my-ext-report"}}],"total":2}"""
        val page = PideckJson.decodeFromString(SessionMessagePage.serializer(), json)
        val items = page.messages.toTimelineItems()
        assertEquals(1, items.size)
        assertEquals("msg-c2", items.single().key)
    }

    @Test
    fun `plain system message stays visible`() {
        val json = """{"messages":[{"id":"s1","role":"system","text":"重启了运行时"}],"total":1}"""
        val page = PideckJson.decodeFromString(SessionMessagePage.serializer(), json)
        assertEquals(1, page.messages.toTimelineItems().size)
    }
    @Test
    fun `guard-note and think-frag pairs never reach the screen`() {
        val raw = "前文<guard-note>内部说明</guard-note>中段<think-frag>约束：别写短句</think-frag>后文"
        val (body, thinking) = splitThinking(raw)
        assertEquals("前文中段后文", body)
        assertNull(thinking)
    }

    @Test
    fun `thinking field loses its tag shell`() {
        val messages = listOf(
            ChatMessage(id = "a9", role = "assistant", text = "", thinking = "<thinking>已登录 GitHub</thinking>"),
        )
        val item = messages.toTimelineItems().single() as TimelineItem.AssistantMessage
        assertEquals("已登录 GitHub", item.thinking)
    }

    @Test
    fun `display false on an unknown projection type is dropped`() {
        val json = """{"messages":[
            {"id":"x1","role":"system","text":"内部注入",
             "meta":{"type":"someFutureType","display":false}},
            {"id":"a1","role":"assistant","text":"正常回复"}],"total":2}"""
        val page = PideckJson.decodeFromString(SessionMessagePage.serializer(), json)
        assertEquals(listOf("msg-a1"), page.messages.toTimelineItems().map { it.key })
    }
    @Test
    fun `any tag with an internal root is dropped`() {
        val raw = "甲<guard-xyz>内部乙</guard-xyz>丙<think-whatever>内部丁</think-whatever>戊"
        val (body, thinking) = splitThinking(raw)
        assertEquals("甲丙戊", body)
        assertNull(thinking)
    }

    @Test
    fun `ordinary html tag stays untouched`() {
        val raw = "看这段 <div>内容</div> 就够了"
        val (body, thinking) = splitThinking(raw)
        assertEquals(raw, body)
        assertNull(thinking)
    }

    @Test
    fun `visible thinking tags still go to the thinking block`() {
        val raw = "<thinking>想一下</thinking>正文"
        val (body, thinking) = splitThinking(raw)
        assertEquals("正文", body)
        assertEquals("想一下", thinking)
    }

    @Test
    fun `thinking field drops internal blocks`() {
        val messages = listOf(
            ChatMessage(
                id = "a10",
                role = "assistant",
                text = "",
                thinking = "先想<guard-note>内部说明</guard-note>再写",
            ),
        )
        val item = messages.toTimelineItems().single() as TimelineItem.AssistantMessage
        assertEquals("先想再写", item.thinking)
    }

    @Test
    fun `plain thinking field is kept as is`() {
        val messages = listOf(
            ChatMessage(id = "a11", role = "assistant", text = "答", thinking = "no tags here"),
        )
        val item = messages.toTimelineItems().single() as TimelineItem.AssistantMessage
        assertEquals("no tags here", item.thinking)
    }

    @Test
    fun `unbalanced thinking tags never leak the tag into the body`() {
        // 真实样本：一条消息里 12 个 <thinking>、只有 4 个 </thinking>。
        // 旧实现在「上一个还没闭合又来新的开标签」时会把标签本身抄进正文，还会重复段落。
        val raw = "<thinking>甲<thinking>乙</thinking>正文<thinking>丙"
        val (body, thinking) = splitThinking(raw)
        assertEquals("正文", body)
        assertTrue("甲   乙   丙".split("   ").all { thinking?.contains(it.trim()) == true })
    }
}
