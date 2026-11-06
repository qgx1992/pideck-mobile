package dev.pideck.nativeapp.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import dev.pideck.nativeapp.ui.theme.BackendBadge
import dev.pideck.nativeapp.ui.theme.softPress
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.saveable.rememberSaveable
import dev.pideck.nativeapp.data.AgentBackend
import dev.pideck.nativeapp.data.SessionActivity
import dev.pideck.nativeapp.ui.common.SearchField
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import dev.pideck.nativeapp.ui.theme.AppButton
import dev.pideck.nativeapp.ui.theme.AppButtonStyle
import dev.pideck.nativeapp.ui.theme.AppIconButton
import dev.pideck.nativeapp.ui.sessions.RowActionsHost
import dev.pideck.nativeapp.ui.sessions.rememberRowActionState
import dev.pideck.nativeapp.ui.sessions.SessionsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 侧栏宽度（默认 360dp 在 390dp 宽的机器上几乎满屏）。 */
private val DrawerWidth = 300.dp

/** 展开的项目组一次最多露出多少个会话，剩下的折进「显示其余 N 个」。 */
private const val DRAWER_SESSION_PREVIEW = 5

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
    onOpenHome: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDevices: () -> Unit,
    viewModel: SessionsViewModel = hiltViewModel(),
) {
    val groups = viewModel.visibleGroups
    var showSearch by rememberSaveable { mutableStateOf(false) }
    val state = viewModel.state
    // 长按菜单（会话 / 工作区），逻辑与列表页共用
    val actions = rememberRowActionState()

    // 抽屉每次打开先问一次运行态：`/api/state` 带 ETag 且只在 ViewModel 初始化时拉，
    // 抽屉里的状态点会一直是「进入时那一刻」的 —— 别处刚开跑、刚跑完都看不出来。
    LaunchedEffect(Unit) { viewModel.refreshRuntimes() }

    // 宽度别用默认的 360dp：390dp 宽的机器上几乎占满，右侧只留一条缝，看着像整页切换。
    ModalDrawerSheet(modifier = Modifier.width(DrawerWidth)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
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
            AppIconButton(
                icon = Icons.Default.Search,
                contentDescription = "搜索会话",
                onClick = {
                    showSearch = !showSearch
                    if (!showSearch) viewModel.search("")
                },
                size = 34,
                container = MaterialTheme.colorScheme.surfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            // 设置放右上角：它不常用，但每次都翻到底很烦
            AppIconButton(
                icon = Icons.Default.Settings,
                contentDescription = "设置",
                onClick = onOpenSettings,
                size = 34,
                container = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
        // 新建会话与切换设备同一行 —— 这两件事随手就要用到，放最上面
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppButton(
                text = "新会话",
                onClick = onOpenHome,
                icon = Icons.Default.Add,
                style = AppButtonStyle.Filled,
                modifier = Modifier.weight(1f),
                fillWidth = true,
            )
            AppButton(
                text = "切换设备",
                onClick = onOpenDevices,
                icon = Icons.Default.Devices,
                style = AppButtonStyle.Tonal,
                modifier = Modifier.weight(1f),
                fillWidth = true,
            )
        }
        if (showSearch) {
            SearchField(
                value = viewModel.query,
                onValueChange = viewModel::search,
                onDismiss = {
                    viewModel.search("")
                    showSearch = false
                },
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)

        // 分段（活动 / 聊天）：桌面端左侧栏顶上那排 pill 的移动版。
        // 只切这一个列表，「新会话 / 切换设备 / 搜索」在所有段里都保留。
        DrawerSectionBar()

        // 展开后每组先露 5 条，剩下的收进「显示其余 N 个」：项目一多整列就翻不到头
        var expandedRest by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }

        if (DrawerTabState.current == DrawerSection.Active) {
            DrawerActivePane(
                sessions = viewModel.allSessions,
                currentSessionId = currentSessionId,
                onPickSession = onPickSession,
                onLongClickSession = { actions.menuSession = it },
                onPollRuntimes = viewModel::refreshRuntimes,
                modifier = Modifier.weight(1f),
            )
        } else LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 10.dp,
                end = 10.dp,
                bottom = 12.dp,
            ),
        ) {
            groups.forEach { group ->
                val collapsed = group.projectId in viewModel.collapsedGroups
                // 先算出这一组当前会露哪几条：组头要不要接管「运行中」状态点就靠它判断
                val showAllGroup = group.projectId in expandedRest
                val previewShown = if (showAllGroup) {
                    group.sessions
                } else {
                    group.sessions.take(DRAWER_SESSION_PREVIEW)
                }
                // 「正在动」的会话：运行中或在等用户回答 —— 完成/失败是静态结果，不需要在组头强调
                val activeIds = group.sessions.mapNotNull { s ->
                    val status = SessionActivity.statusOf(s.id)
                    if (status == SessionActivity.Status.Running || status == SessionActivity.Status.Waiting) {
                        s.id to status
                    } else {
                        null
                    }
                }
                // 折叠时整组都不露行；展开但被「先露 5 条」截掉时同样看不到 —— 两种情况都由组头显示
                val headerStatus = activeIds.firstOrNull { (id, _) -> collapsed || previewShown.none { it.id == id } }
                    ?.second
                item(key = "g-${group.projectId}") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f),
                                shape = RoundedCornerShape(12.dp),
                            )
                    ) {
                        DrawerGroupHeader(
                            name = group.projectName.ifBlank { "未命名项目" },
                            count = group.sessions.size,
                            collapsed = collapsed,
                            status = headerStatus,
                            onToggle = { viewModel.toggleGroup(group.projectId) },
                            onLongPress = { actions.menuProject = group },
                        )
                        if (!collapsed) {
                            val showAll = showAllGroup
                            val shown = previewShown
                            shown.forEach { session ->
                                DrawerSessionRow(
                                    session = session,
                                    status = SessionActivity.statusOf(session.id),
                                    current = session.id == currentSessionId,
                                    acpToolName = state?.acpTools
                                        ?.firstOrNull { it.id == session.acpToolId }
                                        ?.name,
                                    onClick = {
                                        if (session.id != currentSessionId) onPickSession(session.id)
                                    },
                                    onLongClick = { actions.menuSession = session },
                                )
                            }
                            val rest = group.sessions.size - DRAWER_SESSION_PREVIEW
                            if (rest > 0) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            expandedRest = if (showAll) {
                                                expandedRest - group.projectId
                                            } else {
                                                expandedRest + group.projectId
                                            }
                                        }
                                        .padding(start = 34.dp, end = 16.dp, top = 2.dp, bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = if (showAll) "收起" else "显示其余 $rest 个",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

    }

    RowActionsHost(
        state = actions,
        viewModel = viewModel,
        onSessionDeleted = { deleted ->
            // 删掉的正是当前打开的会话：退回会话列表，别停在一个空壳页面上
            if (deleted.id == currentSessionId) onOpenSessions()
        },
    )
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun DrawerGroupHeader(
    name: String,
    count: Int,
    collapsed: Boolean,
    status: SessionActivity.Status?,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .softPress(RoundedCornerShape(12.dp), onClick = onToggle, onLongClick = onLongPress)
            // 上下留白取同一值：卡片顶边到名称、名称到第一条会话看起来是等距的
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
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
        if (status != null) {
            StatusDot(status = status)
            Spacer(Modifier.width(2.dp))
        }
        Text(
            if (collapsed) "$count" else "$count",
            style = MaterialTheme.typography.labelSmall,
            color = if (status != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun DrawerSessionRow(
    session: Session,
    status: SessionActivity.Status,
    current: Boolean,
    acpToolName: String? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    // 选中态按「卡片里的一条」来做：缩进 + 圆角，不是通栏的方块。
    // 通栏方块会贴到工作区卡片的直边和圆角上，看着像贴了块不同材质的胶带。
    val background =
        if (current) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f) else Color.Transparent
    Surface(
        color = background,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .softPress(RoundedCornerShape(8.dp), onClick = onClick, onLongClick = onLongClick)
                // 左 29dp + 外面 5dp 缩进 = 34dp：卡片 12 + 箭头 18 + 间距 4，和组名左缘对齐
                .padding(start = 29.dp, end = 7.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(status = status)
            Spacer(Modifier.width(2.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    session.title.ifBlank { "未命名会话" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        relativeTime(session.updatedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // 后端标记：和列表页 / 首页「最近的会话」同一枚（ui/theme/Controls.kt 的 BackendBadge）。
                    // 这一栏最窄（390dp 机器上抽屉 300dp 左右），所以只留 3dp 间距、不给长名字换行。
                    BackendBadge(
                        label = AgentBackend.badgeOf(session.backend, acpToolName),
                        muted = session.backend.isNullOrBlank() || session.backend == "pi",
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
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
