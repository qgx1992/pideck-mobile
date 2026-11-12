package dev.pideck.nativeapp.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 匿名会话（`noSession`，不落盘）的运行时台账。
 *
 * 这类运行时**不会出现在项目列表里**，也就没有别的地方会去收它：谁建的谁负责卸，
 * 否则宿主上会留一个占着上下文的孤儿（用户看到的症状是「关掉面板之后电脑还在烧 token」）。
 * 现在有两个来源（并行问询、浮窗翻译），以前是两条几乎一样的 `xxxTarget ?: return` +
 * `enqueueRuntimeStop` 各写一遍，翻译那条在 `onCleared` 里干脆漏了。
 *
 * 一律用 [stopScope] 而不是 ViewModel 的 scope：`ViewModel.clear()` 先关 closeables
 * （viewModelScope 在内）再调 `onCleared()`，在 onCleared 里用 viewModelScope 发的请求一条都发不出去。
 */
class EphemeralRuntimes(
    private val stopScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private val targets = mutableMapOf<String, RuntimeTarget>()

    /** 记下（或更新）一个槽位。`agentId` 常常要等 `prompt` 的响应才知道，所以允许覆盖。 */
    @Synchronized
    fun put(slot: String, target: RuntimeTarget) {
        targets[slot] = target
    }

    @Synchronized
    fun get(slot: String): RuntimeTarget? = targets[slot]

    /** 取出并抹掉：收一次即止，重复调用不会重复发请求。 */
    @Synchronized
    fun take(slot: String): RuntimeTarget? = targets.remove(slot)

    @Synchronized
    fun slots(): List<String> = targets.keys.toList()

    /**
     * 卸掉某个槽位的运行时。
     *
     * 设备取不到（被删了）时只清账、不发请求 —— 让 `take` 照旧生效，
     * 否则下次还会对着不存在的设备重试。
     */
    fun stop(
        slot: String,
        device: Device?,
        token: String?,
        api: PideckApi,
    ) {
        val runtime = take(slot) ?: return
        if (device == null) return
        stopNow(device, token, runtime, api)
    }

    /** 已经拿到运行时对象时直接卸（例如 from `finally` 收尾，此时台账已经清空）。 */
    fun stopNow(
        device: Device,
        token: String?,
        runtime: RuntimeTarget,
        api: PideckApi,
    ) {
        stopScope.launch {
            attempt {
                api.stopRuntime(device, token, runtime.sessionId, runtime.agentId, runtime.runtimeGeneration)
            }
        }
    }

    /** `onCleared` 用：把还挂着的全卸掉。 */
    @Synchronized
    fun drain(): List<RuntimeTarget> {
        val rest = targets.values.toList()
        targets.clear()
        return rest
    }
}
