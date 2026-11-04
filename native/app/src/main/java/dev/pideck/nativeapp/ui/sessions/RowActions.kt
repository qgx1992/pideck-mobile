package dev.pideck.nativeapp.ui.sessions

import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import dev.pideck.nativeapp.data.Session
import dev.pideck.nativeapp.ui.common.ActionSheet
import dev.pideck.nativeapp.ui.common.ConfirmDialog
import dev.pideck.nativeapp.ui.common.MenuAction

/**
 * 会话行 / 项目标题行的长按菜单状态。
 *
 * 侧栏（[dev.pideck.nativeapp.ui.chat.SessionDrawer]）和会话列表页共用这一份逻辑，
 * 否则两边菜单迟早会做出两种行为。用 [rememberRowActionState] 各持一份实例。
 */
@Stable
class RowActionState {
    var menuSession by mutableStateOf<Session?>(null)
    var menuProject by mutableStateOf<SessionGroup?>(null)
    var renaming by mutableStateOf<Session?>(null)
    var confirmDelete by mutableStateOf<Session?>(null)
    var confirmRemove by mutableStateOf<SessionGroup?>(null)
}

@Composable
fun rememberRowActionState(): RowActionState = remember { RowActionState() }

/**
 * 菜单本体。放在页面的根作用域里（不要放进 LazyColumn 的 item 里，否则滚走就没了）。
 *
 * @param onSessionDeleted 会话被删掉后的回调（删的是当前会话时由调用方跳走）
 * @param onCopied 复制成功提示（默认用 Toast）
 */
@Composable
fun RowActionsHost(
    state: RowActionState,
    viewModel: SessionsViewModel,
    onSessionDeleted: (Session) -> Unit = {},
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val toast: (String) -> Unit = { msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }

    state.menuSession?.let { session ->
        ActionSheet(
            title = session.title.ifBlank { "未命名会话" },
            subtitle = session.id,
            actions = listOf(
                MenuAction(
                    label = "重命名",
                    icon = Icons.Default.DriveFileRenameOutline,
                    onClick = {
                        state.renaming = session
                        state.menuSession = null
                    },
                ),
                MenuAction(
                    label = "复制会话 ID",
                    icon = Icons.Default.ContentCopy,
                    onClick = {
                        clipboard.setText(AnnotatedString(session.id))
                        state.menuSession = null
                        toast("已复制会话 ID")
                    },
                ),
                MenuAction(
                    label = "复制会话",
                    icon = Icons.Default.CreateNewFolder,
                    detail = if (session.backend == "pi") "浅拷贝出新记录" else "只有 pi 后端的会话能复制",
                    enabled = session.backend == "pi",
                    onClick = {
                        state.menuSession = null
                        viewModel.copySession(session.id) { newId ->
                            toast(if (newId != null) "已复制出新会话" else "服务端拒绝了复制（这个后端可能不支持）")
                        }
                    },
                ),
                // 归档在桌面端是 IPC 能力，HTTP 网关没开放（实测 POST /api/sessions/:id/archive → 404），
                // 留着灰色入口，比直接藏起来更容易解释「为什么手机上没有」。
                MenuAction(
                    label = "归档会话",
                    icon = Icons.Default.FolderOff,
                    detail = "桌面端暂未通过 HTTP 开放归档接口",
                    enabled = false,
                    onClick = {},
                ),
                MenuAction(
                    label = "删除会话",
                    icon = Icons.Default.Delete,
                    detail = "连会话文件一起删，不能撤销",
                    danger = true,
                    onClick = {
                        state.confirmDelete = session
                        state.menuSession = null
                    },
                ),
            ),
            onDismiss = { state.menuSession = null },
        )
    }

    state.menuProject?.let { group ->
        ActionSheet(
            title = group.projectName.ifBlank { "未命名工作区" },
            subtitle = group.projectPath ?: "",
            actions = listOf(
                MenuAction(
                    label = "重命名工作区",
                    icon = Icons.Default.DriveFileRenameOutline,
                    detail = "桌面端暂未通过 HTTP 开放改名接口",
                    enabled = false,
                    onClick = {},
                ),
                MenuAction(
                    label = "复制工作区路径",
                    icon = Icons.Default.ContentCopy,
                    enabled = !group.projectPath.isNullOrBlank(),
                    onClick = {
                        val path = group.projectPath
                        if (!path.isNullOrBlank()) {
                            clipboard.setText(AnnotatedString(path))
                            toast("已复制路径")
                        }
                        state.menuProject = null
                    },
                ),
                MenuAction(
                    label = "移除工作区",
                    icon = Icons.Default.DeleteOutline,
                    detail = "只从列表移除，磁盘上的目录不动",
                    danger = true,
                    onClick = {
                        state.confirmRemove = group
                        state.menuProject = null
                    },
                ),
            ),
            onDismiss = { state.menuProject = null },
        )
    }

    state.renaming?.let { target ->
        RenameSessionDialog(
            current = target.title,
            onDismiss = { state.renaming = null },
            onConfirm = { title ->
                viewModel.rename(target.id, title)
                state.renaming = null
            },
        )
    }

    state.confirmDelete?.let { target ->
        ConfirmDialog(
            title = "删除这个会话？",
            body = "「${target.title.ifBlank { "未命名会话" }}」会被删掉，包括磁盘上的会话文件。删完不能恢复。",
            confirmText = "删除",
            onConfirm = {
                viewModel.deleteSession(target.id)
                state.confirmDelete = null
                onSessionDeleted(target)
            },
            onDismiss = { state.confirmDelete = null },
        )
    }

    state.confirmRemove?.let { group ->
        ConfirmDialog(
            title = "移除这个工作区？",
            body = "「${group.projectName.ifBlank { "未命名工作区" }}」会从项目列表里消失（它的会话也一起不见）。磁盘上的目录不会被删。",
            confirmText = "移除",
            onConfirm = {
                viewModel.removeProject(group.projectId)
                state.confirmRemove = null
            },
            onDismiss = { state.confirmRemove = null },
        )
    }
}
