package dev.pideck.nativeapp.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.pideck.nativeapp.data.ChangedFile
import dev.pideck.nativeapp.data.LineDiff
import dev.pideck.nativeapp.data.PideckApi
import dev.pideck.nativeapp.data.describeConnectionError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 「改动」面板里点开某个文件时的那份 diff。
 *
 * 从 `ChatViewModel` 里分出来的第一块：它只依赖「设备 / 令牌 / 项目 id」三个取值函数，
 * 与聊天流、发送、统计都没有交集，搬出来最不容易牵动别处。
 * 界面仍然读 `viewModel.diffPath` / `diffText` / `diffBusy`（转发属性），所以调用点没动。
 */
internal class DiffController(
    private val scope: CoroutineScope,
    private val api: () -> PideckApi,
    private val device: () -> dev.pideck.nativeapp.data.Device?,
    private val token: () -> String?,
    private val projectId: () -> String?,
) {
    var path by mutableStateOf<String?>(null)
        private set

    var text by mutableStateOf("")
        private set

    var busy by mutableStateOf(false)
        private set

    /** 快速点两个文件时，只允许最后点的那次落地（否则会出现「标题是 B、内容是 A」）。 */
    private var openSeq = 0

    var group by mutableStateOf("workingTree")
        private set

    fun open(file: ChangedFile) {
        val target = device() ?: return
        val project = projectId() ?: return
        val seq = ++openSeq
        path = file.path
        group = file.group
        text = ""
        busy = true
        val auth = token()
        scope.launch {
            try {
                val payload = api().fileDiff(target, auth, project, file.group, file.path)
                if (seq != openSeq) return@launch
                text = if (payload == null) {
                    ""
                } else {
                    LineDiff.render(payload.original, payload.modified)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (seq == openSeq) text = describeConnectionError(e)
            } finally {
                if (seq == openSeq) busy = false
            }
        }
    }

    fun close() {
        path = null
        text = ""
    }
}
