package dev.pideck.nativeapp.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
 * 「活动」段的列表：**所有会话**，按「等回答 > 正在跑 > 其余」再按最近活动排。
 *
 * 这里不再是「只列运行中」—— 桌面端那一栏（`ActiveSessionsTree`）是「正在跑的 agent 行 +
 * 最近会话」拼起来的一个平铺列表，没有任何会话在跑时看到的就是最近会话那串；
 * 手机既没有 agent 级数据源，也不该在没人在跑时显示一片空白（0.5.37 头两稿就是这么错的）。
 * 运行态只用来排前面与画状态点，不再当过滤条件。
 *
 * 纯函数，`statusOf` 由调用方注入（生产里是 `SessionActivity::statusOf`）。
 */
internal fun activityDrawerSessions(
    sessions: List<Session>,
    statusOf: (String) -> SessionActivity.Status,
): List<Session> = sessions.sortedWith(
    compareBy<Session> { rankOf(statusOf(it.id)) }
        .thenByDescending { it.updatedAt },
)

private fun rankOf(status: SessionActivity.Status): Int = when (status) {
    SessionActivity.Status.Waiting -> 0
    SessionActivity.Status.Running -> 1
    else -> 2
}

/** 一次露多少条，点「显示更多」再加这么多（与桌面端 `RECENT_SESSIONS_INITIAL_VISIBLE = 10` 对齐）。 */
private const val DRAWER_ACTIVE_PREVIEW = 10

/** 「显示更多」一次加多少。 */
private const val DRAWER_ACTIVE_STEP = 12

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
 * 「活动」段：一条平铺的会话列表（桌面端 `ActiveSessionsTree` 的移动版）。
 *
 * 行就是「状态点 + 标题 + 相对时间」，和桌面端一致；不做「运行中 / 最近会话」两个小标题，
 * 也不按运行态过滤 —— 运行中的会话因为 [activityDrawerSessions] 的排序自然浮在最上面，
 * 状态点自己会区分正在跑（红点）/ 等你回答（橙点）/ 空闲（灰点）。
 *
 * 只列**会话**级：桌面端那一栏是 agent 级（子代理也单列一行），手机要通过
 * `/api/sessions/:id/subagents` 逐个会话查，放进抽屉会变成 N 次串行请求；子代理明细在
 * 会话页右侧工作区里已经有了。
 */
@Composable
internal fun DrawerActivePane(
    sessions: List<Session>,
    currentSessionId: String,
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
    // 状态与排序都是 Compose 可观察的：SessionActivity.statuses 一变，这里跟着重组
    val ordered = activityDrawerSessions(sessions) { SessionActivity.statusOf(it) }
    var limit by rememberSaveable { mutableIntStateOf(DRAWER_ACTIVE_PREVIEW) }
    val visible = if (limit >= ordered.size) ordered else ordered.take(limit)

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 2.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        if (visible.isEmpty()) {
            item(key = "active-empty") {
                Text(
                    "正在读取会话…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
                )
            }
        }
        items(visible, key = { "a-${it.id}" }) { session ->
            ActiveSessionRow(
                session = session,
                current = session.id == currentSessionId,
                onClick = {
                    if (session.id != currentSessionId) onPickSession(session.id)
                },
                onLongClick = { onLongClickSession(session) },
            )
        }
        val remaining = ordered.size - visible.size
        if (remaining > 0) {
            item(key = "active-more") {
                Text(
                    "显示更多（还有 $remaining 个）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .softPress(
                            RoundedCornerShape(8.dp),
                            onClick = { limit += DRAWER_ACTIVE_STEP },
                            onLongClick = {},
                        )
                        .padding(start = 22.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
                )
            }
        }
    }
}

/**
 * 「活动」段的一行：状态点 + 标题 + 右侧相对时间（桌面端 `RecentSessionRowItem` 同一套信息）。
 *
 * 独立组件，不去复用抽屉里那条 [DrawerSessionRow]、列表页的 `SessionRow`、首页的
 * `RecentSessionRow` —— 那三处各自安静，改一处会连带另两处（AGENTS.md 里记过这个坑）。
 */
@Composable
private fun ActiveSessionRow(
    session: Session,
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
                .padding(start = 8.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(status = SessionActivity.statusOf(session.id))
            Spacer(Modifier.width(8.dp))
            Text(
                session.title.ifBlank { "未命名会话" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                relativeTime(session.updatedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
