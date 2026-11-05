package dev.pideck.nativeapp.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileIndexTest {

    @Test
    fun `noise directories are skipped`() {
        listOf(".git", "node_modules", "build", "dist", ".gradle", "Pods", "__pycache__").forEach {
            assertTrue("应跳过 $it", FileIndex.shouldSkipDir(it))
        }
        listOf("src", "app", "native", "docs", ".github").forEach {
            assertFalse("不该跳过 $it", FileIndex.shouldSkipDir(it))
        }
        // 大小写不敏感：桌面上有 Build/ 和 build/ 混着的情况
        assertTrue(FileIndex.shouldSkipDir("NODE_MODULES"))
    }

    @Test
    fun `rank prefers exact name over prefix over contains`() {
        val files = listOf(
            FileNode("ChatScreenExtra.kt", "ui/ChatScreenExtra.kt", false),
            FileNode("chat", "ui/chat", true),
            FileNode("ChatScreen.kt", "ui/ChatScreen.kt", false),
            FileNode("notes.md", "docs/notes.md", false),
        )
        val hit = FileIndex.rank(files, "chatscreen.kt")
        assertEquals("ui/ChatScreen.kt", hit.first().path)
    }

    @Test
    fun `rank falls back to path matching`() {
        val files = listOf(
            FileNode("index.ts", "native/src/index.ts", false),
            FileNode("index.ts", "app/src/index.ts", false),
        )
        val hit = FileIndex.rank(files, "native/src")
        assertEquals("native/src/index.ts", hit.first().path)
    }

    @Test
    fun `empty query lists directories first and shallow paths before deep ones`() {
        val files = listOf(
            FileNode("Deep.kt", "a/b/c/Deep.kt", false),
            FileNode("Root.kt", "Root.kt", false),
            FileNode("pkg", "a", true),
        )
        val hit = FileIndex.rank(files, "")
        assertEquals("a", hit.first().path)
        assertEquals("Root.kt", hit[1].path)
        assertEquals("a/b/c/Deep.kt", hit[2].path)
    }

    @Test
    fun `rank respects the limit`() {
        val files = (1..50).map { FileNode("f$it.kt", "src/f$it.kt", false) }
        assertEquals(24, FileIndex.rank(files, "").size)
        assertEquals(3, FileIndex.rank(files, "", limit = 3).size)
    }

    @Test
    fun `subsequence matching`() {
        assertTrue(FileIndex.isSubsequence("cskt", "chatscreen.kt"))
        assertFalse(FileIndex.isSubsequence("ktcs", "chatscreen.kt"))
        assertTrue(FileIndex.isSubsequence("", "anything"))
    }

    @Test
    fun `parent of path returns the directory`() {
        assertEquals("native/app", FileIndex.parentOf("native/app/Main.kt"))
        assertEquals("", FileIndex.parentOf("Main.kt"))
    }

    @Test
    fun `host commands parse out of the runtime envelope`() {
        val raw = Json.parseToJsonElement(
            """
            {"result":{"ok":true,"value":{"target":{"sessionId":"s"},"value":[
              {"name":"plan","description":"切换计划模式","source":"extension"},
              {"name":"skill:image-gen","description":"生成图片","source":"skill"},
              {"description":"没有名字的项会被丢掉","source":"extension"}
            ]}}}
            """.trimIndent(),
        )
        val commands = parseHostCommands(raw)
        assertEquals(2, commands.size)
        assertEquals("plan", commands[0].name)
        assertEquals("扩展", commands[0].sourceLabel)
        assertEquals("image-gen", commands[1].displayName)
        assertEquals("技能", commands[1].sourceLabel)
    }

    @Test
    fun `host commands parse tolerates garbage`() {
        assertTrue(parseHostCommands(null).isEmpty())
        assertTrue(parseHostCommands(Json.parseToJsonElement("{}")).isEmpty())
        assertTrue(parseHostCommands(Json.parseToJsonElement("""{"result":{"ok":false}}""")).isEmpty())
    }
}
