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
import dev.pideck.nativeapp.data.StateCache
import dev.pideck.nativeapp.data.StateFetch
import dev.pideck.nativeapp.data.describeConnectionError
import dev.pideck.nativeapp.watch.RunWatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

data class SessionGroup(
    val projectId: String,
    val projectName: String,
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
                else SessionGroup(project.id, project.name, sessions)
            }
            // 项目表里查不到的会话（项目被删/换路径）兜底放最后一组，别让它们消失。
            val known = ordered.map { it.projectId }.toSet()
            val orphans = byProject
                .filterKeys { it !in known }
                .map { (projectId, sessions) ->
                    SessionGroup(projectId, "其他", sessions.sortedByDescending { s -> s.updatedAt })
                }
                .sortedBy { it.projectName }
            return ordered + orphans
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
