package dev.pideck.nativeapp.data

/**
 * `/api/state` 与 `/api/sessions/runtimes` 落到本地时**唯一**该走的地方。
 *
 * 以前这两件事在四个 ViewModel 里各调各的：
 * 先 `StateCache.put(...)`，再（有时忘了）`SessionActivity.observe(...)` —— 首页不刷新运行态、
 * 列表页却刷新了，会话行的状态点于是「有时亮有时不亮」。现在合成一步：
 * 写缓存 + 同步运行态永远一起发生，新增调用方也不会漏掉另一半。
 *
 * 顺带它是测试的接缝：以后要给 ViewModel 写行为测试，塞一个记录调用的假实现即可
 * （`StateCache` 与 `SessionActivity` 都是进程级单例，直接测既有代码没法避免互相污染）。
 */
@javax.inject.Singleton
class SessionStateStore @javax.inject.Inject constructor() {

    fun cached(deviceId: String): PideckState? = StateCache.get(deviceId)

    fun session(deviceId: String, sessionId: String): Session? = StateCache.session(deviceId, sessionId)

    fun runtime(deviceId: String, sessionId: String): RuntimeInfo? = StateCache.runtime(deviceId, sessionId)

    /** `/api/state` 的完整快照：缓存 + 运行态 + 等待中的提问（`pendingUiRequests`）。 */
    fun put(deviceId: String, state: PideckState) {
        StateCache.put(deviceId, state)
        SessionActivity.observe(state)
    }

    /** 轻量接口 `/api/sessions/runtimes`（约 3KB）：只动运行态，等待集合沿用上一次 state 里的。 */
    fun putRuntimes(runtimes: List<RuntimeInfo>) {
        SessionActivity.observeRuntimes(runtimes)
    }
}
