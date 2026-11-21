package dev.pideck.nativeapp.ui.chat

import dev.pideck.nativeapp.data.ChangedFile
import dev.pideck.nativeapp.data.Device
import dev.pideck.nativeapp.data.FakePideckApi
import dev.pideck.nativeapp.data.LineDiff
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `DiffController` 的行为测试（它是从 `ChatViewModel` 拆出来的第一块，之前只有纯函数测试）。
 *
 * 重点盯「快速点两个文件」：没有序号守卫时会留下「标题是 B、内容是 A」。
 */
class DiffControllerTest {

    private val device = Device(id = "d1", name = "本机", baseUrl = "http://127.0.0.1:8765")

    private inner class FakeDiffApi(private val slowSuffix: String? = null) : FakePideckApi() {
        override suspend fun fileDiff(
            device: Device,
            token: String?,
            projectId: String,
            group: String,
            path: String,
        ): LineDiff.FileDiffPayload {
            if (slowSuffix != null && path.endsWith(slowSuffix)) delay(80)
            return LineDiff.FileDiffPayload(
                path = path,
                original = "old-" + path,
                modified = "new-" + path,
            )
        }
    }

    private fun controller(scope: kotlinx.coroutines.CoroutineScope, api: FakePideckApi) = DiffController(
        scope = scope,
        api = { api },
        device = { device },
        token = { "token" },
        projectId = { "p1" },
    )

    @Test
    fun `opening a file fills the diff and clears the spinner`() = runTest {
        val controller = controller(this, FakeDiffApi())
        controller.open(ChangedFile(path = "src/A.kt", group = "workingTree", letter = "M"))
        advanceUntilIdle()

        assertEquals("src/A.kt", controller.path)
        assertEquals("workingTree", controller.group)
        assertFalse(controller.busy)
        assertTrue(controller.text.isNotEmpty())
        assertTrue(controller.text.contains("src/A.kt"))
    }

    @Test
    fun `fast clicking two files keeps the last one`() = runTest {
        // A 慢 B 快：没有序号守卫时 A 后返回会覆盖 B 的内容。
        val controller = controller(this, FakeDiffApi(slowSuffix = "A.kt"))
        controller.open(ChangedFile(path = "src/A.kt", group = "workingTree", letter = "M"))
        controller.open(ChangedFile(path = "src/B.kt", group = "workingTree", letter = "M"))
        advanceUntilIdle()

        assertEquals("src/B.kt", controller.path)
        assertTrue("B 的内容不该被 A 覆盖：" + controller.text, controller.text.contains("src/B.kt"))
        assertFalse(controller.text.contains("src/A.kt"))
    }

    @Test
    fun `close clears the selected file`() = runTest {
        val controller = controller(this, FakeDiffApi())
        controller.open(ChangedFile(path = "src/A.kt", group = "workingTree", letter = "M"))
        advanceUntilIdle()
        controller.close()

        assertEquals(null, controller.path)
        assertEquals("", controller.text)
    }
}
