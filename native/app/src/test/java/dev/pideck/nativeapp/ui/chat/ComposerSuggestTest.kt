package dev.pideck.nativeapp.ui.chat

import dev.pideck.nativeapp.data.FileNode
import dev.pideck.nativeapp.data.HostCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerSuggestTest {

    @Test
    fun `at sign right after whitespace starts a mention`() {
        val trigger = detectTrigger("帮我看下 @Turn")
        assertTrue(trigger is ComposerTrigger.Mention)
        assertEquals("Turn", (trigger as ComposerTrigger.Mention).query)
        assertEquals(5, trigger.start)
    }

    @Test
    fun `at sign inside a word is not a mention`() {
        assertNull(detectTrigger("发到 a@b.com 就行"))
    }

    @Test
    fun `mention closes once whitespace is typed`() {
        assertNull(detectTrigger("@native/app "))
    }

    @Test
    fun `mention applies over the typed stub`() {
        val text = "看下 @Chat"
        val trigger = detectTrigger(text) as ComposerTrigger.Mention
        val node = FileNode("ChatScreen.kt", "native/app/src/ChatScreen.kt", false)
        assertEquals("看下 @native/app/src/ChatScreen.kt ", applyMention(text, trigger, node))
    }

    @Test
    fun `mention of a directory keeps the trailing slash`() {
        val text = "看 @nat"
        val trigger = detectTrigger(text) as ComposerTrigger.Mention
        val node = FileNode("native", "native", true)
        assertEquals("看 @native/ ", applyMention(text, trigger, node))
    }

    @Test
    fun `mention of a path with spaces is quoted`() {
        val text = "@my"
        val trigger = detectTrigger(text) as ComposerTrigger.Mention
        val node = FileNode("my file.kt", "src/my file.kt", false)
        assertEquals("@\"src/my file.kt\" ", applyMention(text, trigger, node))
    }

    @Test
    fun `slash command only at the very start`() {
        val trigger = detectTrigger("/comp")
        assertTrue(trigger is ComposerTrigger.Slash)
        assertEquals("comp", (trigger as ComposerTrigger.Slash).query)
        assertNull(detectTrigger("说明 /compact 怎么用"))
    }

    @Test
    fun `slash intent requires exact command name`() {
        assertEquals("compact", slashIntent("/compact")?.id)
        assertEquals("compact", slashIntent("  /compact 只留结论 ")?.id)
        assertNull(slashIntent("/compacter"))
        assertNull(slashIntent("/compactx 参数"))
        assertNull(slashIntent("普通文本"))
    }

    @Test
    fun `slash argument strips the command`() {
        val command = BUILTIN_SLASH_COMMANDS.first { it.id == "compact" }
        assertEquals("只留结论", slashArgument("/compact 只留结论", command))
        assertEquals("", slashArgument("/compact", command))
    }

    @Test
    fun `mention filter prefers directories then short paths`() {
        val files = listOf(
            FileNode("ChatScreen.kt", "native/app/ui/chat/ChatScreen.kt", false),
            FileNode("chat", "native/app/ui/chat", true),
            FileNode("README.md", "README.md", false),
        )
        val hit = filterMentionFiles(files, "chat")
        assertEquals("native/app/ui/chat", hit.first().path)
        assertTrue(hit.first().isDir)
        assertEquals(2, hit.size)
    }

    @Test
    fun `mention filter caps the list when query empty`() {
        val files = (1..40).map { FileNode("f$it.kt", "src/f$it.kt", false) }
        assertEquals(24, filterMentionFiles(files, "").size)
    }

    @Test
    fun `fuzzy mention matches a subsequence`() {
        val files = listOf(
            FileNode("ChatScreen.kt", "native/ui/ChatScreen.kt", false),
            FileNode("Notes.md", "docs/Notes.md", false),
        )
        val hit = filterMentionFiles(files, "cskt")
        assertEquals("native/ui/ChatScreen.kt", hit.first().path)
    }

    @Test
    fun `slash entries start with the two builtins`() {
        val entries = commandEntries(emptyList(), "")
        assertEquals(listOf("/new", "/compact"), entries.map { it.label })
        assertEquals(listOf("new", "compact"), entries.map { it.insert })
    }

    @Test
    fun `slash filter matches by prefix then host commands`() {
        assertEquals(listOf("compact"), commandEntries(emptyList(), "co").map { it.insert })
        assertTrue(commandEntries(emptyList(), "zzz").isEmpty())
    }

    @Test
    fun `host commands are listed with their source badge`() {
        val hosts = listOf(
            HostCommand("plan", "切换 PiDeck 计划模式", "extension"),
            HostCommand("skill:image-gen", "生成图片", "skill"),
        )
        val entries = commandEntries(hosts, "")
        assertEquals("/plan", entries[2].label)
        assertEquals("扩展", entries[2].badge)
        // 技能插进输入框的必须是全名（裸 /影像名 pi 会拒），显示时才去掉前缀
        assertEquals("skill:image-gen", entries[3].insert)
        assertEquals("/image-gen", entries[3].label)
        assertEquals("技能", entries[3].badge)
    }

    @Test
    fun `builtin pick keeps the compact draft insert`() {
        val compact = commandEntries(emptyList(), "compact").first()
        assertEquals("compact", compact.insert)
        assertEquals("compact", compact.builtin)
    }
}
