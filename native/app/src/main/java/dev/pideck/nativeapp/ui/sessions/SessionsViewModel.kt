package dev.pideck.nativeapp.ui.sessions

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pideck.nativeapp.data.Device
import dev.pideck.nativeapp.data.DeviceRepository
import dev.pideck.nativeapp.data.PideckApi
import dev.pideck.nativeapp.data.PideckState
import dev.pideck.nativeapp.data.Session
import dev.pideck.nativeapp.data.SessionActivity
import dev.pideck.nativeapp.data.StateCache
import dev.pideck.nativeapp.data.StateFetch
import dev.pideck.nativeapp.data.describeConnectionError
import dev.pideck.nativeapp.watch.RunWatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

data class SessionGroup(
    val projectId: String,
    val projectName: String,
    val projectPath: String?,
    val sessions: List<Session>,
)

@HiltViewModel
class SessionsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: DeviceRepository,
    private val api: PideckApi,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val deviceId: String = savedStateHandle["deviceId"] ?: error("缺少 deviceId")

    private var device: Device? = null
    private var token: String? = null
    private var etag: String? = null
    private var firstResume = true

    var deviceName by mutableStateOf<String?>(null)
        private set

    var state by mutableStateOf<PideckState?>(null)
        private set

    var loading by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    val hostVersion: String? get() = state?.version

    /**
     * 折叠起来的项目分组（按 projectId 记）。
     *
     * 状态放在 [GroupCollapse] 这个单例里 —— 聊天页左侧栏是另一个 SessionsViewModel
     * 实例，状态必须共享，否则换一次会话就整片弹开。不落盘。
     */
    val collapsedGroups: Set<String>
        get() = GroupCollapse.collapsed

    fun toggleGroup(projectId: String) = GroupCollapse.toggle(projectId)

    /** 顶栏那个一键开关：有任何一个展开就全收起来，否则全展开。 */
    fun toggleAllGroups() = GroupCollapse.toggleAll(groups.map { it.projectId })

    val allCollapsed: Boolean
        get() = GroupCollapse.allCollapsed(groups.map { it.projectId })

    /** 搜索框里的词；空串表示没在搜。 */
    var query by mutableStateOf("")
        private set

    val searching: Boolean get() = query.isNotBlank()

    fun search(text: String) {
        query = text
    }

    /**
     * 真正渲染用的分组 —— 搜索时只留标题命中的会话，命中不了的项目整组不显示。
     *
     * 决定：搜索在本地做（`state` 里已经有全部会话），不打接口。桌面端的 `/api/state`
     * 一次就有 200 多条会话，边打字边请求反而更慢，还会闪。
     */
    val visibleGroups: List<SessionGroup>
        get() = filterGroups(groups, query)

    val matchCount: Int get() = visibleGroups.sumOf { it.sessions.size }

    /** 项目按「置顶 → 最近打开」排，会话按最近活动排；只保留有会话的项目。 */
    val groups: List<SessionGroup>
        get() {
            val snapshot = state ?: return emptyList()
            val projects = snapshot.projects.sortedWith(
                compareByDescending<dev.pideck.nativeapp.data.Project> { it.pinned }
                    .thenByDescending { it.lastOpenedAt },
            )
            val byProject = snapshot.sessions
                .filter { it.parentSessionId == null }
                .groupBy { it.projectId }
            val ordered = projects.mapNotNull { project ->
                val sessions = byProject[project.id].orEmpty()
                    .sortedByDescending { it.updatedAt }
                if (sessions.isEmpty()) null
                else SessionGroup(project.id, project.name, project.path, sessions)
            }
            // 项目表里查不到的会话（项目被删/换路径）兜底放最后一组，别让它们消失。
            val known = ordered.map { it.projectId }.toSet()
            val orphans = byProject
                .filterKeys { it !in known }
                .map { (projectId, sessions) ->
                    SessionGroup(projectId, "其他", null, sessions.sortedByDescending { s -> s.updatedAt })
                }
                .sortedBy { it.projectName }
            return ordered + orphans
        }

    /**
     * 全部会话铺平一行（不含子会话），按最近活动倒序 —— 侧栏「活动」段用。
     *
     * 不另起一个接口：`/api/state` 已经带全部会话，`groups` 也是从它派生的。
     */
    val allSessions: List<dev.pideck.nativeapp.data.Session>
        get() = groups.flatMap { it.sessions }.sortedByDescending { it.updatedAt }

    /** 工作区列表（置顶优先、最近打开靠前）：抽屉「项目」段直接用，排序口径与列表页一致。 */
    val projects: List<dev.pideck.nativeapp.data.Project>
        get() = state?.projects.orEmpty()

    /** 项目名查表（会话行要显示它属于哪个工作区）。查不到给空串，调用方自己兜底。 */
    fun projectNameOf(projectId: String): String {
        val snapshot = state ?: return ""
        return snapshot.projects.firstOrNull { it.id == projectId }?.name.orEmpty()
    }

    fun isRunning(sessionId: String): Boolean =
        state?.runtimes?.any { it.sessionId == sessionId && it.isRunning } == true

    init {
        viewModelScope.launch {
            repository.devices.first { list -> list.any { it.id == deviceId } }
                .firstOrNull { it.id == deviceId }
                ?.let { found ->
                    device = found
                    deviceName = found.name
                    token = repository.tokenOf(found.id)
                }
            refresh()
        }
    }

    /** 回到前台刷新一次（别处发起的运行、桌面端改的标题都能看到）。首次进入不重复拉。 */
    fun onForeground() {
        if (firstResume) {
            firstResume = false
            return
        }
        refresh()
    }

    /** 侧栏/列表长按重命名。改完强制刷新一次（否则 ETag 会返回 304，标题看着没变）。 */
    fun rename(sessionId: String, newTitle: String) {
        val target = device ?: return
        val title = newTitle.trim()
        if (title.isEmpty()) return
        viewModelScope.launch {
            try {
                api.renameSession(target, token, sessionId, title)
                etag = null
                refresh()
            } catch (e: Exception) {
                error = describeConnectionError(e)
            }
        }
    }

    /** 删除会话记录（连文件，不可撤销）。删的是当前打开的那个由调用方决定跳到哪。 */
    fun deleteSession(id: String) {
        val target = device ?: return
        viewModelScope.launch {
            try {
                api.deleteSession(target, token, id)
                if (StateCache.session(deviceId, id) != null) Unit
                etag = null
                refresh()
            } catch (e: Exception) {
                error = describeConnectionError(e)
            }
        }
    }

    /**
     * 复制会话：成功返回新会话 id（调用方决定是跳过去还是只提示）。
     * 只有 pi 后端支持，其余后端服务端 500 —— 这里翻成 null 让 UI 说人话。
     */
    fun copySession(id: String, onDone: (String?) -> Unit = {}) {
        val target = device ?: return
        viewModelScope.launch {
            try {
                val newId = api.copySession(target, token, id)
                etag = null
                refresh()
                onDone(newId)
            } catch (e: Exception) {
                error = describeConnectionError(e)
                onDone(null)
            }
        }
    }

    /** 从列表移除工作区（磁盘目录不动）。 */
    fun removeProject(id: String) {
        val target = device ?: return
        viewModelScope.launch {
            try {
                api.removeProject(target, token, id)
                etag = null
                refresh()
            } catch (e: Exception) {
                error = describeConnectionError(e)
            }
        }
    }

    fun clearError() {
        error = null
    }

    private var runtimesBusy = false

    /**
     * 只刷运行态（`/api/sessions/runtimes`，约 3KB），供侧栏「活动」段轮询。
     *
     * 为什么不能只靠 [refresh]：那个走 `/api/state`（56KB）且带 ETag，抽屉开着的时候不会自己重拉，
     * 于是「别处刚开跑的会话」在手机上看起来一直是空闲 —— 0.5.37 第一版就是这样，活动段永远空。
     */
    fun refreshRuntimes() {
        val target = device ?: return
        if (runtimesBusy) return
        runtimesBusy = true
        viewModelScope.launch {
            try {
                val runtimes = api.runtimes(target, token)
                SessionActivity.observeRuntimes(runtimes)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 轻量轮询失败就静默：下一次（5 秒后）还会再试，不必为一个状态灯弹错
            } finally {
                runtimesBusy = false
            }
        }
    }

    fun refresh() {
        val target = device ?: return
        if (loading) return
        viewModelScope.launch {
            loading = true
            error = null
            try {
                when (val fetched = api.state(target, token, etag)) {
                    is StateFetch.Fresh -> {
                        state = fetched.state
                        etag = fetched.etag
                        StateCache.put(deviceId, fetched.state)
                        SessionActivity.observe(fetched.state)
                        // 有会话在跑就挂上看护（跑完了一条通知），没跑就等下次再说。
                        if (fetched.state.runtimes.any { it.isRunning }) RunWatcher.start(context)
                    }
                    StateFetch.NotModified -> Unit
                }
                repository.touch(target.id)
            } catch (e: Exception) {
                error = describeConnectionError(e)
            } finally {
                loading = false
            }
        }
    }
}


/**
 * 按标题过滤会话分组 —— 纯函数，方便单测。
 *
 * 大小写不敏感；命中不了的项目整组消失（不然搜出来一堆空标题很难看）。
 */
internal fun filterGroups(groups: List<SessionGroup>, rawQuery: String): List<SessionGroup> {
    val needle = rawQuery.trim()
    if (needle.isEmpty()) return groups
    return groups.mapNotNull { group ->
        val hits = group.sessions.filter { it.title.contains(needle, ignoreCase = true) }
        if (hits.isEmpty()) null else group.copy(sessions = hits)
    }
}
