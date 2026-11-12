package dev.pideck.nativeapp.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 本机模板（提示词库里的「本机模板」段）。
 *
 * 它填的是桌面端补不上的那个洞：桌面端「提示词」面板的 .md 模板只有 IPC，手机读不到；
 * 宿主那个云端提示词库又常常是空的。所以这里的约束要钉住：标题能省、正文不许空、
 * 删了就真删、存得下多段正文（换行不能被吃掉）。
 */
class LocalPromptsTest {

    @Before
    fun setUp() {
        LocalPrompts.resetForTest(null)
    }

    @After
    fun tearDown() {
        LocalPrompts.resetForTest(null)
    }

    @Test
    fun `没有存储时是空清单`() {
        assertTrue(LocalPrompts.load().isEmpty())
    }

    @Test
    fun `标题留空时用正文第一行`() {
        val items = LocalPrompts.add("", "\n\n  第一条非空行\n第二行")
        assertEquals(1, items.size)
        assertEquals("第一条非空行", items.single().title)
    }

    @Test
    fun `正文为空不新增`() {
        assertEquals(0, LocalPrompts.add("标题", "   \n  ").size)
    }

    @Test
    fun `多段正文与变量原样保留`() {
        val body = "第一段 {{file}}\n\n第二段\n  缩进保留"
        val prompt = LocalPrompts.add("标题", body).single()
        assertEquals(body, prompt.body)
        assertTrue(prompt.body.contains("{{file}}"))
    }

    @Test
    fun `删除按 id 精确删掉`() {
        LocalPrompts.add("A", "aaa")
        LocalPrompts.add("B", "bbb")
        val items = LocalPrompts.load()
        val kept = LocalPrompts.remove(items.first().id)
        assertEquals(1, kept.size)
        assertEquals("B", kept.single().title)
        assertFalse(kept.any { it.title == "A" })
    }

    @Test
    fun `超长正文按上限截断`() {
        val long = "长".repeat(LocalPrompts.MAX_BODY_LENGTH + 100)
        val prompt = LocalPrompts.add("长文", long).single()
        assertEquals(LocalPrompts.MAX_BODY_LENGTH, prompt.body.length)
    }

    @Test
    fun `正文里的字面反斜杠 n 存一次读回来不变`() {
        // 0.5.57 前用「换行折成字面 \n」的编码：模板里写 C / JSON 转义示例，
        // 存一次再读回来就断成两行，用户改不回去。
        val body = "printf(\"a\\nb\");\n正则: ^\\d+$"
        val raw = LocalPrompts.encode(listOf(LocalPrompts.Prompt(id = "p1", title = "转义", body = body)))
        val back = LocalPrompts.decode(raw)
        assertEquals(1, back.size)
        assertEquals(body, back.single().body)
    }

    @Test
    fun `升级前存的老格式还能读出来`() {
        val legacy = "p1\u001f旧标题\u001f第一行\\n第二行"
        val back = LocalPrompts.decode(legacy)
        assertEquals(1, back.size)
        assertEquals("旧标题", back.single().title)
        assertEquals("第一行\n第二行", back.single().body)
    }
}
