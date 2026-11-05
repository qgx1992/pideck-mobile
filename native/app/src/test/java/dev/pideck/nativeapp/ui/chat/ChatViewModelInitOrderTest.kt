package dev.pideck.nativeapp.ui.chat

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守一条真的踩过的坑：`ChatViewModel.init` 里读本地偏好补默认值，而被赋值的 state
 * 必须声明在 `init` **之前**。
 *
 * Kotlin 按文本顺序初始化属性：声明写在 `init` 后面时，init 跑的那一刻委托字段（`by mutableStateOf`）
 * 还是 null，赋值就抛 NPE —— 表现是「会话页进不去」（0.5.22 出过一次，0.5.23 修）。
 * 这类顺序错误编译期完全看不出来，只能靠这层守卫。
 */
class ChatViewModelInitOrderTest {

    private val source: String by lazy {
        // Gradle 的单元测试工作目录就是模块目录（native/app）
        File("src/main/java/dev/pideck/nativeapp/ui/chat/ChatViewModel.kt").readText()
    }

    @Test
    fun `state assigned from init is declared above init`() {
        val initAt = source.indexOf("\n    init {")
        assertTrue("找不到 init 块", initAt > 0)
        // init 里只允许给「声明在 init 之前」的 state 赋值；目前只有这一个入口函数
        assertTrue("restoreBusySendMode() 应该还在 init 里", source.contains("init {\n        restoreBusySendMode()"))
        val declared = listOf(
            "var busySendMode by",
            "var queuedTexts by",
            "var parallelAsk by",
            "var parallelInsert by",
        )
        declared.forEach { field ->
            val at = source.indexOf(field)
            assertTrue("$field 不见了", at > 0)
            assertTrue("$field 必须声明在 init 之前（否则 init 里赋值会 NPE）", at < initAt)
        }
    }
}
