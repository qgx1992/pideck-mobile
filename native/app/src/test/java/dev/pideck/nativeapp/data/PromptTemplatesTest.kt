package dev.pideck.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 桌面端提示词模板（`GET /api/prompt-templates`，宿主 0.13.0 起）。
 *
 * 这份解析只做容错：宿主回什么形状都不能把面板弄崩 —— 老宿主压根没这条路由（404 抛出去，
 * 由 ViewModel 给一句「宿主太旧」），新宿主少了 `enabled` 时按启用处理。
 */
class PromptTemplatesTest {

    @Test
    fun `parses the template list`() {
        val json = """
            {"templates":[
              {"name":"todo-comment-debt-tracker","description":"扫描 TODO","scope":"global","enabled":true},
              {"name":"web-app-security","description":"OWASP 审查","scope":"project"}
            ]}
        """.trimIndent()
        val list = PromptTemplatesParse.list(json)
        assertEquals(2, list.size)
        assertEquals("todo-comment-debt-tracker", list[0].name)
        assertEquals("global", list[0].scope)
        assertTrue(list[0].enabled)
        // 没写 enabled 的按启用处理（老版本字段可能缺）
        assertTrue(list[1].enabled)
        assertEquals("project", list[1].scope)
    }

    @Test
    fun `entries without a name are dropped`() {
        val json = """{"templates":[{"description":"没有名字"},{"name":"ok"}]}"""
        assertEquals(listOf("ok"), PromptTemplatesParse.list(json).map { it.name })
    }

    @Test
    fun `disabled templates keep their flag`() {
        val json = """{"templates":[{"name":"a","enabled":false}]}"""
        assertFalse(PromptTemplatesParse.list(json).single().enabled)
    }

    @Test
    fun `broken payload gives an empty list instead of throwing`() {
        assertTrue(PromptTemplatesParse.list("not json").isEmpty())
        assertTrue(PromptTemplatesParse.list("""{"templates":"oops"}""").isEmpty())
    }

    @Test
    fun `reads the template body from detail`() {
        val json = """{"detail":{"name":"a","description":"d","scope":"global","content":"# 标题\n正文"}}"""
        assertEquals("# 标题\n正文", PromptTemplatesParse.content(json))
    }

    @Test
    fun `blank body counts as missing`() {
        assertNull(PromptTemplatesParse.content("""{"detail":{"content":"   "}}"""))
        assertNull(PromptTemplatesParse.content("{}"))
    }
}
