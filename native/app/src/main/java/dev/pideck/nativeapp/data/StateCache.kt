package dev.pideck.nativeapp.data

/**
 * 会话列表页抓到的 `/api/state` 快照，聊天页直接复用。
 *
 * 开一个会话再拉一次 55KB 的 state 纯属浪费：列表页刚刚拿到过，标题与 runtime
 * （停止按钮要的 agentId / runtimeGeneration）都在里面。
 *
 * 调用点并不都保证在主线程（`SessionStateStore` 会被 IO 侧的收尾协程调用），
 * 所以读写都加锁 —— 裸 `mutableMapOf` 并发写会把内部桶表写坏（表现为偶发丢会话）。
 */
object StateCache {
    private val byDevice = mutableMapOf<String, PideckState>()

    @Synchronized
    fun put(deviceId: String, state: PideckState) {
        byDevice[deviceId] = state
    }

    @Synchronized
    fun get(deviceId: String): PideckState? = byDevice[deviceId]

    @Synchronized
    fun session(deviceId: String, sessionId: String): Session? =
        get(deviceId)?.sessions?.firstOrNull { it.id == sessionId }

    @Synchronized
    fun runtime(deviceId: String, sessionId: String): RuntimeInfo? =
        get(deviceId)?.runtimes?.firstOrNull { it.sessionId == sessionId }

    /** 单测用：这是个进程级单例，用例之间不清就会互相污染（踩过：上一个用例塞的 runtime 让下一个用例多发一次 abort）。 */
    @Synchronized
    internal fun resetForTest() {
        byDevice.clear()
    }
}
