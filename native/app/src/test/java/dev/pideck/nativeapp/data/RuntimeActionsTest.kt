package dev.pideck.nativeapp.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeActionsTest {

    private val json = Json

    @Test
    fun `context usage reads the reported percent`() {
        val root = json.parseToJsonElement(
            """{"contextTokens": 52000, "contextWindow": 200000, "contextPercent": 26.4, "permissionPreset": "read-only", "provider": "magpie"}""",
        )
        val usage = parseContextUsage(root)
        assertTrue(usage.known)
        assertEquals("26%", usage.percentLabel)
        assertEquals("52.0k / 200.0k", usage.detail)
        assertEquals("read-only", usage.permissionPreset)
        assertEquals("magpie", usage.provider)
    }

    @Test
    fun `context usage computes percent when the host omits it`() {
        val usage = parseContextUsage(
            json.parseToJsonElement("""{"contextTokens": 50000, "contextWindow": 100000}"""),
        )
        assertEquals(50.0, usage.contextPercent, 0.001)
        assertEquals("50%", usage.percentLabel)
    }

    @Test
    fun `context usage without a window is unknown, not zero percent`() {
        val usage = parseContextUsage(json.parseToJsonElement("""{"contextTokens": 12}"""))
        assertFalse(usage.known)
        assertNull(usage.percentLabel)
        assertEquals("上下文窗口未知", usage.detail)
    }

    @Test
    fun `context usage carries overflow and token counters`() {
        val usage = parseContextUsage(
            json.parseToJsonElement(
                """{"contextTokens": 210000, "contextWindow": 200000, "contextOverflow": true, "inputTokens": 1200, "outputTokens": 98000}""",
            ),
        )
        assertTrue(usage.overflow)
        assertEquals(1200, usage.inputTokens)
        assertEquals(98000, usage.outputTokens)
    }

    @Test
    fun `missing body degrades to empty usage`() {
        assertEquals(ContextUsage(), parseContextUsage(null))
        assertEquals(ContextUsage(), parseContextUsage(json.parseToJsonElement("{}")))
    }

    @Test
    fun `runtime state unwraps the state envelope`() {
        val root = json.parseToJsonElement("""{"state": {"contextWindow": 1000}}""")
        val inner = unwrapRuntimeState(root)!!.jsonObject
        assertEquals("1000", inner["contextWindow"].toString())
    }

    @Test
    fun `runtime state passes plain state through`() {
        val root = json.parseToJsonElement("""{"contextWindow": 1000}""")
        assertEquals("1000", unwrapRuntimeState(root)!!.jsonObject["contextWindow"].toString())
    }

    @Test
    fun `target body nests the runtime target`() {
        val body = runtimeTargetBody(RuntimeTarget("s1", "a1", 7))
        val target = body["target"]!!.jsonObject
        assertEquals("\"s1\"", target["sessionId"].toString())
        assertEquals("7", target["runtimeGeneration"].toString())
    }

    @Test
    fun `export file name keeps a safe title`() {
        val name = exportFileName("f421df54-44d4-48f0", "重构: 会话/导出")
        assertTrue(name.startsWith("pideck-重构-会话-导出-"))
        assertTrue(name.endsWith(".html"))
        assertFalse(name.contains(":"))
        assertFalse(name.contains("/"))
    }

    @Test
    fun `export file name falls back to the session id`() {
        val name = exportFileName("sess-1", "   ")
        assertTrue(name.startsWith("pideck-sess-1-"))
    }

    @Test
    fun `export payload must look like html`() {
        assertTrue(htmlLooksValid("<!DOCTYPE html><html><body>hi</body></html>"))
        assertFalse(htmlLooksValid(""))
        assertFalse(htmlLooksValid("{\"error\":\"nope\"}"))
    }

    @Test
    fun `permission presets match the desktop ids`() {
        assertEquals(listOf("read-only", "workspace-write", "danger-full-access"), PermissionPresets.all.map { it.id })
        assertEquals("只读", PermissionPresets.labelOf("read-only"))
        assertEquals("自定义 x", PermissionPresets.labelOf("自定义 x"))
        assertEquals("只读", PermissionPresets.labelOf("read-only"))
    }
}
