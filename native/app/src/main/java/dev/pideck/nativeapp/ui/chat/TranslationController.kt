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
import dev.pideck.nativeapp.data.TranslationState
import dev.pideck.nativeapp.data.TranslateTarget
import dev.pideck.nativeapp.data.attempt
import dev.pideck.nativeapp.data.defaultTranslateTarget
import dev.pideck.nativeapp.data.describeConnectionError
import dev.pideck.nativeapp.data.translateInstruction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/**
 * 思考浮窗里的「翻译」：借宿主 agent 跑一轮匿名会话，把译文贴在原文下面。
 *
 * 这是从 [ChatViewModel] 分出来的第五块，也是唯一直接操做匿名运行时台账的一块：
 * 匿名运行时不出现在项目列表里，没人替它收，所以必须走 [EphemeralRuntimes]（离开页面时
 * `viewModelScope` 已经关了，那里发的请求一条都出不去）。
 */
internal class TranslationController(
    private val scope: CoroutineScope,
    private val api: () -> PideckApi,
    private val device: () -> Device?,
    private val token: () -> String?,
    private val projectId: () -> String?,
    private val ephemeral: EphemeralRuntimes,
) {
    var translation by mutableStateOf<TranslationState?>(null)
        private set

    private var source = ""
    private var job: Job? = null

    fun translate(text: String, target: TranslateTarget = defaultTranslateTarget(text)) {
        val targetDevice = device() ?: return
        val body = text.trim()
        if (body.isEmpty()) return
        val project = projectId()
        if (project == null) {
            translation = TranslationState(target = target, error = "这个会话没有归属项目，开不了匿名会话")
            return
        }
        job?.cancel()
        stopRuntime()
        source = body
        translation = TranslationState(target = target, busy = true)
        val merger = ReplyMerger()
        job = scope.launch {
            try {
                val anon = api().createAnonymousSession(targetDevice, token(), project)
                ephemeral.put(SLOT, RuntimeTarget(anon, "", 0L))
                val delivery = api().sendPrompt(
                    targetDevice,
                    token(),
                    anon,
                    ChatPayloads.promptBody(newRequestId(), translateInstruction(body, target)),
                )
                if (delivery.agentId != null) {
                    ephemeral.put(SLOT, RuntimeTarget(anon, delivery.agentId, delivery.runtimeGeneration ?: 0L))
                }
                if (!delivery.accepted) {
                    translation = TranslationState(
                        target = target,
                        error = delivery.error ?: "宿主没有接受这条翻译请求",
                    )
                    return@launch
                }
                api().sessionStream(targetDevice, token(), anon).collect { frame ->
                    val snapshot = when {
                        frame.data.trim() == "[DONE]" -> merger.finish()
                        else -> {
                            val parsed = attempt {
                                PideckJson.parseToJsonElement(frame.data) as? JsonObject
                            }.getOrNull()
                            if (parsed != null) merger.applyFrame(parsed) else merger.snapshot()
                        }
                    } ?: return@collect
                    translation = TranslationState(
                        target = target,
                        busy = !snapshot.finished,
                        text = snapshot.text,
                        error = snapshot.errorText,
                    )
                    if (snapshot.finished) throw TranslationDone()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TranslationDone) {
                // 正常收尾：译文已经在上面写进状态
                translation = translation?.copy(busy = false)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                translation = TranslationState(target = target, error = describeConnectionError(e))
            } finally {
                withContext(NonCancellable) { stopRuntimeNow() }
            }
        }
    }

    /** 「改译成英文 / 中文」：拿同一段原文按反方向再翻一次。 */
    fun flipTranslation() {
        val state = translation ?: return
        if (state.busy) return
        if (source.isBlank()) return
        translate(source, state.target.flipped())
    }

    /** 关浮窗：取消订阅、清状态、卸掉匿名会话的运行时。 */
    fun closeTranslation() {
        job?.cancel()
        job = null
        translation = null
        source = ""
        stopRuntime()
    }

    private fun stopRuntime() {
        ephemeral.stop(SLOT, device(), token(), api())
    }

    /** 收尾用：这里已经在协程里，直接等请求发完再走（`attempt` 不会把取消抛成失败）。 */
    private suspend fun stopRuntimeNow() {
        val runtime = ephemeral.take(SLOT) ?: return
        val target = device() ?: return
        attempt {
            api().stopRuntime(target, token(), runtime.sessionId, runtime.agentId, runtime.runtimeGeneration)
        }
    }

    private fun newRequestId(): String = UUID.randomUUID().toString()

    /** 只在这块里用的「流读完了」，不复用聊天主回路那个。 */
    private class TranslationDone : Throwable()

    private companion object {
        const val SLOT = "translate"
    }
}
