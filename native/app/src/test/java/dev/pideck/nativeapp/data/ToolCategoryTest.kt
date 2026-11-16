package dev.pideck.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 归类表必须与桌面端 `toolActivityCategory` 完全一致（asar 的 `mermaid-*.js`）：
 * 组头图标、「读了几次」这类摘要都按它算，两边分叉会让同一回合显示成两种说法。
 */
class ToolCategoryTest {
    @Test
    fun `reads are reads`() {
        assertEquals(ToolCategory.Read, ToolCategoryMap.of("read"))
        assertEquals(ToolCategory.ReadImage, ToolCategoryMap.of("read_image"))
    }

    @Test
    fun `bash family is commands`() {
        assertEquals(ToolCategory.Commands, ToolCategoryMap.of("bash"))
        assertEquals(ToolCategory.Commands, ToolCategoryMap.of("Bash"))
        assertEquals(ToolCategory.Commands, ToolCategoryMap.of("pwsh"))
        assertEquals(ToolCategory.Commands, ToolCategoryMap.of("terminal_run"))
        assertEquals(ToolCategory.Commands, ToolCategoryMap.of("write_stdin"))
    }

    @Test
    fun `editing tools`() {
        assertEquals(ToolCategory.Edit, ToolCategoryMap.of("edit"))
        assertEquals(ToolCategory.Edit, ToolCategoryMap.of("multi_edit"))
        assertEquals(ToolCategory.Edit, ToolCategoryMap.of("apply_patch"))
        assertEquals(ToolCategory.Write, ToolCategoryMap.of("write"))
        assertEquals(ToolCategory.Write, ToolCategoryMap.of("create"))
    }

    @Test
    fun `search family`() {
        assertEquals(ToolCategory.Search, ToolCategoryMap.of("grep"))
        assertEquals(ToolCategory.Search, ToolCategoryMap.of("glob"))
        assertEquals(ToolCategory.Search, ToolCategoryMap.of("ls"))
        assertEquals(ToolCategory.Search, ToolCategoryMap.of("mcp__docs_inspect"))
    }

    @Test
    fun `web tools`() {
        assertEquals(ToolCategory.WebSearch, ToolCategoryMap.of("web_search"))
        assertEquals(ToolCategory.WebFetch, ToolCategoryMap.of("web_fetch"))
        assertEquals(ToolCategory.WebFetch, ToolCategoryMap.of("fetch"))
    }

    @Test
    fun `plan questions subagents`() {
        assertEquals(ToolCategory.Plan, ToolCategoryMap.of("todo_write"))
        assertEquals(ToolCategory.Plan, ToolCategoryMap.of("update_goal"))
        assertEquals(ToolCategory.Questions, ToolCategoryMap.of("ask_question"))
        assertEquals(ToolCategory.Subagents, ToolCategoryMap.of("subagent"))
        assertEquals(ToolCategory.Subagents, ToolCategoryMap.of("subagent_run"))
        // 桌面端的 EXACT 表里没有 acp_delegate（只有 subagent / agent / task），这里也不自作主张
        assertEquals(ToolCategory.Tools, ToolCategoryMap.of("acp_delegate"))
    }

    @Test
    fun `unknown falls back to tools`() {
        assertEquals(ToolCategory.Tools, ToolCategoryMap.of("whatever"))
        assertEquals(ToolCategory.Tools, ToolCategoryMap.of("  "))
        assertEquals(ToolCategory.Tools, ToolCategoryMap.of("custom_tool"))
    }

    @Test
    fun `code and case insensitive`() {
        assertEquals(ToolCategory.Code, ToolCategoryMap.of("run_code"))
        assertEquals(ToolCategory.Read, ToolCategoryMap.of(" READ "))
    }
}
