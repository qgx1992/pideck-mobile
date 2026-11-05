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
    fun `privilege slot is dispatched by backend`() {
        // pi（以及草稿会话那种 backend 空着的）→ 安全等级；dsh → 权限预设；其余不摆位。
        assertEquals(PrivilegeControl.SecurityLevel, privilegeControlFor("pi"))
        assertEquals(PrivilegeControl.SecurityLevel, privilegeControlFor(null))
        assertEquals(PrivilegeControl.SecurityLevel, privilegeControlFor(""))
        assertEquals(PrivilegeControl.PermissionPreset, privilegeControlFor("dsh"))
        assertEquals(PrivilegeControl.PermissionPreset, privilegeControlFor("DSH"))
        assertEquals(PrivilegeControl.None, privilegeControlFor("acp"))
        assertEquals(PrivilegeControl.None, privilegeControlFor("imagegen"))
        assertEquals(PrivilegeControl.None, privilegeControlFor("videogen"))
    }

    @Test
    fun `security projection parses levels and the session override`() {
        val raw = json.parseToJsonElement(
            """
            {"enabled":true,"defaultLevelId":"standard","levels":[
              {"id":"off","name":"关闭","description":"完全放行","builtin":true},
              {"id":"standard","name":"标准","builtin":true},
              {"id":"strict","name":"严格","builtin":true}
            ],"sessionOverrides":{"s1":"strict"}}
            """.trimIndent(),
        )
        val security = parseSecurityState(raw)!!
        assertTrue(security.enabled)
        assertEquals(3, security.levels.size)
        assertEquals("完全放行", security.levels.first().description)
        assertEquals("standard", security.defaultLevelId)
        assertEquals("strict", security.overrideOf("s1"))
        assertNull(security.overrideOf("s2"))
        assertEquals("strict", security.effectiveId("s1"))
        assertEquals("standard", security.effectiveId("s2"))
        assertEquals("标准", security.labelOf("standard"))
    }

    @Test
    fun `security projection without levels is unavailable, not empty`() {
        // 老主机的 /api/state 没有 security；宁可显示「没提供」也不要摆一套假等级去点出 400
        assertNull(parseSecurityState(null))
        assertNull(parseSecurityState(json.parseToJsonElement("""{"enabled":true}""")))
        assertNull(parseSecurityState(json.parseToJsonElement("""{"levels":[]}""")))
    }

    @Test
    fun `security projection keeps the switch off state`() {
        val security = parseSecurityState(
            json.parseToJsonElement("""{"enabled":false,"defaultLevelId":"off","levels":[{"id":"off"}]}"""),
        )!!
        assertFalse(security.enabled)
        // name 缺省时用 id 顶位，不能显示空白行
        assertEquals("off", security.levelOf("off")!!.name)
    }

    @Test
    fun `permission presets match the desktop ids`() {
        assertEquals(listOf("read-only", "workspace-write", "danger-full-access"), PermissionPresets.all.map { it.id })
        assertEquals("只读", PermissionPresets.labelOf("read-only"))
        assertEquals("自定义 x", PermissionPresets.labelOf("自定义 x"))
        assertEquals("只读", PermissionPresets.labelOf("read-only"))
    }

    @Test
    fun `runtime state unwraps the result envelope`() {
        val raw = Json.parseToJsonElement(
            """
            {"result":{"ok":true,"value":{"target":{"sessionId":"s"},"value":{
              "modelName":"DeepSeek V4.1 Flash","provider":"magpie","thinkingLevel":"high",
              "isStreaming":false,"isCompacting":false,"contextTokens":14284,
              "contextWindow":1000000,"contextPercent":1.4284,"contextOverflow":false,
              "contextMessageTokens":3497,"contextSystemTokens":5615,"contextToolsTokens":5172,
              "inputTokens":22593,"outputTokens":286,"cacheRead":32000,"cacheWrite":0,
              "cacheHitPercent":98.9,"cost":0.0036,"ttftMs":1401
            }}}}
            """.trimIndent(),
        )
        val usage = parseContextUsage(unwrapRuntimeState(raw))
        assertTrue(usage.known)
        assertEquals(14284L, usage.contextTokens)
        assertEquals(1000000L, usage.contextWindow)
        assertEquals("1%", usage.percentLabel)
    }

    @Test
    fun `runtime state unwrap rejects a failed envelope`() {
        val raw = Json.parseToJsonElement("""{"result":{"ok":false,"error":"no runtime"}}""")
        assertNull(unwrapRuntimeState(raw))
        assertFalse(parseContextUsage(unwrapRuntimeState(raw)).known)
    }

    @Test
    fun `runtime state unwrap still accepts the legacy state wrapper`() {
        val raw = Json.parseToJsonElement("""{"state":{"contextTokens":100,"contextWindow":1000}}""")
        val usage = parseContextUsage(unwrapRuntimeState(raw))
        assertTrue(usage.known)
        assertEquals("10%", usage.percentLabel)
    }
}
