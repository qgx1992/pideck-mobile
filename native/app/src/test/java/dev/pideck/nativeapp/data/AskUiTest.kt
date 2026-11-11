package dev.pideck.nativeapp.data

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AskUiTest {

    private fun request(
        method: String,
        options: List<kotlinx.serialization.json.JsonElement> = emptyList(),
    ) = PendingUiRequest(
        sessionId = "s1",
        agentId = "a1",
        runtimeGeneration = 3,
        requestId = "r1",
        method = method,
        title = "要写哪个文件？",
        options = options,
        placeholder = "说明一下",
        prefill = "",
        allowOther = true,
    )

    @Test
    fun `select 答案回 value 字符串`() {
        val body = AskUi.response("select", AskUi.Answer.Text("src/a.kt"))
        assertEquals(JsonPrimitive("src/a.kt"), body["value"])
        assertFalse(body.containsKey("confirmed"))
    }

    @Test
    fun `confirm 答案回 confirmed`() {
        assertEquals(JsonPrimitive(true), AskUi.response("confirm", AskUi.Answer.Flag(true))["confirmed"])
        assertEquals(JsonPrimitive(false), AskUi.response("confirm", AskUi.Answer.Flag(false))["confirmed"])
    }

    @Test
    fun `multi_select 回字符串数组`() {
        val body = AskUi.response("multi_select", AskUi.Answer.Many(listOf("a", "b")))
        val array = body["value"] as kotlinx.serialization.json.JsonArray
        assertEquals(2, array.size)
    }

    @Test
    fun `取消回 cancelled`() {
        val body = AskUi.cancelled()
        assertEquals(JsonPrimitive(true), body["cancelled"])
    }

    @Test
    fun `ui-response 信封带四个定位字段`() {
        val body = AskUi.body(request("select"), AskUi.response("select", AskUi.Answer.Text("x")))
        assertEquals(JsonPrimitive("s1"), body["sessionId"])
        assertEquals(JsonPrimitive("r1"), body["requestId"])
        assertEquals(JsonPrimitive("a1"), body["agentId"])
        assertEquals(JsonPrimitive(3L), body["runtimeGeneration"])
        assertEquals(JsonPrimitive("x"), body["response"]?.let { (it as kotlinx.serialization.json.JsonObject)["value"] })
    }

    @Test
    fun `批量信封的 value 是字符串化的 answers`() {
        val questions = listOf(
            AskQuestion(id = "q1", type = "select", question = "第一个？"),
            AskQuestion(id = "q2", type = "input", question = "第二个？"),
        )
        val serialized = AskUi.serializeBatch(
            questions,
            mapOf("q1" to AskUi.Answer.Text("a"), "q2" to AskUi.Answer.Text("b")),
        )
        assertTrue(serialized.startsWith("{\"answers\""))
        val reopened = PideckJson.parseToJsonElement(serialized) as kotlinx.serialization.json.JsonObject
        val answers = reopened["answers"] as kotlinx.serialization.json.JsonArray
        assertEquals(2, answers.size)
        val body = AskUi.response("batch", AskUi.Answer.Text(serialized))
        assertTrue((body["value"] as JsonPrimitive).content.contains("\"q1\""))
    }

    @Test
    fun `选项既支持字符串也支持对象`() {
        val raw = buildJsonArray {
            add(JsonPrimitive("只有文字"))
            add(
                buildJsonObject {
                    put("label", "带说明")
                    put("value", "v1")
                    put("description", "解释一下")
                },
            )
        }.toList()

        val options = AskUi.options(raw)
        assertEquals(2, options.size)
        assertEquals("只有文字", options[0].value)
        assertEquals("v1", options[1].value)
        assertEquals("解释一下", options[1].description)
    }

    @Test
    fun `过程组摘要按类别聚合`() {
        assertEquals(
            "读取 ×2 · 编辑 ×1",
            ProcessActivity.summary(listOf("read", "read", "edit"), thinkingCount = 0, running = false),
        )
        assertTrue(
            ProcessActivity.summary(listOf("grep"), thinkingCount = 1, running = true).startsWith("正在"),
        )
        assertEquals("过程", ProcessActivity.summary(emptyList(), thinkingCount = 0, running = false))
    }
}
