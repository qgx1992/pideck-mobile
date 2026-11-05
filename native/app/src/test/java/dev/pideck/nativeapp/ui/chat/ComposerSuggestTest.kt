package dev.pideck.nativeapp.ui.chat

import dev.pideck.nativeapp.data.FileNode
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
        assertEquals("看下 @native/app/src/ChatScreen.kt ", applyMention(text, trigger, "native/app/src/ChatScreen.kt"))
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
    fun `mention filter returns first entries when query empty`() {
        val files = (1..12).map { FileNode("f$it.kt", "src/f$it.kt", false) }
        assertEquals(8, filterMentionFiles(files, "").size)
    }

    @Test
    fun `slash filter matches by prefix`() {
        assertEquals(listOf("compact"), filterSlashCommands("co").map { it.id })
        assertEquals(BUILTIN_SLASH_COMMANDS.size, filterSlashCommands("").size)
        assertTrue(filterSlashCommands("zzz").isEmpty())
    }
}
