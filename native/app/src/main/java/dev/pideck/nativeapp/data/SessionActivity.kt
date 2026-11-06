package dev.pideck.nativeapp.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 会话在侧栏上的状态灯。
 *
 * 数据只有三份来源，能拿到什么就显示什么：
 * 1. `/api/state` 的 `runtimes` → 正在跑；
 * 2. `/api/state` 的 `pendingUiRequests` → 在等用户回答；
 * 3. 「跑完之后那一轮是成功还是失败」宿主没给 —— 由客户端自己在这轮结束时读最后一条
 *    消息的 `errorText` 判定（[markTurnResult]），只对打开过的会话准确。
 *
 * 所以这是一份内存里的观察记录，不是服务端的权威状态：重启 App 后除了正在跑的，其余回到空闲灰。
 */
object SessionActivity {

    enum class Status { Idle, Running, Waiting, Done, Failed }

    /** 只保存「跑过 / 判过」的会话，没进过表的按 Idle 处理。 */
    var statuses by mutableStateOf<Map<String, Status>>(emptyMap())
        private set

    private var previousRunning: Set<String> = emptySet()

    fun statusOf(sessionId: String): Status = statuses[sessionId] ?: Status.Idle

    /** 上一次从 `/api/state` 读到的「等你回答」集合；轻量轮询（只有 runtimes）时沿用它。 */
    private var lastWaiting: Set<String> = emptySet()

    /**
     * 用一次 `/api/state` 的结果刷新：跑起来的标蓝、在等回答的标橙，
     * 刚从「跑」变成「不跑」的先记成 Done，等 [markTurnResult] 用真实结果覆盖成 Failed。
     */
    fun observe(state: PideckState) {
        observeRuntimes(state.runtimes, state.pendingUiRequests.map { it.sessionId }.toSet())
    }

    /**
     * 只喂运行态（`/api/sessions/runtimes`，约 3KB）的版本 —— 侧栏「活动」段每 5 秒刷一次走这条，
     * 不必为了一个状态灯去拉 56KB 的 `/api/state`。
     *
     * [waiting] 传 null 表示「这次没问到」，沿用上一次的集合：轻量接口里没有 `pendingUiRequests`，
     * 直接当成空会把「在等你回答」误降级成 Running/Idle。
     */
    fun observeRuntimes(runtimes: List<RuntimeInfo>, waiting: Set<String>? = null) {
        if (waiting != null && waiting != lastWaiting) lastWaiting = waiting
        val running = runtimes.filter { it.isRunning }.map { it.sessionId }.toSet()
        val waitingIds = lastWaiting
        val next = statuses.toMutableMap()
        // 等你回答的会话可能还没把 runtime 标成 running，所以先按等待态铺一遍
        for (id in waitingIds) next[id] = Status.Waiting
        for (id in running) {
            next[id] = if (id in waitingIds) Status.Waiting else Status.Running
        }
        for (id in previousRunning - running) {
            // 已经判定过的（打开过会话、读过 errorText）就别被乐观的 Done 盖掉
            if (next[id] == null || next[id] == Status.Running || next[id] == Status.Waiting) {
                next[id] = Status.Done
            }
        }
        previousRunning = running
        if (next != statuses) statuses = next
    }

    /** 一轮结束时由聊天页写入真实结果。 */
    fun markTurnResult(sessionId: String, failed: Boolean) {
        val next = statuses.toMutableMap()
        next[sessionId] = if (failed) Status.Failed else Status.Done
        if (next != statuses) statuses = next
    }

    /** 测试用：把观察记录清空（含 previousRunning，否则「停止」检测会串场）。 */
    internal fun reset() {
        statuses = emptyMap()
        previousRunning = emptySet()
        lastWaiting = emptySet()
    }

    /** 删除会话时顺手清掉，免得同一 id 复用（复制会话）时串色。 */
    fun forget(sessionId: String) {
        if (sessionId !in statuses) return
        statuses = statuses - sessionId
    }
}
