package dev.pideck.nativeapp.ui.chat

import dev.pideck.nativeapp.data.attempt
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pideck.nativeapp.data.AcpTool
import dev.pideck.nativeapp.data.Project
import dev.pideck.nativeapp.data.Session
import dev.pideck.nativeapp.data.SessionActivity
import dev.pideck.nativeapp.ui.common.SessionRowText
import dev.pideck.nativeapp.ui.common.relativeTime
import dev.pideck.nativeapp.ui.theme.softPress
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 侧栏顶部的分段（对应桌面端那个 `Tabs` 的 `active` / `chats` / `projects`）。
 *
 * 文案与桌面端一致：`sidebarActive` =「活动」、`sidebarChats` =「聊天」、`sidebarProjects` =「项目」。
 */
enum class DrawerSection(val label: String, val icon: ImageVector) {
    Active("活动", Icons.Filled.Bolt),
    Chats("聊天", Icons.Filled.Forum),
    Projects("项目", Icons.Filled.Folder),
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
 * 「活动」上半：宿主上**装载着运行时**的会话（`/api/state` 的 `runtimes`，idle 也算），按最近活动倒序。
 *
 * 照桌面端 `collectActiveSessionRows` 来的 —— 那一段不是「正在跑」（`status` 里只有一条
 * `running`，其余是 idle），而是「这台桌面上开着 agent 的会话」：跑完但运行时还挂着的也在上面。
 * 0.5.37 头几稿按 `Running/Waiting` 过滤，于是只有真在跑的那一条会出现，其余全掉到下面去。
 */
internal fun liveDrawerSessions(
    sessions: List<Session>,
    loadedSessionIds: Set<String>,
): List<Session> = sessions
    .filter { it.id in loadedSessionIds }
    .sortedByDescending { it.updatedAt }

/**
 * 「活动」下半：最近会话 —— **排除**上半已经出现过的那几条（桌面端 `collectRecentActivityRows`
 * 里用 `activeSessionIds` 去重，用户明确说过「上下两部分没有重复」），按活动时间倒序。
 */
internal fun recentDrawerSessions(
    sessions: List<Session>,
    loadedSessionIds: Set<String>,
): List<Session> = sessions
    .filter { it.id !in loadedSessionIds }
    .sortedByDescending { it.updatedAt }

/** 「最近会话」初露多少条（桌面端 `RECENT_SESSIONS_INITIAL_VISIBLE = 10`）。 */
private const val DRAWER_RECENT_INITIAL = 10

/** 「加载更多」一次加多少（桌面端 `RECENT_SESSIONS_PAGE_SIZE`）。 */
private const val DRAWER_RECENT_STEP = 12

/** 「活动」段看得见时刷运行态的间隔。和后台看护的 15s 不同：这里是人在看，要跟手。 */
private const val RUNTIMES_POLL_MS = 5_000L

/** 胶囊条的内缩（拖动手感用，和样式上的内边距是同一个数）。 */
private val DRAWER_BAR_PAD = 3.dp

/** 胶囊条高度：图标与文字并排后 42dp 就够（用户指定；早先上下堆叠时是 54dp）。 */
private val DRAWER_BAR_HEIGHT = 42.dp

/** 松手后按速度往前多看多少秒：0.12s 大致等于「快速一甩前进一段」。 */
private const val DRAG_FLING_SECONDS = 0.12f

/**
 * 松手时该吸附到哪一段。
 *
 * @param offsetFraction 指示块此刻的连续位置（0 = 第一段，1 = 第二段……）
 * @param velocitySectionsPerSecond 每秒划过多少段（横向速度 ÷ 段宽）
 * @param count 段数
 */
internal fun dragTargetSection(
    offsetFraction: Float,
    velocitySectionsPerSecond: Float,
    count: Int,
): Int {
    if (count <= 1) return 0
    val predicted = offsetFraction + velocitySectionsPerSecond * DRAG_FLING_SECONDS
    return predicted.roundToInt().coerceIn(0, count - 1)
}

/**
 * 侧栏顶部那一排分段（活动 / 聊天），样子照 KernelSU 那种 Material 3 expressive 的选择块：
 * **图标在上、文字在下**，选中的那格垫一块圆角实心块（浅色主题纯白、深色主题提亮两档）带投影，
 * 块随手指滑动、拖离原位时横向撑开最多 12% ── 扁扁一条纯色块的观感就是「不像按钮」。
 *
 * 可以左右拖（0.5.37 用户要求「拖动到哪一类就展开哪一类」）：底块跟着手走，松手用 spring
 * 吸附到最近一段并切过去（有一点点回弹，不是硬停）；点按照旧直接跳。
 *
 * 点按**不要水波纹**：准备拖动时手指按下去先出一圈灰，看着像误触。这里用
 * `clickable(indication = null)`，反馈只体现为底块滑过去 + 图标/文字变强调色。
 *
 * 实现要点：底块用 `drawBehind` 画（读 `Animatable.value` 只触发重绘、不触发布局），
 * 段宽取 `onSizeChanged` 的实测宽度；速度用 `VelocityTracker` 自己算 ——
 * `detectHorizontalDragGestures` 的 `onDragEnd` 不带速度。
 */
@Composable
internal fun DrawerSectionBar(modifier: Modifier = Modifier) {
    val sections = DrawerSection.entries
    val count = sections.size
    val current = DrawerTabState.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    val isLight = scheme.surface.luminance() > 0.5f
    // 选择块：浅色主题纯白，深色主题比轨道亮两档（只亮一档时底边和轨道糊在一起）
    val thumbColor = if (isLight) Color.White else Color(0xFF3A404B)
    val thumbShadow = if (isLight) Color(0x1A000000) else Color(0x66000000)
    val glow = scheme.primary
    val padPx = with(density) { DRAWER_BAR_PAD.toPx() }
    var widthPx by remember { mutableFloatStateOf(0f) }
    val segmentPx = if (widthPx > 0f) (widthPx - padPx * 2f) / count else 0f
    val offset = remember { Animatable(0f) }
    // 回弹：比 tween 多一点"甩过去再收住"的手感
    val settleSpec = spring<Float>(dampingRatio = 0.62f, stiffness = 420f)

    // 选中项变化（点按 / 拖到位 / 别处改状态）都滑到目标位置
    LaunchedEffect(current, segmentPx) {
        if (segmentPx > 0f) {
            offset.animateTo(targetValue = current.ordinal * segmentPx, animationSpec = settleSpec)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .height(DRAWER_BAR_HEIGHT + DRAWER_BAR_PAD * 2)
            .clip(RoundedCornerShape(18.dp))
            .background(scheme.surfaceContainerHigh)
            .onSizeChanged { widthPx = it.width.toFloat() }
            .drawBehind {
                if (segmentPx <= 0f || widthPx <= 0f) return@drawBehind
                val height = (size.height - padPx * 2f).coerceAtLeast(1f)
                if (height <= 1f) return@drawBehind
                // 离最近一格有多远（0..1）：拖动时用它撑开选择块
                val snap = offset.value / segmentPx
                val lean = abs(snap - snap.roundToInt()).coerceIn(0f, 0.5f) * 2f
                val growth = segmentPx * 0.12f * lean
                val left = offset.value + padPx - growth / 2f
                val width = segmentPx + growth
                val radius = CornerRadius(height * 0.42f)
                // 投影（两片叠出柔和边）
                drawRoundRect(
                    color = thumbShadow.copy(alpha = thumbShadow.alpha * 0.55f),
                    topLeft = Offset(left, padPx + 1.5f),
                    size = Size(width, height),
                    cornerRadius = radius,
                )
                drawRoundRect(
                    color = thumbShadow,
                    topLeft = Offset(left, padPx),
                    size = Size(width, height),
                    cornerRadius = radius,
                )
                // 选择块本体
                drawRoundRect(
                    color = thumbColor,
                    topLeft = Offset(left, padPx),
                    size = Size(width, height),
                    cornerRadius = radius,
                )
                // 被拖动时补一层很淡的强调色，让"正在被拖"这件事看得更明确
                if (lean > 0f) {
                    drawRoundRect(
                        color = glow.copy(alpha = 0.06f * lean),
                        topLeft = Offset(left, padPx),
                        size = Size(width, height),
                        cornerRadius = radius,
                    )
                }
            }
            .pointerInput(count, segmentPx) {
                if (segmentPx <= 0f) return@pointerInput
                val maxOffset = (count - 1) * segmentPx
                val tracker = VelocityTracker()
                fun settle(target: Int) {
                    if (sections[target] != DrawerTabState.current) {
                        // 交给上面那个 LaunchedEffect 滑过去（keys 变了才会重跑）
                        DrawerTabState.select(sections[target])
                    } else {
                        scope.launch {
                            offset.animateTo(targetValue = target * segmentPx, animationSpec = settleSpec)
                        }
                    }
                }
                detectHorizontalDragGestures(
                    onDragStart = { tracker.resetTracking() },
                    onDragCancel = { settle(dragTargetSection(offset.value / segmentPx, 0f, count)) },
                    onHorizontalDrag = { change, dragAmount ->
                        tracker.addPosition(change.uptimeMillis, change.position)
                        change.consume()
                        scope.launch { offset.snapTo((offset.value + dragAmount).coerceIn(0f, maxOffset)) }
                    },
                    onDragEnd = {
                        val velocityPx = attempt { tracker.calculateVelocity().x }.getOrDefault(0f)
                        settle(
                            dragTargetSection(
                                offsetFraction = offset.value / segmentPx,
                                velocitySectionsPerSecond = velocityPx / segmentPx,
                                count = count,
                            ),
                        )
                    },
                )
            },
        // 三格必须落在选择块的坐标里：Box 的内边距不参与布局，不补这两处
        // 文字会整体偏上 3dp、外侧两格还会各偏 1dp 左右（用户报「文本在遮罩里不够居中」）
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(DRAWER_BAR_HEIGHT)
                .padding(horizontal = DRAWER_BAR_PAD),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            sections.forEach { section ->
                val selected = section == current
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { DrawerTabState.select(section) },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        section.icon,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                        tint = if (selected) glow else scheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        section.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) glow else scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * 「活动」段：上半是**装载着运行时的会话**（桌面端的 agent 行），下半是**最近会话**。
 *
 * 两段之间不重复：下半会把上半已经列出的会话剔掉（用户明确要求 —— 「上下两部分是没有重复的」）。
 * 上半没有小标题，空的时候只留一行「暂无运行中的 Agent 会话」（桌面端 `sidebarActiveEmpty`）；
 * 下半有一个吸顶小标题「最近会话 · 共 N 条」+ 右侧「显示前 N 项 / 全部已显示」，
 * 底部「加载更多 (N)」/「收起」。
 *
 * 只列**会话**级：桌面端上半是 agent 级（子代理也会出现），手机要通过
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
    acpTools: List<AcpTool>? = null,
    modifier: Modifier = Modifier,
) {
    // 运行态是「别处也在变」的东西：抽屉开着、别的机器/桌面端在跑的时候必须自己问。
    // 每 5 秒拉一次 `/api/sessions/runtimes`（约 3KB），离开这一段就自动停（LaunchedEffect 随组合注销）。
    // 只在「看得见」时轮询：App 退到后台、抽屉还开着的时候继续每 5 秒打一次宿主是白耗电。
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                onPollRuntimes()
                delay(RUNTIMES_POLL_MS)
            }
        }
    }
    // 状态与排序都是 Compose 可观察的：SessionActivity 一变，这里跟着重组
    val loaded = SessionActivity.loadedSessionIds
    val live = liveDrawerSessions(sessions, loaded)
    val recentAll = recentDrawerSessions(sessions, loaded)
    var limit by rememberSaveable { mutableIntStateOf(DRAWER_RECENT_INITIAL) }
    val recent = if (limit >= recentAll.size) recentAll else recentAll.take(limit)

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        if (sessions.isEmpty()) {
            item(key = "empty") {
                Text(
                    "正在读取会话…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
                )
            }
        }
        if (sessions.isNotEmpty() && live.isEmpty()) {
            item(key = "live-empty") {
                Text(
                    "暂无运行中的 Agent 会话",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 4.dp),
                )
            }
        }
        items(live, key = { "live-${it.id}" }) { session ->
            ActiveSessionRow(
                session = session,
                current = session.id == currentSessionId,
                acpToolName = acpTools?.firstOrNull { it.id == session.acpToolId }?.name,
                onClick = { if (session.id != currentSessionId) onPickSession(session.id) },
                onLongClick = { onLongClickSession(session) },
            )
        }
        if (recentAll.isNotEmpty()) {
            item(key = "recent-header") {
                RecentHeader(total = recentAll.size, shown = recent.size)
            }
            items(recent, key = { "recent-${it.id}" }) { session ->
                ActiveSessionRow(
                    session = session,
                    current = session.id == currentSessionId,
                    acpToolName = acpTools?.firstOrNull { it.id == session.acpToolId }?.name,
                    onClick = { if (session.id != currentSessionId) onPickSession(session.id) },
                    onLongClick = { onLongClickSession(session) },
                )
            }
            val remaining = recentAll.size - recent.size
            if (remaining > 0 || limit > DRAWER_RECENT_INITIAL) {
                item(key = "recent-footer") {
                    RecentFooter(
                        remaining = remaining,
                        canCollapse = limit > DRAWER_RECENT_INITIAL,
                        onMore = { limit += DRAWER_RECENT_STEP },
                        onCollapse = { limit = DRAWER_RECENT_INITIAL },
                    )
                }
            }
        }
    }
}

/** 「最近会话」小标题：左边标题 + 共 N 条，右边「显示前 M 项 / 全部已显示」（桌面端同款）。 */
@Composable
private fun RecentHeader(total: Int, shown: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "最近会话",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "共 $total 条",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (shown >= total) "全部已显示" else "显示前 $shown 项",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/** 列表底部那两颗按钮：加载更多 / 收起。 */
@Composable
private fun RecentFooter(
    remaining: Int,
    canCollapse: Boolean,
    onMore: () -> Unit,
    onCollapse: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, top = 2.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (remaining > 0) {
            Text(
                "加载更多 ($remaining)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .softPress(RoundedCornerShape(8.dp), onClick = onMore, onLongClick = {})
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        if (canCollapse) {
            Text(
                "收起",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier
                    .softPress(RoundedCornerShape(8.dp), onClick = onCollapse, onLongClick = {})
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
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
    acpToolName: String? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background =
        if (current) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f) else Color.Transparent
    Surface(
        color = background,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.fillMaxWidth(),
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
            // 与抽屉里其它会话行同一份文字块（ui/common/SessionRowText.kt）：
            // 以前这一行是手写的，于是「后端标记」在这四处里漏的就是它（同名会话分不清 pi/dsh/acp）。
            SessionRowText(
                session = session,
                meta = relativeTime(session.updatedAt),
                acpToolName = acpToolName,
                titleStyle = MaterialTheme.typography.bodyMedium,
                metaColor = MaterialTheme.colorScheme.outline,
                verticalPadding = 1,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
