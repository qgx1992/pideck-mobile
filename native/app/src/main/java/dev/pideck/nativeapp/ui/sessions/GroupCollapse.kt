package dev.pideck.nativeapp.ui.sessions

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 项目分组的折叠状态，进程级共享。
 *
 * 为什么不在 ViewModel 里：会话列表页和聊天页左侧栏是两个不同的 [SessionsViewModel]
 * 实例（各自挂在不同的导航条目上），而它们必须看到同一份折叠状态 ——
 * 换一次会话就整片弹开，谁都会烦。放在单例里，只依赖 compose 的 snapshot 播变化，
 * 不落盘（重启回「全展开」是合理默认）。
 */
object GroupCollapse {

    var collapsed by mutableStateOf<Set<String>>(emptySet())
        private set

    fun toggle(projectId: String) {
        collapsed = if (projectId in collapsed) collapsed - projectId else collapsed + projectId
    }

    /** 有任何一个展开就全收起来，否则全展开。 */
    fun toggleAll(projectIds: Collection<String>) {
        val all = projectIds.toSet()
        if (all.isEmpty()) return
        collapsed = if (all.all { it in collapsed }) emptySet() else all
    }

    fun isCollapsed(projectId: String): Boolean = projectId in collapsed

    fun allCollapsed(projectIds: Collection<String>): Boolean {
        val all = projectIds.toSet()
        return all.isNotEmpty() && all.all { it in collapsed }
    }
}
