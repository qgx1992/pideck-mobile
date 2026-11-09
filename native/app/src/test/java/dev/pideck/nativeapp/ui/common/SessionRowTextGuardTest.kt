package dev.pideck.nativeapp.ui.common

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 会话行有**四**份外壳布局，但「标题 + 元信息 + 后端标记」这一段必须共用
 * [SessionRowText]。历史上每次加行级元素都只改到其中一两处：
 * 后端标记 0.5.26 补列表页、0.5.27 补首页、0.5.28 补齐抽屉，0.5.41 才发现「活动」段还是没带。
 *
 * 这里直接读源码对账（和 shell 时代 `overlay-selectors.test.ts` 同一个思路）：
 * 谁把这段又手抄回去，测试就红一次。
 */
class SessionRowTextGuardTest {

    /** 工作目录是 `native/app`（Gradle 单测的默认 cwd）。 */
    private fun source(relative: String): String {
        val file = File("src/main/java/dev/pideck/nativeapp/$relative")
        assertTrue("找不到源码：${file.absolutePath}", file.exists())
        return file.readText()
    }

    private val rowFiles = listOf(
        "ui/sessions/SessionsScreen.kt",   // 全部会话
        "ui/home/HomeScreen.kt",           // 首页「最近的会话」
        "ui/chat/SessionDrawer.kt",        // 抽屉 · 聊天/项目
        "ui/chat/DrawerActivePane.kt",     // 抽屉 · 活动
    )

    @Test
    fun `every session row renders the shared text block`() {
        rowFiles.forEach { path ->
            assertTrue(
                "$path 的会话行没有用 ui/common/SessionRowText.kt —— 标题/元信息/后端标记会再次分叉",
                source(path).contains("SessionRowText("),
            )
        }
    }

    @Test
    fun `session rows do not hand-roll the backend badge anymore`() {
        rowFiles.forEach { path ->
            val text = source(path)
            assertTrue(
                "$path 自己画了 BackendBadge —— 四处行组件里只要有一处手抄，就会漏掉另一处",
                !text.contains("BackendBadge("),
            )
        }
    }

    @Test
    fun `relative time has a single implementation`() {
        val offenders = rowFiles + listOf("ui/chat/WorkspaceSheet.kt")
        offenders.forEach { path ->
            val text = source(path)
            assertTrue(
                "$path 又定义了一份 relativeTime —— 请用 ui/common/TimeText.kt",
                !text.contains("fun relativeTime("),
            )
        }
    }
}
