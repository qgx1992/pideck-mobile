package dev.pideck.nativeapp.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Forum
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pideck.nativeapp.data.Session
import dev.pideck.nativeapp.data.SessionActivity
import dev.pideck.nativeapp.ui.sessions.relativeTime
import dev.pideck.nativeapp.ui.theme.softPress
import kotlinx.coroutines.delay

/**
 * 侧栏顶部的分段（桌面端那个 `Tabs` 的 `active` / `chats`，`projects` 还没做）。
 *
 * 文案与桌面端一致：`sidebarActive` =「活动」、`sidebarChats` =「聊天」。
 */
enum class DrawerSection(val label: String, val icon: ImageVector) {
    Active("活动", Icons.Filled.Bolt),
    Chats("聊天", Icons.Filled.Forum),
}

/**
 * 当前选中的分段。
 *
 * 放单例而不是 `rememberSaveable`：抽屉每次开合都是新的组合，会话列表页与聊天页又是两个
 * ViewModel 实例，状态得落在进程里 —— 和 [dev.pideck.nativeapp.ui.sessions.GroupCollapse]
 * 同一个理由。不落盘：下次开 App 回到「聊天」，那是主用法。
 */
object DrawerTabState {
    var current by mutableStateOf(DrawerSection.Chats)
        private set

    fun select(section: DrawerSection) {
        current = section
    }

    /** 测试用：恢复默认。 */
    internal fun reset() {
        current = DrawerSection.Chats
    }
}

/**
 * 「活动」上半：正在跑或等你回答的会话。等你回答的排前面（那是真的卡住了），其余按最近活动。
 *
 * 纯函数，`statusOf` 由调用方注入（生产里是 `SessionActivity::statusOf`）。
 */
internal fun activeDrawerSessions(
    sessions: List<Session>,
    statusOf: (String) -> SessionActivity.Status,
): List<Session> = sessions
    .mapNotNull { session ->
        val status = statusOf(session.id)
        if (status == SessionActivity.Status.Running || status == SessionActivity.Status.Waiting) {
            session to status
        } else {
            null
        }
    }
    .sortedWith(
        compareBy<Pair<Session, SessionActivity.Status>> {
            if (it.second == SessionActivity.Status.Waiting) 0 else 1
        }.thenByDescending { it.first.updatedAt },
    )
    .map { it.first }

/** 「活动」下半：最近会话（按最后活动时间倒序）。 */
internal fun recentDrawerSessions(sessions: List<Session>, limit: Int): List<Session> {
    if (limit <= 0) return emptyList()
    return sessions.sortedByDescending { it.updatedAt }.take(limit)
}

/** 「最近会话」一次露多少条，点「显示更多」再加这么多。 */
private const val DRAWER_RECENT_PREVIEW = 12

/** 「显示更多」一次加多少。 */
private const val DRAWER_RECENT_STEP = 12

/** 「活动」段看得见时刷运行态的间隔。和后台看护的 15s 不同：这里是人在看，要跟手。 */
private const val RUNTIMES_POLL_MS = 5_000L

/**
 * 侧栏顶部那一排分段（桌面端 `Tabs` 的 pill 变体）：一枚胶囊里并排两段，选中那段是
 * 实心底 + 正文色，未选是透明底 + 次要色。
 *
 * 抽屉总宽 300dp，两段各占一半、图标 14dp + 两字标签，还剩不少余量；等「项目」那段也做进来
 * 时三段均分也放得下。
 */
@Composable
internal fun DrawerSectionBar(modifier: Modifier = Modifier) {
    val current = DrawerTabState.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        DrawerSection.entries.forEach { section ->
            val selected = section == current
            Surface(
                onClick = { DrawerTabState.select(section) },
                shape = RoundedCornerShape(50),
                color = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
                modifier = Modifier.weight(1f),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        section.icon,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = if (selected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        section.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

/**
 * 「活动」段：上半运行中、下半最近会话（桌面端 `ActiveSessionsTree` 的移动版）。
 *
 * 只列**会话**级：桌面端那一栏是 agent 级（子代理也单列一行），手机要通过
 * `/api/sessions/:id/subagents` 逐个会话查，放进抽屉会变成 N 次串行请求；子代理明细在
 * 会话页右侧工作区里已经有了。
 */
@Composable
internal fun DrawerActivePane(
    sessions: List<Session>,
    currentSessionId: String,
    projectNameOf: (String) -> String,
    onPickSession: (String) -> Unit,
    onLongClickSession: (Session) -> Unit,
    onPollRuntimes: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // 运行态是「别处也在变」的东西：抽屉开着、别的机器/桌面端在跑的时候必须自己问。
    // 每 5 秒拉一次 `/api/sessions/runtimes`（约 3KB），离开这一段就自动停（LaunchedEffect 随组合注销）。
    LaunchedEffect(Unit) {
        while (true) {
            onPollRuntimes()
            delay(RUNTIMES_POLL_MS)
        }
    }
    // 状态变化是 Compose 可观察的：SessionActivity.statuses 一变，这里跟着重组。
    val active = activeDrawerSessions(sessions) { SessionActivity.statusOf(it) }
    var recentLimit by rememberSaveable { mutableIntStateOf(DRAWER_RECENT_PREVIEW) }
    val recent = recentDrawerSessions(sessions, recentLimit)

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        item(key = "active-header") {
            PaneHeader(title = "运行中", count = active.size)
        }
        if (active.isEmpty()) {
            item(key = "active-empty") {
                Text(
                    if (sessions.isEmpty()) "正在读取会话…" else "暂无运行中的 Agent 会话",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 8.dp),
                )
            }
        }
        items(active, key = { "a-${it.id}" }) { session ->
            ActiveSessionRow(
                session = session,
                subtitle = projectNameOf(session.projectId),
                current = session.id == currentSessionId,
                onClick = {
                    if (session.id != currentSessionId) onPickSession(session.id)
                },
                onLongClick = { onLongClickSession(session) },
            )
        }

        if (sessions.isNotEmpty()) {
            item(key = "recent-header") {
                PaneHeader(title = "最近会话", count = null)
            }
            items(recent, key = { "r-${it.id}" }) { session ->
                ActiveSessionRow(
                    session = session,
                    subtitle = projectNameOf(session.projectId),
                    current = session.id == currentSessionId,
                    onClick = {
                        if (session.id != currentSessionId) onPickSession(session.id)
                    },
                    onLongClick = { onLongClickSession(session) },
                )
            }
            val remaining = recentDrawerSessions(sessions, Int.MAX_VALUE).size - recent.size
            if (remaining > 0) {
                item(key = "recent-more") {
                    Text(
                        "显示更多（还有 $remaining 个）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .softPress(
                                RoundedCornerShape(8.dp),
                                onClick = { recentLimit += DRAWER_RECENT_STEP },
                                onLongClick = {},
                            )
                            .padding(start = 34.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PaneHeader(title: String, count: Int?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (count != null) {
            Text(
                "$count",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/**
 * 「活动」段的一行：状态点 + 标题 + 项目名 · 相对时间。
 *
 * 独立组件，不去复用抽屉里那条 [DrawerSessionRow]、列表页的 `SessionRow`、首页的
 * `RecentSessionRow` —— 那三处各自安静，改一处会连带另两处（AGENTS.md 里记过这个坑）。
 */
@Composable
private fun ActiveSessionRow(
    session: Session,
    subtitle: String,
    current: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val background =
        if (current) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f) else Color.Transparent
    Surface(
        color = background,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .softPress(RoundedCornerShape(8.dp), onClick = onClick, onLongClick = onLongClick)
                .padding(start = 8.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(status = SessionActivity.statusOf(session.id))
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    session.title.ifBlank { "未命名会话" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOf(subtitle.ifBlank { "未命名项目" }, relativeTime(session.updatedAt))
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
