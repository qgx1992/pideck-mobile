package dev.pideck.nativeapp.data

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceParserTest {

    /** Windows 路径的分隔符用 Char 拼，别在源码里写反斜杠（转义太容易错）。 */
    private val B = Char(92).toString()

    @Test
    fun `todo 计划解析出完成数与进行项`() {
        val root = buildJsonObject {
            putJsonObject("todo") {
                put("planId", 9)
                put(
                    "todos",
                    buildJsonArray {
                        add(item(1, "a", "completed"))
                        add(item(2, "b", "in_progress"))
                        add(item(3, "c", "pending"))
                    },
                )
            }
        }
        val todo = WorkspaceParser.todo(root)!!
        assertEquals(9, todo.planId)
        assertEquals(3, todo.todos.size)
        assertEquals(1, todo.done)
        assertEquals("b", todo.active?.text)
    }

    @Test
    fun `没有计划时是 null`() {
        assertNull(WorkspaceParser.todo(buildJsonObject { put("todo", null as String?) }))
        assertNull(WorkspaceParser.todo(buildJsonObject { }))
    }

    @Test
    fun `git status 的绝对路径裁成相对路径`() {
        val root = buildJsonObject {
            put("repo", true)
            putJsonObject("branch") { put("current", "master") }
            putJsonObject("groups") {
                put("index", buildJsonArray { })
                put(
                    "workingTree",
                    buildJsonArray { add(file("D:" + B + "repo" + B + "src" + B + "a.ts", "M")) },
                )
                put(
                    "untracked",
                    buildJsonArray { add(file("D:" + B + "repo" + B + ".tmp" + B + "x.txt", "U")) },
                )
            }
        }
        val changes = WorkspaceParser.changes(root, "D:" + B + "repo")
        assertTrue(changes.repo)
        assertEquals("master", changes.branch)
        assertEquals(2, changes.files.size)
        assertEquals("src/a.ts", changes.files[0].path)
        assertEquals("M", changes.files[0].letter)
        assertEquals("workingTree", changes.files[0].group)
        assertEquals(".tmp/x.txt", changes.files[1].path)
        assertEquals("untracked", changes.files[1].group)
    }

    @Test
    fun `非 git 仓库时没有文件`() {
        val changes = WorkspaceParser.changes(buildJsonObject { put("repo", false) }, null)
        assertEquals(false, changes.repo)
        assertTrue(changes.isEmpty)
    }

    @Test
    fun `文件列表把目录排在前面`() {
        val root = buildJsonObject {
            put(
                "nodes",
                buildJsonArray {
                    add(node("zeta.md", "zeta.md", "file"))
                    add(node("src", "src", "directory"))
                    add(node("Alpha.kt", "Alpha.kt", "file"))
                },
            )
        }
        val files = WorkspaceParser.files(root)
        assertEquals("src", files[0].name)
        assertTrue(files[0].isDir)
        assertEquals("Alpha.kt", files[1].name)
        assertEquals("zeta.md", files[2].name)
    }

    @Test
    fun `缺 projectPath 时保留原始路径`() {
        assertEquals("src/a.ts", WorkspaceParser.relativize("src" + B + "a.ts", null))
    }

    private fun item(id: Int, text: String, status: String) = buildJsonObject {
        put("id", id)
        put("text", text)
        put("status", status)
    }

    private fun file(path: String, letter: String) = buildJsonObject {
        put("path", path)
        put("letter", letter)
    }

    private fun node(name: String, relativePath: String, type: String) = buildJsonObject {
        put("name", name)
        put("relativePath", relativePath)
        put("type", type)
    }
}
