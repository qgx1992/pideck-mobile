package dev.pideck.nativeapp.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.pideck.nativeapp.data.ChatPayloads
import dev.pideck.nativeapp.data.Device
import dev.pideck.nativeapp.data.EphemeralRuntimes
import dev.pideck.nativeapp.data.PideckApi
import dev.pideck.nativeapp.data.PideckJson
import dev.pideck.nativeapp.data.ReplyMerger
import dev.pideck.nativeapp.data.RuntimeTarget
import dev.pideck.nativeapp.data.attempt
import dev.pideck.nativeapp.data.describeConnectionError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/**
 * 「并行发送」：另开一个匿名会话去问一件事，结果贴回浮窗，不打断当前这一轮。
 *
 * 从 [ChatViewModel] 分出来的第六块。和翻译一样直接操做 [EphemeralRuntimes]：
 * 匿名会话不出现在项目列表里，关面板 / 清 ViewModel 时得有人替它发 `runtime/stop`。
 */
internal class ParallelAskController(
    private val scope: CoroutineScope,
    private val api: () -> PideckApi,
    private val device: () -> Device?,
    private val token: () -> String?,
    private val projectId: () -> String?,
    private val ephemeral: EphemeralRuntimes,
    private val onNote: (String) -> Unit = {},
) {
    var parallelAsk by mutableStateOf<ParallelAsk?>(null)
        private set

    var parallelInsert by mutableStateOf<String?>(null)

    private var job: Job? = null
    private var merger = ReplyMerger()

    fun start(text: String) {
        val target = device() ?: return
        val question = text.trim()
        if (question.isEmpty()) return
        val project = projectId()
        if (project == null) {
            onNote("这个会话没有归属项目，没法另开匿名会话")
            return
        }
        job?.cancel()
        stopRuntime()
        merger = ReplyMerger()
        parallelAsk = ParallelAsk(question = question, sessionId = "", busy = true)
        job = scope.launch {
            try {
                val anon = api().createAnonymousSession(target, token(), project)
                parallelAsk = parallelAsk?.copy(sessionId = anon)
                // 会话一建出来就先登记：后面 prompt 抛异常、或宿主不回 agentId / accepted=false 时，
                // 先记账：agentId 要等 prompt 的响应才知道，记在前面的意义是「中途取消也有人收尾」。
                ephemeral.put(SLOT, RuntimeTarget(anon, "", 0L))
                val delivery = api().sendPrompt(
                    target,
                    token(),
                    anon,
                    ChatPayloads.promptBody(newRequestId(), question),
                )
                if (delivery.agentId != null) {
                    ephemeral.put(SLOT, RuntimeTarget(anon, delivery.agentId, delivery.runtimeGeneration ?: 0L))
                }
                if (!delivery.accepted) {
                    parallelAsk = parallelAsk?.copy(busy = false, error = delivery.error ?: "宿主没有接受这条问题")
                    return@launch
                }
                // 先投递后订阅也不丢帧：服务端在订阅时会回放本轮已产出的帧。
                try {
                    api().sessionStream(target, token(), anon).collect { frame ->
                        val snapshot = when {
                            frame.data.trim() == "[DONE]" -> merger.finish()
                            else -> {
                                val parsed = attempt {
                                    PideckJson.parseToJsonElement(frame.data) as? JsonObject
                                }.getOrNull()
                                if (parsed != null) merger.applyFrame(parsed) else merger.snapshot()
                            }
                        } ?: return@collect
                        parallelAsk = parallelAsk?.copy(
                            answer = snapshot.text,
                            busy = !snapshot.finished,
                            error = snapshot.errorText,
                        )
                        // 这一轮收尾就别再挂着 keepAlive 的订阅（服务端不会主动断）
                        if (snapshot.finished) throw AskDone()
                    }
                } catch (e: AskDone) {
                    // 正常收尾
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                parallelAsk = parallelAsk?.copy(busy = false, error = describeConnectionError(e))
            }
        }
    }

    /** 面板上「插入输入框」：把答案交出去，然后收掉匿名会话。 */
    fun insertAnswer() {
        val ask = parallelAsk ?: return
        if (ask.answer.isBlank()) return
        parallelInsert = ask.answer.trim()
        close()
    }

    /** 关面板：取消订阅并停掉匿名会话的 runtime（`noSession` 会话不会自己出现在项目列表里）。 */
    fun close() {
        job?.cancel()
        job = null
        parallelAsk = null
        stopRuntime()
    }

    /**
     * 收尾走 [EphemeralRuntimes] 的 stopScope：**不能**用 viewModelScope ——
     * `ViewModel.clear()` 先关 closeables 再调 `onCleared()`，那里发的请求一条都出不去。
     */
    private fun stopRuntime() {
        ephemeral.stop(SLOT, device(), token(), api())
    }

    private fun newRequestId(): String = UUID.randomUUID().toString()

    private class AskDone : Throwable()

    private companion object {
        const val SLOT = "parallel"
    }
}
