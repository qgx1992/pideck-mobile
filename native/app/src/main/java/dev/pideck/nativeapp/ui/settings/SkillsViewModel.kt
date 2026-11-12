package dev.pideck.nativeapp.ui.settings

import dev.pideck.nativeapp.data.attempt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pideck.nativeapp.data.Device
import dev.pideck.nativeapp.data.DeviceRepository
import dev.pideck.nativeapp.data.ExtensionEntry
import dev.pideck.nativeapp.data.SkillEntry
import dev.pideck.nativeapp.data.SkillsParse
import dev.pideck.nativeapp.data.describeConnectionError
import dev.pideck.nativeapp.data.PideckApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 技能 / 扩展页。
 *
 * 数据都在宿主上（`/api/skills`、`/api/extensions`），开关是**宿主全局**生效，
 * 所以不做本地缓存：进页面读一次、切完重读一次，避免「界面上关了、宿主其实没关」。
 *
 * 只读 + toggle；新建/删除技能、安装扩展只有 IPC，这里不做。
 */
@HiltViewModel
class SkillsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: DeviceRepository,
    private val api: PideckApi,
) : ViewModel() {

    private val deviceId: String = savedStateHandle["deviceId"] ?: ""

    var skills by mutableStateOf<List<SkillEntry>>(emptyList())
        private set

    var extensions by mutableStateOf<List<ExtensionEntry>>(emptyList())
        private set

    var conflicts by mutableStateOf<List<String>>(emptyList())
        private set

    var loading by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    /** 正在切换的条目 id（用 id 而不是布尔，避免整页转圈）。 */
    var busyIds by mutableStateOf<Set<String>>(emptySet())
        private set

    private var device: Device? = null
    private var token: String? = null

    init {
        viewModelScope.launch {
            val devices = attempt { repository.devices.first() }.getOrDefault(emptyList())
            device = devices.firstOrNull { it.id == deviceId } ?: devices.firstOrNull()
            token = device?.let { attempt { repository.tokenOf(it.id) }.getOrNull() }
            refresh()
        }
    }

    fun refresh() {
        val device = device ?: return
        loading = true
        error = null
        viewModelScope.launch {
            try {
                // 两个接口互不依赖，并发拉；其中一个挂了不该把另一个也清空
                coroutineScope {
                    val skillsJob = async { attempt { api.skills(device, token) } }
                    val extensionsJob = async { attempt { api.extensions(device, token) } }
                    val skillsResult = skillsJob.await()
                    val extensionsResult = extensionsJob.await()
                    skillsResult.getOrNull()?.let {
                        skills = SkillsParse.sortedSkills(it.skills)
                    }
                    extensionsResult.getOrNull()?.let {
                        extensions = SkillsParse.sortedExtensions(it.extensions)
                        conflicts = it.conflicts
                    }
                    val failure = skillsResult.exceptionOrNull() ?: extensionsResult.exceptionOrNull()
                    if (failure != null && skills.isEmpty() && extensions.isEmpty()) {
                        error = describeConnectionError(failure)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is CancellationException) throw e

                error = describeConnectionError(e)
            } finally {
                loading = false
            }
        }
    }

    fun toggle(skill: SkillEntry, enabled: Boolean) {
        val device = device ?: return
        if (skill.id in busyIds) return
        busyIds = busyIds + skill.id
        viewModelScope.launch {
            try {
                api.toggleSkill(device, token, skill.name, skill.sourceId, enabled)
                // 宿主会回最新条目，但内容一字不差地重读一次更省心（顺序/失效位都可能变）
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is CancellationException) throw e

                error = describeConnectionError(e)
            } finally {
                busyIds = busyIds - skill.id
            }
        }
    }

    fun toggleExtension(extension: ExtensionEntry, enabled: Boolean) {
        val device = device ?: return
        if (extension.id in busyIds) return
        busyIds = busyIds + extension.id
        viewModelScope.launch {
            try {
                api.toggleExtension(device, token, extension.source, extension.scope, enabled)
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is CancellationException) throw e

                error = describeConnectionError(e)
            } finally {
                busyIds = busyIds - extension.id
            }
        }
    }
}
