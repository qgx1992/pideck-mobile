package dev.pideck.nativeapp.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pideck.nativeapp.data.Session
import dev.pideck.nativeapp.ui.sessions.RenameSessionDialog
import dev.pideck.nativeapp.ui.sessions.SessionsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 聊天页的左侧栏：换会话不用退出再进来。
 *
 * 数据直接复用 [SessionsViewModel]（同一个 deviceId，ViewModel 由聊天页这个回退栈条目
 * 作用域持有），折叠状态也跟会话列表页共用 —— 在哪边收起，另一边也是收起的。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionDrawer(
    deviceId: String,
    currentSessionId: String,
    onPickSession: (String) -> Unit,
    onOpenSessions: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDevices: () -> Unit,
    viewModel: SessionsViewModel = hiltViewModel(),
) {
    val groups = viewModel.groups
    val state = viewModel.state
    // 长按会话 → 重命名；长按项目标题 → 只留它展开
    var renaming by remember { mutableStateOf<Session?>(null) }

    ModalDrawerSheet {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp)) {
            Text(
                viewModel.deviceName?.ifBlank { null } ?: "PiDeck",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${state?.sessions?.size ?: 0} 个会话",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 12.dp),
        ) {
            groups.forEach { group ->
                val collapsed = group.projectId in viewModel.collapsedGroups
                item(key = "h-${group.projectId}") {
                    DrawerGroupHeader(
                        name = group.projectName.ifBlank { "未命名项目" },
                        count = group.sessions.size,
                        collapsed = collapsed,
                        onToggle = { viewModel.toggleGroup(group.projectId) },
                        onSolo = { viewModel.soloGroup(group.projectId) },
                    )
                }
                if (!collapsed) {
                    items(group.sessions, key = { it.id }) { session ->
                        DrawerSessionRow(
                            session = session,
                            running = viewModel.isRunning(session.id),
                            current = session.id == currentSessionId,
                            onClick = {
                                if (session.id != currentSessionId) onPickSession(session.id)
                            },
                            onLongClick = { renaming = session },
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
        Column(Modifier.padding(vertical = 6.dp, horizontal = 8.dp)) {
            NavigationDrawerItem(
                label = { Text("全部会话") },
                icon = { Icon(Icons.Default.KeyboardArrowRight, contentDescription = null) },
                selected = false,
                onClick = onOpenSessions,
            )
            NavigationDrawerItem(
                label = { Text("设置") },
                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                selected = false,
                onClick = onOpenSettings,
            )
            NavigationDrawerItem(
                label = { Text("切换设备") },
                icon = { Icon(Icons.Default.Devices, contentDescription = null) },
                selected = false,
                onClick = onOpenDevices,
            )
        }
    }

    renaming?.let { target ->
        RenameSessionDialog(
            current = target.title,
            onDismiss = { renaming = null },
            onConfirm = { title ->
                viewModel.rename(target.id, title)
                renaming = null
            },
        )
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun DrawerGroupHeader(
    name: String,
    count: Int,
    collapsed: Boolean,
    onToggle: () -> Unit,
    onSolo: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onToggle, onLongClick = onSolo)
            .padding(start = 12.dp, end = 20.dp, top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (collapsed) Icons.Default.KeyboardArrowRight else Icons.Default.KeyboardArrowDown,
            contentDescription = if (collapsed) "展开" else "收起",
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            name,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (collapsed) "$count" else "$count",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun DrawerSessionRow(
    session: Session,
    running: Boolean,
    current: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val background =
        if (current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    Surface(color = background) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(start = 34.dp, end = 20.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .background(
                        if (running) Color(0xFF4ADE80) else MaterialTheme.colorScheme.outlineVariant,
                        CircleShape,
                    ),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    session.title.ifBlank { "未命名会话" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    relativeTime(session.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/** 自己算，不用 locale —— CI / 不同系统语言下 toLocaleXxx 会分叉。 */
private fun relativeTime(epochMillis: Long): String {
    if (epochMillis <= 0) return ""
    val now = System.currentTimeMillis()
    val diff = now - epochMillis
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000} 分钟前"
        diff < 86_400_000 -> "${diff / 3_600_000} 小时前"
        diff < 7 * 86_400_000 -> "${diff / 86_400_000} 天前"
        else -> SimpleDateFormat("MM-dd", Locale.US).format(Date(epochMillis))
    }
}
