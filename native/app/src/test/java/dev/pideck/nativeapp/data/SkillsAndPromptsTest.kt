package dev.pideck.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 技能 / 扩展 / 提示词 / 提交历史的解析与排序。
 *
 * 这些回包都来自宿主 HTTP，字段名写死在服务端代码里（`/api/skills`、`/api/extensions`、
 * `/api/prompts`、`/api/git/log`）。测试用真实抓到的形状当样本，改服务端时这里会先红。
 */
class SkillsAndPromptsTest {

    private val skillsJson = """
        {"locations":[{"id":"pi-global","label":"~/.pi/agent/skills","rootMarkdownEnabled":true},
                      {"id":"pideck-global","label":"~/.pi/agent/pideck-skills","rootMarkdownEnabled":true}],
         "skills":[
           {"id":"a:image-gen/SKILL.md","name":"image-gen","description":"生成图片","sourceId":"pideck-global",
            "sourceLabel":"~/.pi/agent/pideck-skills","type":"directory","userOnly":false,
            "enabled":true,"valid":true,"warnings":[]},
           {"id":"a:broken/SKILL.md","name":"broken","description":"","sourceId":"pi-global",
            "sourceLabel":"~/.pi/agent/skills","type":"directory","userOnly":false,
            "enabled":false,"valid":false,"warnings":["frontmatter 缺 name"]}
         ]}
    """.trimIndent()

    @Test
    fun `解析技能清单与目录位置`() {
        val payload = SkillsParse.skills(skillsJson)
        assertEquals(2, payload.locations.size)
        assertEquals("pi-global", payload.locations.first().id)
        assertEquals(2, payload.skills.size)
        val imageGen = payload.skills.first { it.name == "image-gen" }
        assertTrue(imageGen.enabled)
        assertTrue(imageGen.valid)
        assertTrue(imageGen.warnings.isEmpty())
        val broken = payload.skills.first { it.name == "broken" }
        assertFalse(broken.valid)
        assertEquals(listOf("frontmatter 缺 name"), broken.warnings)
    }

    @Test
    fun `技能排序把失效项沉底、启用的在前`() {
        val sorted = SkillsParse.sortedSkills(SkillsParse.skills(skillsJson).skills)
        assertEquals(listOf("image-gen", "broken"), sorted.map { it.name })
    }

    @Test
    fun `技能清单坏掉的回包不炸`() {
        val payload = SkillsParse.skills("<html>401</html>")
        assertTrue(payload.skills.isEmpty())
        assertTrue(payload.locations.isEmpty())
    }

    // ---- 扩展 ----

    private val extensionsJson = """
        {"extensions":[
          {"id":"user:npm:billion-context","source":"npm:billion-context","scope":"user","enabled":true,"filtered":false},
          {"id":"local:magpie.ts","source":"magpie.ts","scope":"user","builtIn":false,"enabled":false,"filtered":false},
          {"id":"local:pi-deck-ask-question.ts","source":"pi-deck-ask-question.ts","scope":"user","builtIn":true,
           "enabled":true,"currentVersion":"1.0.3","filtered":true}
        ],"conflicts":["magpie.ts"]}
    """.trimIndent()

    @Test
    fun `解析扩展与冲突`() {
        val payload = SkillsParse.extensions(extensionsJson)
        assertEquals(3, payload.extensions.size)
        assertEquals(listOf("magpie.ts"), payload.conflicts)
        val builtin = payload.extensions.first { it.builtIn }
        assertEquals("1.0.3", builtin.currentVersion)
        assertTrue(builtin.filtered)
        assertFalse(payload.extensions.first { it.source == "magpie.ts" }.enabled)
    }

    @Test
    fun `扩展排序启用优先，未启用的沉底`() {
        val sorted = SkillsParse.sortedExtensions(SkillsParse.extensions(extensionsJson).extensions)
        assertEquals("npm:billion-context", sorted.first().source)
        assertFalse(sorted.last().enabled)
        assertEquals(3, sorted.size)
    }

    // ---- 提示词 ----

    @Test
    fun `解析提示词一页`() {
        val json = """
            {"categories":["写作","代码"],"prompts":[
              {"slug":"polish","title":"润色","description":"改通顺","category":"写作"},
              {"slug":"review","title":"代码审查","description":"","category":"代码"}
            ],"page":1,"pageSize":2}
        """.trimIndent()
        val page = PromptsParse.page(json)
        assertEquals(listOf("写作", "代码"), page.categories)
        assertEquals(2, page.prompts.size)
        assertEquals("润色", page.prompts.first().title)
        // pageSize 2 且正好装满 → 还有下一页（服务端不返回 total，只能这样判）
        assertTrue(page.hasMore)
    }

    @Test
    fun `宿主给了 total 就按总数判还有没有下一页`() {
        val json = """
            {"categories":[],"prompts":[{"slug":"a","title":"A"},{"slug":"b","title":"B"}],
             "total":2,"page":1,"pageSize":2}
        """.trimIndent()
        // 这一页正好填满，但总数就是 2 → 不该再自动翻页（否则会无限空转）
        assertFalse(PromptsParse.page(json).hasMore)
        val more = json.replace("\"total\":2", "\"total\":7")
        assertTrue(PromptsParse.page(more).hasMore)
    }

    @Test
    fun `空库时不会显示还有下一页`() {
        val page = PromptsParse.page("""{"categories":[],"prompts":[],"page":1,"pageSize":20}""")
        assertTrue(page.prompts.isEmpty())
        assertFalse(page.hasMore)
    }

    @Test
    fun `取提示词正文`() {
        val json = """{"detail":{"title":"润色","description":"改通顺","promptContent":"把下面这段改通顺："}}"""
        assertEquals("把下面这段改通顺：", PromptsParse.content(json))
        assertNull(PromptsParse.content("""{"detail":{}}"""))
        assertNull(PromptsParse.content("not json"))
    }

    // ---- 提交历史 ----

    @Test
    fun `解析提交历史，多行信息只留标题`() {
        val json = """
            {"commits":[
              {"hash":"9c52f5f53f0471aa609adcbb3912487f175d83a5","shortHash":"9c52f5f",
               "message":"fix(tokens): 读写失败自动重建\n\n正文第二段","authorName":"qgx1992",
               "authorDate":1794049271000,"parents":["78090ee"],"refNames":["HEAD -> master","origin/master"],
               "graph":[]}
            ]}
        """.trimIndent()
        val commits = GitLogParser.commits(json)
        assertEquals(1, commits.size)
        val commit = commits.first()
        assertEquals("9c52f5f", commit.shortHash)
        assertEquals("fix(tokens): 读写失败自动重建", commit.title)
        assertEquals(1794049271000L, commit.authorDateMs)
        assertEquals("HEAD -> master, origin/master", commit.refs)
    }

    @Test
    fun `空提交历史与非 json 都给空列表`() {
        assertTrue(GitLogParser.commits("""{"commits":[]}""").isEmpty())
        assertTrue(GitLogParser.commits("fatal: not a git repository").isEmpty())
    }
}
