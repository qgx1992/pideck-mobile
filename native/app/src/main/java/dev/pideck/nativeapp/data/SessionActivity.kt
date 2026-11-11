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

    /**
     * 只保存「跑过 / 判过」的会话，没进过表的按 Idle 处理。
     *
     * 键里必须带设备：两台设备上出现同一个 `sessionId`（同一台机器配对两次、备份恢复后 id 相同）时，
     * 以前会互相覆盖 —— A 的会话在 B 的列表里亮着「运行中」。
     * [statuses] / [loadedSessionIds] 是「当前正在渲染的那台设备」的视图，`observe*` 会切过去；
     * 手上有 deviceId 的地方走带参数的 [statusOf] 更稳。
     */
    var statuses by mutableStateOf<Map<String, Status>>(emptyMap())
        private set

    /** 每台设备各自一份观察记录。 */
    private val byDevice = mutableMapOf<String, Map<String, Status>>()
    private val previousRunningByDevice = mutableMapOf<String, Set<String>>()
    private val waitingByDevice = mutableMapOf<String, Set<String>>()
    private val loadedByDevice = mutableMapOf<String, Set<String>>()
    private var activeDeviceId: String = ""

    fun statusOf(sessionId: String): Status = statuses[sessionId] ?: Status.Idle

    /** 指定设备的状态（列表页 / 抽屉手上有 deviceId 时优先用这个）。 */
    fun statusOf(deviceId: String, sessionId: String): Status =
        byDevice[deviceId]?.get(sessionId) ?: Status.Idle

    /**
     * 宿主上**装载着运行时**的会话 id（`/api/state` 的 `runtimes`，idle 也算）。
     *
     * 侧栏「活动」上半段照桌面端的 `collectActiveSessionRows` 来：那一栏不是「正在跑」，
     * 而是「这台桌面上开着 agent 的会话」—— 跑完但运行时还挂着的会话也留在上面。
     */
    var loadedSessionIds by mutableStateOf<Set<String>>(emptySet())
        private set

    /**
     * 用一次 `/api/state` 的结果刷新：跑起来的标蓝、在等回答的标橙，
     * 刚从「跑」变成「不跑」的先记成 Done，等 [markTurnResult] 用真实结果覆盖成 Failed。
     */
    fun observe(deviceId: String, state: PideckState) {
        observeRuntimes(deviceId, state.runtimes, state.pendingUiRequests.map { it.sessionId }.toSet())
    }

    /**
     * 只喂运行态（`/api/sessions/runtimes`，约 3KB）的版本 —— 侧栏「活动」段每 5 秒刷一次走这条，
     * 不必为了一个状态灯去拉 56KB 的 `/api/state`。
     *
     * [waiting] 传 null 表示「这次没问到」，沿用上一次的集合：轻量接口里没有 `pendingUiRequests`，
     * 直接当成空会把「在等你回答」误降级成 Running/Idle。
     */
    fun observeRuntimes(deviceId: String, runtimes: List<RuntimeInfo>, waiting: Set<String>? = null) {
        if (waiting != null && waiting != waitingByDevice[deviceId]) waitingByDevice[deviceId] = waiting
        val loaded = runtimes.map { it.sessionId }.toSet()
        val running = runtimes.filter { it.isRunning }.map { it.sessionId }.toSet()
        val waitingIds = waitingByDevice[deviceId].orEmpty()
        val previousRunning = previousRunningByDevice[deviceId].orEmpty()
        val next = byDevice[deviceId].orEmpty().toMutableMap()
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
        previousRunningByDevice[deviceId] = running
        byDevice[deviceId] = next
        loadedByDevice[deviceId] = loaded
        publish(deviceId)
    }

    /** 把某台设备的视图推到界面读的那两个属性上（列表页只渲染一台设备）。 */
    private fun publish(deviceId: String) {
        activeDeviceId = deviceId
        val map = byDevice[deviceId].orEmpty()
        if (map != statuses) statuses = map
        val ids = loadedByDevice[deviceId].orEmpty()
        if (ids != loadedSessionIds) loadedSessionIds = ids
    }

    /** 一轮结束时由聊天页写入真实结果。 */
    fun markTurnResult(deviceId: String, sessionId: String, failed: Boolean) {
        val next = byDevice[deviceId].orEmpty().toMutableMap()
        next[sessionId] = if (failed) Status.Failed else Status.Done
        byDevice[deviceId] = next
        publish(deviceId)
    }

    /** 测试用：把观察记录清空（含 previousRunning，否则「停止」检测会串场）。 */
    internal fun reset() {
        statuses = emptyMap()
        loadedSessionIds = emptySet()
        byDevice.clear()
        previousRunningByDevice.clear()
        waitingByDevice.clear()
        loadedByDevice.clear()
        activeDeviceId = ""
    }

    /** 删除会话时顺手清掉，免得同一 id 复用（复制会话）时串色。 */
    fun forget(deviceId: String, sessionId: String) {
        val next = byDevice[deviceId] ?: return
        if (sessionId !in next) return
        byDevice[deviceId] = next - sessionId
        publish(deviceId)
    }
}
