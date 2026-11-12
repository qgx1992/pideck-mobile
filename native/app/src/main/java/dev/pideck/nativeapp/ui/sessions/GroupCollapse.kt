package dev.pideck.nativeapp.ui.sessions

import dev.pideck.nativeapp.data.attempt
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.pideck.nativeapp.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 项目分组的折叠状态，进程级共享 + 落盘。
 *
 * 为什么不在 ViewModel 里：会话列表页和聊天页左侧栏是两个不同的 [SessionsViewModel]
 * 实例（各自挂在不同的导航条目上），而它们必须看到同一份状态 ——
 * 换一次会话就整片弹开，谁都会烦。
 *
 * 落盘策略由设置决定（`Prefs.collapseRestore`）：
 * - 开：启动时恢复上次退出时的收起状态；
 * - 关：一律从「全部收起」开始。
 * 无论哪种，改动都会立刻写盘，所以切回「恢复上次状态」时用得上。
 */
object GroupCollapse {

    private var context: Context? = null
    private var scope: CoroutineScope? = null

    var collapsed by mutableStateOf<Set<String>>(emptySet())
        private set

    private var touched = false

    /**
     * 由 Application.onCreate 调一次。等 Prefs 读完磁盘再灌初值 ——
     * 之前不等的话拿到的是默认空集，「恢复上次状态」形同虚设。
     */
    fun attach(context: Context, scope: CoroutineScope) {
        this.context = context.applicationContext
        this.scope = scope
        scope.launch {
            attempt { Prefs.awaitLoaded() }
            val restored = if (Prefs.collapseRestore) Prefs.collapsedGroups else emptySet()
            // 传进来的 scope 是 Dispatchers.IO（`PiDeckApp.onCreate` 的 prefsScope）：
            // 读盘留在 IO，**写 Compose 状态必须回主线程**。快照状态本身线程安全，
            // 但主线程不会因为 IO 线程的这次写入被唤醒重绘，表现是「上次展开的项目又合上了」，
            // 手动滚一下才刷新。
            withContext(Dispatchers.Main.immediate) {
                if (!touched) collapsed = restored
            }
        }
    }

    fun toggle(projectId: String) {
        touched = true
        collapsed = if (projectId in collapsed) collapsed - projectId else collapsed + projectId
        persist()
    }

    /** 顶栏那个一键开关：有任何一个展开就全收起来，否则全展开。 */
    fun toggleAll(projectIds: Collection<String>) {
        val all = projectIds.toSet()
        if (all.isEmpty()) return
        touched = true
        collapsed = if (all.all { it in collapsed }) emptySet() else all
        persist()
    }

    /** 长按项目标题：只留它展开（其余全收）；如果本来就只有它一个展开，则恢复全展开。 */
    fun solo(projectIds: Collection<String>, projectId: String) {
        val all = projectIds.toSet()
        if (all.isEmpty()) return
        touched = true
        val alreadySolo = collapsed == (all - projectId)
        collapsed = if (alreadySolo) emptySet() else all - projectId
        persist()
    }

    fun isCollapsed(projectId: String): Boolean = projectId in collapsed

    fun allCollapsed(projectIds: Collection<String>): Boolean {
        val all = projectIds.toSet()
        return all.isNotEmpty() && all.all { it in collapsed }
    }

    private fun persist() {
        val ctx = context ?: return
        val s = scope ?: return
        val snapshot = collapsed
        s.launch { attempt { Prefs.setCollapsedGroups(ctx, snapshot) } }
    }
}
