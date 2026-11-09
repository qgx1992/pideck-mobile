package dev.pideck.nativeapp.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.clickable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Splitscreen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.pideck.nativeapp.data.BusySendMode
import dev.pideck.nativeapp.data.defaultTranslateTarget
import dev.pideck.nativeapp.data.TurnStats
import dev.pideck.nativeapp.data.formatTurnStats
import dev.pideck.nativeapp.data.TimelineItem
import dev.pideck.nativeapp.data.Prefs
import dev.pideck.nativeapp.data.ProcessActivity
import dev.pideck.nativeapp.data.visibleMemberCount
import dev.pideck.nativeapp.data.ToolView
import dev.pideck.nativeapp.data.SessionModel
import dev.pideck.nativeapp.data.thinkingLabel
import dev.pideck.nativeapp.data.toTimelineItem
import dev.pideck.nativeapp.ui.theme.AppIconButton
import dev.pideck.nativeapp.ui.theme.ControlShape
import dev.pideck.nativeapp.ui.theme.softPress
import dev.pideck.nativeapp.data.ImagePrep
import dev.pideck.nativeapp.data.PrivilegeControl
import dev.pideck.nativeapp.data.FileNode
import dev.pideck.nativeapp.data.HostCommand
import dev.pideck.nativeapp.data.PendingImage
import dev.pideck.nativeapp.ui.theme.SelectChip
import java.text.SimpleDateFormat
import kotlinx.coroutines.launch
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    deviceId: String,
    onOpenSession: (String) -> Unit,
    onOpenSessions: () -> Unit,
    onOpenHome: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDevices: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    // 聊天页是新首页：左边不再是「返回设备列表」，而是能横向换会话的侧栏
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // 侧栏开着时返回键先收侧栏，而不是直接退出去
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
    // 草稿按会话各自保存：换会话再回来，写了一半的内容还在
    var draft by remember(viewModel.sessionId) {
        mutableStateOf(ChatDrafts.get(viewModel.sessionId))
    }

    // 并行问询面板点「插入输入框」：答案塞进草稿，面板随即收掉
    LaunchedEffect(viewModel.parallelInsert) {
        val text = viewModel.consumeParallelInsert() ?: return@LaunchedEffect
        draft = if (draft.isBlank()) text else draft.trimEnd() + System.lineSeparator() + text
        ChatDrafts.put(viewModel.sessionId, draft)
    }

    // 两个过程相关开关（设置 → 通用）。关掉「过程组显示」就是桌面端那个开关关掉的样子：全部平铺。
    val groupProcess by Prefs.processGroupFlow.collectAsState()
    val expandInterim by Prefs.expandInterimFlow.collectAsState()

    // 拍平成「行」：连续的过程条目（思考 / 工具）合成一个过程组，正文回答单独成行。
    val rows = remember(viewModel.items, viewModel.live, viewModel.streaming, groupProcess) {
        buildChatRows(
            items = viewModel.items,
            live = viewModel.live?.toTimelineItem(streaming = viewModel.streaming),
            groupProcess = groupProcess,
        )
    }

    // 贴底判定 = 「用户没往上翻」。
    // 之前用 firstVisibleItemIndex >= count-2 判断，一追加新消息这个式子立刻变 false，
    // 于是流式回复永远不跟随（这就是「不会自动滚动」的根因）。
    // 现在只在两个时刻改标记：用户开始拖列表 → false；列表再也滚不动（真到底了）→ true。
    // 追加内容不算「用户滚动」，所以不会把标记打掉。
    var atBottom by remember { mutableStateOf(true) }
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragging) {
        if (dragging) atBottom = false
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollForward }
            .collect { canForward -> if (!canForward) atBottom = true }
    }

    // 贴底时把列表钉在最底部：scrollOffset 给一个大值，LazyList 会自己夹到最大滚动量，
    // 长消息也能看到末尾（只用 scrollToItem(index) 只会把这条的顶部对齐）。
    suspend fun stickToBottom() {
        val count = listState.layoutInfo.totalItemsCount
        if (count <= 0) return
        listState.scrollToItem(count - 1, scrollOffset = Int.MAX_VALUE / 4)
    }

    // 只在「末尾那条变了」的时候跟随 —— 往上翻历史会往前面插条目，末尾没变就不会把人拽回底部。
    val lastKey = rows.lastOrNull()?.key
    // 跟随的判据不能只看正文长度：流式过程中先长的是思考块、工具入参/结果，
    // 正文还没开始动的时候高度已经在涨了，那时不跟就会「贴底也不跟」。
    val liveSignature = viewModel.live?.let { live ->
        live.text.length + live.reasoning.length +
            live.tools.sumOf {
                (it.input?.toString()?.length ?: 0) + (it.output?.toString()?.length ?: 0) +
                    (it.errorText?.length ?: 0)
            }
    } ?: 0
    // 流式期间每 250ms 补一次贴底 —— 过程组的高度变化（新工具卡、组自动展开）不都体现在
    // liveSignature 上，只靠它触发就会漏，表现就是「过程组不会自动贴底」。
    LaunchedEffect(viewModel.streaming) {
        while (viewModel.streaming) {
            if (atBottom) stickToBottom()
            kotlinx.coroutines.delay(250)
        }
    }
    LaunchedEffect(lastKey, liveSignature) {
        if (atBottom) {
            // 等这一帧布局量完再钉，否则钉的还是加高之前的底部
            withFrameNanos { }
            stickToBottom()
        }
    }
    // 键盘弹起 / 收起会压缩或放大视口。等这一轮布局量完再钉，否则钉的是「旧的底部」，
    // 键盘一冒出来内容就又跑到下面去了（这就是上一版键盘弹起不贴底的原因）。
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.viewportSize.height }.collect {
            if (atBottom) stickToBottom()
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onForeground() }

    // 模型表要提前拉：思考档位是从模型的 thinkingLevels 推出来的，
    // 以前只在点开模型选择时才拉，于是「思考」按钮要点一下模型才冒出来。
    // （这一次调用会等 ViewModel 里的设备就位再发请求 —— 以前它比设备读取先跑，
    //   直接空手回来，表现是「点开模型胶囊要点一下刷新才有列表」。）
    LaunchedEffect(Unit) { viewModel.loadModels() }
    // 上下文用量：进来先读一次，每轮跑完再对一次（用量只在运行时活着时拿得到）
    LaunchedEffect(Unit) { viewModel.refreshContext() }
    LaunchedEffect(viewModel.streaming) {
        if (!viewModel.streaming) viewModel.refreshContext()
    }

    var showWorkspace by remember { mutableStateOf(false) }
    // 顶栏的模型 / 思考档位选择器
    var showModelSheet by remember { mutableStateOf(false) }
    // 兜底：万一首次拉取失败（主机刚起 / 断线），点开选择器时再补一次
    LaunchedEffect(showModelSheet) {
        if (showModelSheet && viewModel.availableModels.isEmpty()) viewModel.loadModels()
    }
    var showThinkingSheet by remember { mutableStateOf(false) }
    // 本会话的历史提问：点一条跳回那一段
    var showHistory by remember { mutableStateOf(false) }
    // 历史跳转：key = 要落到的提问，tick 让「再点同一条」也能重新滚、重新闪
    var jumpKey by remember { mutableStateOf<String?>(null) }
    var jumpTick by remember { mutableIntStateOf(0) }
    // 闪谁：key + tick（tick 只是为了让同一个 key 也能再触发一次动画）
    var flashKey by remember { mutableStateOf<String?>(null) }
    var flashTick by remember { mutableIntStateOf(0) }
    // 顶栏溢出口：导出 HTML / 权限预设 / 上下文用量
    var showMore by remember { mutableStateOf(false) }
    var showPermission by remember { mutableStateOf(false) }
    var showSecurity by remember { mutableStateOf(false) }
    var showContext by remember { mutableStateOf(false) }
    // 图片附件：系统相册多选；粘贴走剪贴板
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents(),
    ) { uris -> viewModel.addImages(uris) }
    var showAttachSheet by remember { mutableStateOf(false) }
    var showPrompts by remember { mutableStateOf(false) }
    var panelTab by remember { mutableStateOf(ComposerPanelTab.Quick) }

    /**
     * 真正「发出去」的那一步：输入框的发送键与快捷消息的「直接发送」共用。
     *
     * 客户端接管的斜杠命令不发给宿主：`/compact` 交给运行时压缩、`/new` 回首页选工作区。
     */
    val submitText: (String, BusySendMode) -> Unit = { text, mode ->
        when {
            slashIntent(text)?.id == "compact" ->
                viewModel.compact(slashArgument(text, slashIntent(text)!!).ifBlank { null })

            slashIntent(text)?.id == "new" -> onOpenHome()
            // 还在跑：按用户选的档位投递（加入当前回合 / 排队 / 并行问）
            viewModel.streaming -> if (text.isNotBlank()) viewModel.sendWhileBusy(text, mode)
            else -> viewModel.send(text)
        }
    }
    val sendText: (String) -> Unit = { text -> if (text.isNotBlank()) submitText(text, viewModel.effectiveBusyMode) }

    // 往输入框塞东西（提示词模板 / 快捷消息）：已有内容就和它空一行接上，不要覆盖用户写到一半的草稿
    val insertIntoDraft: (String) -> Unit = { text ->
        val merged = if (draft.isBlank()) text else draft.trimEnd() + System.lineSeparator() + System.lineSeparator() + text
        draft = merged
        ChatDrafts.put(viewModel.sessionId, merged)
    }

    // 长按消息 → 复制全文 / 复制代码块 / 自由复制
    var actionTarget by remember { mutableStateOf<TimelineItem?>(null) }
    // 思考 / 工具点开的那块浮窗（与「自由复制」同一套外壳）
    var detail by remember { mutableStateOf<DetailContent?>(null) }
    var freeCopyTarget by remember { mutableStateOf<TimelineItem?>(null) }

    LaunchedEffect(viewModel.error) {
        viewModel.error?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    // 右侧工作区面板要盖住顶栏与输入条，所以放在最外层 Box 里（内容区放不下它）
    Box(modifier = Modifier.fillMaxSize()) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            SessionDrawer(
                deviceId = deviceId,
                currentSessionId = viewModel.sessionId,
                onPickSession = onOpenSession,
                onOpenHome = {
                    scope.launch { drawerState.close() }
                    onOpenHome()
                },
                onOpenSessions = {
                    scope.launch { drawerState.close() }
                    onOpenSessions()
                },
                onOpenSettings = {
                    scope.launch { drawerState.close() }
                    onOpenSettings()
                },
                onOpenDevices = {
                    scope.launch { drawerState.close() }
                    onOpenDevices()
                },
            )
        },
    ) {
    Scaffold(
        modifier = Modifier.statusBarsPadding(),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = viewModel.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (viewModel.hasOlder) {
                            Text(
                                text = "还有更早的消息",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    AppIconButton(
                        icon = Icons.Default.Menu,
                        contentDescription = "会话列表",
                        onClick = { scope.launch { drawerState.open() } },
                        modifier = Modifier.padding(start = 6.dp),
                    )
                },
                actions = {
                    val todo = viewModel.workspaceTodo
                    if (todo != null && todo.todos.isNotEmpty()) {
                        Text(
                            "${todo.done}/${todo.todos.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                    AppIconButton(
                        icon = Icons.Filled.Splitscreen,
                        contentDescription = "工作区",
                        onClick = {
                            viewModel.loadWorkspace()
                            showWorkspace = true
                        },
                        modifier = Modifier.padding(end = 2.dp),
                    )
                    // 导出 / 权限预设 / 上下文：低频动作收进溢出菜单，顶栏只留最常用的两个图标
                    Box {
                        AppIconButton(
                            icon = Icons.Default.MoreVert,
                            contentDescription = "更多",
                            onClick = { showMore = true },
                            modifier = Modifier.padding(end = 6.dp),
                        )
                        DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text("导出会话 HTML")
                                        Text(
                                            if (viewModel.exportBusy) "正在生成…" else "存到「下载/PiDeck」",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                leadingIcon = { Icon(Icons.Default.FileDownload, contentDescription = null) },
                                enabled = !viewModel.exportBusy,
                                onClick = {
                                    showMore = false
                                    viewModel.exportHtml()
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text("重启会话")
                                        Text(
                                            when {
                                                viewModel.restarting -> "正在重启，等它跑完…"
                                                else -> "上下文不丢，重载资源"
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                                enabled = !viewModel.restarting,
                                onClick = {
                                    showMore = false
                                    viewModel.restartRuntime()
                                },
                            )
                            // 权限位按后端分发：pi → 安全等级（安全门），dsh → 权限预设，acp / 生图生视频 → 不摆。
                            // 以前这里不分后端一律摆「权限预设」，调到 pi 会话上会写坏记录，后续对话起不来。
                            when (viewModel.securityControl) {
                                PrivilegeControl.SecurityLevel -> DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text("安全等级")
                                            Text(
                                                viewModel.securityLevelLabel,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    },
                                    leadingIcon = { Icon(Icons.Default.Shield, contentDescription = null) },
                                    onClick = {
                                        showMore = false
                                        viewModel.refreshSecurity()
                                        showSecurity = true
                                    },
                                )
                                PrivilegeControl.PermissionPreset -> DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text("权限预设")
                                            Text(
                                                dev.pideck.nativeapp.data.PermissionPresets.labelOf(viewModel.permissionPreset),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    },
                                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                                    onClick = {
                                        showMore = false
                                        showPermission = true
                                    },
                                )
                                PrivilegeControl.None -> Unit
                            }
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text("上下文用量与压缩")
                                        viewModel.contextUsage?.let { usage ->
                                            Text(
                                                if (usage.known) "${usage.percentLabel} · ${usage.detail}" else "暂未取到用量",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                },
                                leadingIcon = { Icon(Icons.Default.DataUsage, contentDescription = null) },
                                onClick = {
                                    showMore = false
                                    viewModel.refreshContext()
                                    viewModel.refreshCacheStat()
                                    showContext = true
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )

            // 模型 / 思考档位：桌面端在输入条里选，手机上顶栏下方排一排胶囊更省空间
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 12.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 模型胶囊吃掉剩余的宽度（内部封顶 188dp），而不是固定宽度占位：
                // 上下文胶囊一出现，固定宽度的 Row 就没位置给后面的历史按钮，
                // 会被整块挤出屏幕（实测「查了上下文，历史按钮不见了」）。
                if (!viewModel.isPiBackend) {
                    // 非 pi 后端（DSH / ACP / 图像视频）：模型与思考档位不归这里管 ——
                    // 宿主对 acp 会话根本不落 model/thinkingLevel，DSH 走它自己的模型体系。
                    // 摆一个点了不生效的选择器比不摆更误导，所以只留一个后端标记。
                    viewModel.backendLabel?.let { label ->
                        SelectChip(
                            text = label,
                            onClick = {},
                            icon = Icons.Default.Hub,
                            accent = true,
                            enabled = false,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                } else {
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    SelectChip(
                        // 记录里没模型时不要把用户的选择误读成「未选」：手机新建的会话
                        // 记录本来就不带模型，实际跑的是电脑端的默认模型（运行时起来后会换成
                        // 真实名字，见 ChatViewModel.displayModel）
                        text = viewModel.displayModel?.shortLabel ?: "默认模型",
                        onClick = { showModelSheet = true },
                        icon = Icons.Default.Memory,
                        trailing = Icons.Filled.KeyboardArrowDown,
                        accent = viewModel.displayModel == null,
                        modifier = Modifier.widthIn(max = 188.dp),
                    )
                }
                val levels = viewModel.thinkingLevels
                if (levels.isNotEmpty()) {
                    // 没选过档位就是跟随电脑端默认：别再显示成「不思考」—— 那是档位表的第一项，
                    // 与 pi 实际用的 high 差得远
                    val level = viewModel.displayThinking?.takeIf { it in levels }
                    SelectChip(
                        text = "思考 ${level?.let(::thinkingLabel) ?: "默认"}",
                        onClick = { showThinkingSheet = true },
                        trailing = Icons.Filled.KeyboardArrowDown,
                        accent = level in setOf("high", "xhigh", "max"),
                    )
                }
                }
                // 上下文占用：只在使用量拿得到时出现，点开看明细并压缩
                val usage = viewModel.contextUsage
                if (usage != null && usage.known) {
                    SelectChip(
                        text = "上下文 ${usage.percentLabel}",
                        onClick = {
                            viewModel.refreshContext()
                            viewModel.refreshCacheStat()
                            showContext = true
                        },
                        accent = usage.overflow || usage.contextPercent >= 85,
                    )
                }
                // 本会话的历史：列出问过的每一句，点一条滚回去
                AppIconButton(
                    icon = Icons.Default.History,
                    contentDescription = "本会话历史",
                    onClick = { showHistory = true },
                    size = 34,
                )
            }
            }
        },
        bottomBar = {
            Column {
                // agent 正在等回答时，卡片常驻在输入条上方（滚到历史里也不会错过）
                viewModel.pendingAsk?.let { request ->
                    AskCard(
                        request = request,
                        busy = viewModel.askBusy,
                        error = viewModel.askError,
                        onRespond = { viewModel.respondAsk(it) },
                        onCancel = { viewModel.respondAsk(dev.pideck.nativeapp.data.AskUi.cancelled()) },
                        onDismissError = { viewModel.dismissAskError() },
                    )
                }
                if (viewModel.queuedTexts.isNotEmpty()) {
                    QueuedBanner(viewModel.queuedTexts)
                }
                viewModel.parallelAsk?.let { ask ->
                    ParallelAskCard(
                        ask = ask,
                        onInsert = { viewModel.insertParallelAnswer() },
                        onClose = { viewModel.closeParallelAsk() },
                    )
                }
                TurnStatsLine(stats = viewModel.turnStats, streaming = viewModel.streaming)
                Composer(
                value = draft,
                onValueChange = { text ->
                    draft = text
                    ChatDrafts.put(viewModel.sessionId, text)
                },
                sending = viewModel.streaming,
                images = viewModel.images,
                imageError = viewModel.imageError,
                onAttach = { showAttachSheet = true },
                onRemoveImage = { viewModel.removeImage(it) },
                onDismissImageError = { viewModel.dismissImageError() },
                busyMode = viewModel.effectiveBusyMode,
                busyModes = viewModel.busyModes,
                onBusyModeChange = { viewModel.rememberBusySendMode(it) },
                onSend = { mode ->
                    val text = draft
                    draft = ""
                    ChatDrafts.clear(viewModel.sessionId)
                    atBottom = true
                    submitText(text, mode)
                },
                onStop = { viewModel.stop() },
                mentionFiles = viewModel.mentionFiles,
                mentionIndexing = viewModel.mentionIndexing,
                hostCommands = viewModel.hostCommands,
                onNeedMention = { viewModel.buildMentionIndex() },
                onNeedCommands = { viewModel.ensureCommands() },
                onOpenPrompts = {
                    viewModel.closePromptPreview()
                    viewModel.openPromptLibrary()
                    viewModel.loadQuickMessages()
                    viewModel.loadLocalPrompts()
                    showPrompts = true
                },
                promptError = viewModel.promptInsertError,
                onDismissPromptError = { viewModel.promptInsertError = null },
                onCommand = { entry ->
                    when (entry.builtin) {
                        // 客户端接管：新会话直接回首页，压缩把命令写回输入框（可以再补一句提示词）
                        "new" -> {
                            draft = ""
                            ChatDrafts.clear(viewModel.sessionId)
                            onOpenHome()
                        }

                        else -> {
                            draft = "/${entry.insert} "
                            ChatDrafts.put(viewModel.sessionId, draft)
                        }
                    }
                },
                )
            }
        },
    ) { inner ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            when {
                viewModel.loading -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    strokeWidth = 2.dp,
                )

                rows.isEmpty() -> Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // 空态要分清四件事：真没有消息 / 还在读 / 拉取失败 / 服务端说拿不到。
                    // 以前不管哪种都写「这个会话还没有消息」—— 断网时用户只会以为会话被清空了。
                    val failure = viewModel.error ?: viewModel.unavailable
                    Text(
                        text = when {
                            failure != null -> failure
                            viewModel.loading -> "正在读取消息…"
                            else -> "这个会话还没有消息"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (failure != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        textAlign = TextAlign.Center,
                    )
                    if (failure == null) {
                        Text(
                            text = "在下面输入框里发第一条",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (viewModel.hasOlder) {
                        item(key = "load-older") {
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                if (viewModel.loadingOlder) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                } else {
                                    TextButton(onClick = { viewModel.loadOlder() }) { Text("加载更早的消息") }
                                }
                            }
                        }
                    }
                    items(rows, key = { it.key }) { row ->
                        when (row) {
                            is ChatRow.Single -> when (val item = row.item) {
                                is TimelineItem.UserMessage -> UserBubble(
                                    item = item,
                                    onLongPress = { actionTarget = item },
                                    flashTick = if (item.key == flashKey) flashTick else 0,
                                )

                                is TimelineItem.AssistantMessage -> AssistantBlock(
                                    item = item,
                                    onLongPress = { actionTarget = item },
                                    onOpenDetail = { viewModel.closeTranslation(); detail = it },
                                )
                            }

                            is ChatRow.Process -> ProcessGroupBlock(
                                row = row,
                                // 「流式对话时展开中间过程」关掉时全程保持折叠（手动展开的不受影响）
                                autoOpen = expandInterim && row.streaming && row.key == lastKey,
                                onOpenDetail = { detail = it },
                            )
                        }
                    }
                }
            }

            if (!atBottom && rows.isNotEmpty()) {
                SmallFloatingActionButton(
                    onClick = {
                        atBottom = true
                        scope.launch { stickToBottom() }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "回到底部")
                }
            }

        }
    }
    }

    WorkspacePanel(
        visible = showWorkspace,
        onDismiss = { showWorkspace = false },
        viewModel = viewModel,
        // 文件树里的 @ 键：把 `@相对路径` 拼到输入框，跟桌面端同一个用法
        onInsertMention = { path ->
            val needsSpace = draft.isNotEmpty() && !draft.endsWith(" ")
            draft = draft + (if (needsSpace) " " else "") + "@" + path + " "
            ChatDrafts.put(viewModel.sessionId, draft)
            showWorkspace = false
            atBottom = true
        },
    )
    }

    if (showPrompts) {
        ComposerPanelSheet(
            tab = panelTab,
            onTabChange = { panelTab = it },
            prompts = viewModel.prompts,
            categories = viewModel.promptCategories,
            category = viewModel.promptCategory,
            query = viewModel.promptQuery,
            loading = viewModel.promptsBusy,
            error = viewModel.promptsError,
            hasMore = viewModel.promptsHasMorePages,
            preview = viewModel.promptPreview,
            previewBody = viewModel.promptPreviewBody,
            previewBusy = viewModel.promptPreviewBusy,
            onQueryChange = viewModel::searchPrompts,
            onCategoryChange = viewModel::pickPromptCategory,
            onLoadMore = viewModel::loadMorePrompts,
            onPreview = { prompt -> viewModel.previewPrompt(prompt) },
            onBackFromPreview = { viewModel.closePromptPreview() },
            onInsertPreview = { body ->
                insertIntoDraft(body)
                showPrompts = false
            },
            localPrompts = viewModel.localPrompts,
            localEditor = viewModel.localPromptEditor,
            localTitle = viewModel.localPromptTitle,
            localBody = viewModel.localPromptBody,
            onLocalTitleChange = { viewModel.localPromptTitle = it },
            onLocalBodyChange = { viewModel.localPromptBody = it },
            onOpenLocalEditor = { viewModel.openLocalPromptEditor() },
            onCloseLocalEditor = { viewModel.closeLocalPromptEditor() },
            onSaveLocal = { viewModel.saveLocalPrompt() },
            onRemoveLocal = { viewModel.removeLocalPrompt(it) },
            onPreviewLocal = { viewModel.previewLocalPrompt(it) },
            quickMessages = viewModel.quickMessages,
            quickDraft = viewModel.quickDraft,
            onQuickDraftChange = viewModel::updateQuickDraft,
            onAddQuick = { viewModel.addQuickMessage() },
            onRemoveQuick = { viewModel.removeQuickMessage(it) },
            onInsertQuick = { text ->
                insertIntoDraft(text)
                showPrompts = false
            },
            onSendQuick = { text ->
                showPrompts = false
                sendText(text)
            },
            onDismiss = {
                showPrompts = false
                viewModel.closePromptPreview()
            },
        )
    }

    if (showAttachSheet) {
        ModalBottomSheet(onDismissRequest = { showAttachSheet = false }) {
            SheetAction(
                label = "从相册选择",
                detail = "最多 ${ImagePrep.MAX_COUNT} 张",
                onClick = {
                    showAttachSheet = false
                    imagePicker.launch("image/*")
                },
            )
            SheetAction(
                label = "粘贴图片",
                detail = "剪贴板里的截图",
                onClick = {
                    showAttachSheet = false
                    viewModel.pasteImage()
                },
            )
            Spacer(Modifier.height(12.dp))
        }
    }

    actionTarget?.let { target ->
        MessageActionsSheet(
            item = target,
            onDismiss = { actionTarget = null },
            onFreeCopy = {
                actionTarget = null
                freeCopyTarget = target
            },
            onCopy = { text ->
                clipboard.setText(AnnotatedString(text))
                actionTarget = null
                scope.launch { snackbar.showSnackbar("已复制 ${text.length} 字") }
            },
        )
    }


    if (showModelSheet) {
        ModelSheet(
            models = viewModel.availableModels,
            current = viewModel.displayModel,
            busy = viewModel.modelsBusy,
            onRefresh = { viewModel.loadModels(force = true) },
            onPick = { model ->
                showModelSheet = false
                viewModel.applyModel(
                    SessionModel(provider = model.provider, modelId = model.id, modelName = model.name),
                )
            },
            onDismiss = { showModelSheet = false },
        )
    }
    if (showThinkingSheet) {
        ThinkingSheet(
            levels = viewModel.thinkingLevels,
            current = viewModel.displayThinking,
            modelLabel = viewModel.displayModel?.shortLabel,
            onPick = { level ->
                showThinkingSheet = false
                viewModel.applyThinking(level)
            },
            onDismiss = { showThinkingSheet = false },
        )
    }
    // 改偏好失败（主机拒了 / 离线）用 snackbar 说一声，不占版面
    LaunchedEffect(viewModel.prefError) {
        val message = viewModel.prefError ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        viewModel.dismissPrefError()
    }
    // 历史跳转：滚到那一句并让它停稳。
    // 直接 scrollToItem 有两个坑 —— ① 顶上挂着「加载更早的消息」时行下标要 +1；
    // ② 行高要等布局量完才知道，量之前滚到的是估算位置。所以滚完校验一次，
    //    目标没贴到顶部就再来一遍（最多 3 次，够覆盖慢机型上的长列表）。
    LaunchedEffect(jumpKey, jumpTick) {
        val key = jumpKey ?: return@LaunchedEffect
        if (key.isEmpty()) return@LaunchedEffect
        repeat(3) {
            val leading = if (viewModel.hasOlder) 1 else 0
            val index = historyListIndex(rows, key, leading)
            if (index < 0) return@LaunchedEffect
            listState.scrollToItem(index)
            withFrameNanos { }
            val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
            val top = listState.layoutInfo.viewportStartOffset
            if (info != null && info.offset - top <= 1) return@LaunchedEffect
        }
    }
    // 高亮闪完就清掉，别让气泡一直亮着
    LaunchedEffect(flashKey, flashTick) {
        if (flashKey == null) return@LaunchedEffect
        kotlinx.coroutines.delay(1_800)
        flashKey = null
    }

    freeCopyTarget?.let { target ->
        FreeCopyDialog(
            text = when (target) {
                is TimelineItem.UserMessage -> target.text
                is TimelineItem.AssistantMessage -> target.text
            },
            onCopyAll = { text ->
                clipboard.setText(AnnotatedString(text))
                freeCopyTarget = null
                scope.launch { snackbar.showSnackbar("已复制 ${text.length} 字") }
            },
            onDismiss = { freeCopyTarget = null },
        )
    }
    detail?.let { content ->
        DetailDialog(
            content = content,
            onCopyAll = { text ->
                clipboard.setText(AnnotatedString(text))
                detail = null
                viewModel.closeTranslation()
                scope.launch { snackbar.showSnackbar("已复制 ${text.length} 字") }
            },
            onDismiss = {
                detail = null
                viewModel.closeTranslation()
            },
            translation = if (content.translatable) viewModel.translation else null,
            onTranslate = if (content.translatable) {
                {
                    val open = viewModel.translation
                    viewModel.translate(
                        text = content.text,
                        target = open?.target ?: defaultTranslateTarget(content.text),
                    )
                }
            } else {
                null
            },
            onFlipTranslation = if (content.translatable) {
                { viewModel.flipTranslation() }
            } else {
                null
            },
            onCopyTranslation = { text ->
                clipboard.setText(AnnotatedString(text))
                scope.launch { snackbar.showSnackbar("已复制译文 ${text.length} 字") }
            },
        )
    }
    if (showHistory) {
        val turns = remember(viewModel.items) { buildHistoryTurns(viewModel.items, ::clock) }
        HistorySheet(
            turns = turns,
            onDismiss = { showHistory = false },
            onPick = { turn ->
                showHistory = false
                atBottom = false
                jumpKey = turn.key
                jumpTick += 1
                flashKey = turn.key
                flashTick += 1
            },
        )
    }
    if (showSecurity) {
        SecurityLevelSheet(
            security = viewModel.security,
            sessionId = viewModel.sessionId,
            busy = viewModel.securityBusy,
            onPick = { levelId ->
                viewModel.chooseSecurityLevel(levelId)
                showSecurity = false
            },
            onRefresh = { viewModel.refreshSecurity() },
            onDismiss = { showSecurity = false },
        )
    }
    if (showPermission) {
        PermissionSheet(
            current = viewModel.permissionPreset,
            busy = viewModel.presetBusy,
            onPick = { preset ->
                viewModel.choosePermission(preset)
                showPermission = false
            },
            onDismiss = { showPermission = false },
        )
    }
    if (showContext) {
        ContextSheet(
            usage = viewModel.contextUsage,
            busy = viewModel.contextBusy,
            compactBusy = viewModel.compactBusy,
            cache = viewModel.cacheStat,
            cacheBusy = viewModel.cacheBusy,
            control = viewModel.securityControl,
            securityLevelLabel = viewModel.securityLevelLabel,
            permissionLabel = dev.pideck.nativeapp.data.PermissionPresets.labelOf(viewModel.permissionPreset),
            onRefresh = {
                viewModel.refreshContext()
                viewModel.refreshCacheStat()
            },
            onCompact = { prompt -> viewModel.compact(prompt) },
            onDismiss = { showContext = false },
        )
    }
    LaunchedEffect(viewModel.actionNote) {
        val note = viewModel.actionNote ?: return@LaunchedEffect
        snackbar.showSnackbar(note)
        viewModel.dismissActionNote()
    }
    FilePreviewDialog(viewModel = viewModel)
    DiffDialog(viewModel = viewModel)
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun UserBubble(
    item: TimelineItem.UserMessage,
    onLongPress: () -> Unit = {},
    flashTick: Int = 0,
) {
    // 高亮闪烁：从历史面板跳过来时闪三下，告诉人「落在这儿了」。
    // 只动颜色与描边（不碰尺寸），所以不会让列表跳高度、也不会把追滚带跑偏。
    val flash = remember { Animatable(0f) }
    LaunchedEffect(flashTick) {
        if (flashTick == 0) return@LaunchedEffect
        flash.snapTo(0f)
        repeat(3) {
            flash.animateTo(1f, tween(durationMillis = 170))
            flash.animateTo(0f, tween(durationMillis = 230))
        }
    }
    val container = MaterialTheme.colorScheme.primaryContainer
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
                onLongClick = onLongPress,
            ),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            color = androidx.compose.ui.graphics.lerp(container, accent, flash.value * 0.55f),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp),
            border = if (flash.value > 0.01f) {
                BorderStroke(1.5.dp, accent.copy(alpha = flash.value))
            } else {
                null
            },
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                // 图片在文字上面：手机发的图通常是「图 + 一句话」的，先看图更顺
                if (item.images.isNotEmpty()) {
                    MessageImages(
                        images = item.images,
                        keyPrefix = item.key,
                        modifier = Modifier.padding(bottom = if (item.text.isBlank()) 0.dp else 8.dp),
                    )
                }
                if (item.text.isNotBlank()) {
                    Text(
                        text = item.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Text(
                    text = clock(item.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun AssistantBlock(
    item: TimelineItem.AssistantMessage,
    onLongPress: () -> Unit = {},
    onOpenDetail: (DetailContent) -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
                onLongClick = onLongPress,
            ),
    ) {
        item.thinking?.let { thinking ->
            ThinkingCard(
                text = thinking,
                streaming = item.streaming,
                onOpen = {
                    onOpenDetail(
                        DetailContent(
                            title = if (item.streaming) "思考过程（进行中）" else "思考过程",
                            hint = "太长不想滚？长按选中一段就行；也可以直接复制全文，或让宿主翻一遍",
                            text = thinking,
                            translatable = true,
                        ),
                    )
                },
            )
            Spacer(Modifier.size(8.dp))
        }

        item.tools.forEach { tool ->
            ToolCard(tool, onOpen = { onOpenDetail(toolDetailOf(tool)) })
            Spacer(Modifier.size(6.dp))
        }

        if (item.text.isNotBlank()) {
            // 几万字的一条回复一次性铺开会同时建出上百个 Text，滚动直接掉帧；
            // 收尾后先展示前 6000 字，要看全文点一下再展开。
            val long = !item.streaming && item.text.length > LONG_TEXT_LIMIT
            var expanded by remember(item.key) { mutableStateOf(false) }
            val shown = if (long && !expanded) item.text.take(LONG_TEXT_LIMIT) else item.text
            MarkdownText(markdown = shown, streaming = item.streaming)
            if (long && !expanded) {
                TextButton(onClick = { expanded = true }) {
                    Text("展开全文（共 ${item.text.length} 字）")
                }
            }
        }

        item.errorText?.let { message ->
            Spacer(Modifier.size(6.dp))
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }

        if (!item.streaming && item.timestamp > 0) {
            Text(
                text = clock(item.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * 过程组：连续的过程条目（思考 + 工具调用）折成一行摘要，点开才铺开细节。
 *
 * 桌面端就是这么渲染的：跑起来之后屏幕上只有「读取 · 搜索 · 2 次调用」这样一行，
 * 正文与细节要主动展开 —— 长回合里这能把几十屏的过程压成几行。
 */
/** 过程分组里的一段思考（0.5.43 起统一渲染在工具卡下方）。 */
private data class ThinkingPiece(val key: String, val text: String, val streaming: Boolean)

/**
 * 组内一条成员。**按真实时序排**：每条 assistant 消息里先有思考、再有它触发的工具调用
 * （对齐桌面端 `buildTurnDisplay` —— 思考组与工具组都是按 run.items 的顺序入列表的）。
 *
 * 0.5.43 曾把思考统一挪到组体底部，0.5.45 按用户截图改回来：39 段思考全堆在 83 张工具卡下面，
 * 看不出「这一条命令是在想完之后跑的」。
 */
private sealed interface ProcessMember {
    data class Tool(val tool: ToolView) : ProcessMember

    data class Thinking(val piece: ThinkingPiece, val index: Int, val total: Int) : ProcessMember
}

@Composable
private fun ProcessGroupBlock(
    row: ChatRow.Process,
    autoOpen: Boolean,
    onOpenDetail: (DetailContent) -> Unit = {},
) {
    // 手动开关优先：点过就以用户为准（桌面端 `manualGroupIds` 同一语义）。
    // 以前直接用 `LaunchedEffect(autoOpen)` 覆盖 open，跑完收尾那一刻会把用户刚展开的组收回去。
    // （用「点过没有 + 点成了什么」两个布尔，别用可空 Boolean —— rememberSaveable 对 null 不友好）
    var manualSet by rememberSaveable(row.key) { mutableStateOf(false) }
    var manualOpen by rememberSaveable(row.key) { mutableStateOf(autoOpen) }
    val open = if (manualSet) manualOpen else autoOpen

    val tools = row.steps.flatMap { it.tools }
    val thinkingTotal = row.steps.count { it.thinking?.isNotBlank() == true }
    val members: List<ProcessMember> = buildList {
        var thinkingIndex = 0
        for (step in row.steps) {
            val text = step.thinking?.takeIf { it.isNotBlank() }
            if (text != null) {
                thinkingIndex += 1
                add(
                    ProcessMember.Thinking(
                        piece = ThinkingPiece(step.key, text, step.streaming),
                        index = thinkingIndex,
                        total = thinkingTotal,
                    ),
                )
            }
            step.tools.forEach { add(ProcessMember.Tool(it)) }
        }
    }
    // 挂载预算：桌面端组内 >32 条只挂后 32，超出给「显示更早的 N 条步骤」
    var batches by rememberSaveable(row.key) { mutableIntStateOf(0) }
    val shown = visibleMemberCount(members.size, batches)
    val hidden = members.size - shown
    val visibleMembers = members.takeLast(shown)

    Column(modifier = Modifier.fillMaxWidth()) {
        // 运行中给最后一条工具的参数摘要（桌面端 `lastToolDetail` 同款）：
        // 光一句「正在执行命令」在长回合里等于没信息。
        val runningDetail = if (row.streaming) tools.lastOrNull()?.let { ProcessActivity.toolDetail(it) } else null

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable {
                    manualSet = true
                    manualOpen = !open
                }
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (open) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.size(4.dp))
            Text(
                text = row.summary,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // 摘要里已经有各类次数，不再另起一段「· 83 次调用」（那截会掉到第二行）
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = runningDetail != null),
            )
            if (runningDetail != null) {
                Text(
                    text = " · ",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Text(
                    text = runningDetail,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                spanText(row.durationMs)?.let { span ->
                    Text(
                        text = " · $span",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            if (row.streaming) {
                Spacer(Modifier.size(8.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        AnimatedVisibility(visible = open, enter = fadeIn(tween(140)), exit = fadeOut(tween(90))) {
            val inner = rememberScrollState()
            val lineColor = MaterialTheme.colorScheme.outlineVariant
            // 组里也是流式追加：不钉住的话新工具卡长在可视区外，看着像「没跟上」
            LaunchedEffect(members.size, row.streaming) {
                if (row.streaming) {
                    withFrameNanos { }
                    inner.scrollTo(inner.maxValue)
                }
            }
            // 跑完再打开要从头看：流式时被钉在底部，收尾后位置会停在最后一条，
            // 展开第一眼看到的是「结尾的思考」，容易误以为顺序不对。
            LaunchedEffect(open, row.streaming) {
                if (open && !row.streaming) {
                    withFrameNanos { }
                    inner.scrollTo(0)
                }
            }
            // 组体样式对齐桌面端：缩进 + 左侧 2dp 竖线，不再是一张圆角卡片
            // （桌面 `.ml-5 border-l-2 border-border-subtle pl-3`）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, top = 2.dp)
                    .drawBehind {
                        drawRect(
                            color = lineColor,
                            topLeft = Offset.Zero,
                            size = Size(2.dp.toPx(), size.height),
                        )
                    }
                    .padding(start = 12.dp)
                    .heightIn(max = 320.dp)
                    .verticalScroll(inner)
                    .padding(vertical = 2.dp),
            ) {
                if (hidden > 0) {
                    TextButton(
                        onClick = { batches += 1 },
                        modifier = Modifier.padding(bottom = 2.dp),
                    ) {
                        Text(
                            text = "显示更早的 ${minOf(hidden, 32)} 条步骤",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                visibleMembers.forEachIndexed { index, member ->
                    when (member) {
                        is ProcessMember.Tool -> ToolCard(
                            tool = member.tool,
                            onOpen = { onOpenDetail(toolDetailOf(member.tool)) },
                        )

                        is ProcessMember.Thinking -> ThinkingCard(
                            text = member.piece.text,
                            streaming = member.piece.streaming,
                            title = if (member.total > 1) "思考 ${member.index}/${member.total}" else "思考",
                            onOpen = {
                                onOpenDetail(
                                    DetailContent(
                                        title = if (member.total > 1) {
                                            "思考 ${member.index}/${member.total}"
                                        } else {
                                            "思考过程"
                                        },
                                        hint = "太长不想滚？长按选中一段就行；也可以直接复制全文，或让宿主翻一遍",
                                        text = member.piece.text,
                                        translatable = true,
                                    ),
                                )
                            },
                        )
                    }
                    if (index != visibleMembers.lastIndex) Spacer(Modifier.size(6.dp))
                }
            }
        }
    }
}

/** 「850ms」/「1.2s」/「1m02s」；0 或负数返回 null（不显示）。 */
private fun spanText(ms: Long): String? {
    if (ms <= 0) return null
    if (ms < 1000) return "${ms}ms"
    val totalSeconds = ms / 1000
    if (totalSeconds < 60) {
        val tenth = ((ms % 1000) / 100).toInt()
        return "$totalSeconds.${tenth}s"
    }
    return "${totalSeconds / 60}m${(totalSeconds % 60).toString().padStart(2, '0')}s"
}

/**
 * 思考卡：**只占一行**，点开是「自由复制」同款浮窗。
 *
 * 以前是就地展开（卡片自己撑高），长思考一展开就把整轮回复推走、还很难挑出其中一段复制；
 * 换成浮窗后与原位滚动互不干扰，浮窗里能直接拖选。
 */
@Composable
private fun ThinkingCard(
    text: String,
    streaming: Boolean,
    onOpen: () -> Unit,
    title: String = "思考过程",
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .softPress(RoundedCornerShape(10.dp), onOpen),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (streaming) "$title（进行中）" else title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${text.length} 字",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Icon(
                imageVector = Icons.Default.OpenInFull,
                contentDescription = "展开",
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

/**
 * 工具卡：一行摘要，点开是「自由复制」同款浮窗（入参 / 结果 / 错误都在里面，等宽显示）。
 *
 * 同样是「不再就地展开」：命令输出动辄几百行，塞在气泡里既挤又没法滚动，
 * 浮窗里能滚、能选、能一次复制全文。
 */
@Composable
private fun ToolCard(tool: ToolView, onOpen: () -> Unit = {}) {
    val tint = when {
        tool.isError -> MaterialTheme.colorScheme.error
        tool.isSettled -> Color(0xFF4CAF50)
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .softPress(RoundedCornerShape(10.dp), onOpen),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            run {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(tint, CircleShape),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = tool.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = toolSummary(tool),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Default.OpenInFull,
                    contentDescription = "展开",
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

/** 工具卡点开后浮窗里的正文：入参 / 错误 / 结果，等宽显示。 */
private fun toolDetailOf(tool: ToolView): DetailContent {
    val body = buildString {
        tool.input?.let { append("入参\n").append(it).append("\n\n") }
        tool.errorText?.let { append("错误\n").append(it) }
        if (tool.errorText == null) {
            tool.output?.let { append("结果\n").append(it) }
        }
    }.trim()
    val hint = when {
        tool.isError -> "这条调用出错了：说明在「错误」那一段"
        tool.state.contains("running") -> "还在跑，结果会随流式更新"
        else -> tool.name + " · " + toolSummary(tool)
    }
    return DetailContent(
        title = tool.name,
        hint = hint.take(90),
        text = body.ifBlank { "（这条调用没有留下入参和结果）" },
        mono = true,
    )
}

private fun toolSummary(tool: ToolView): String {
    tool.errorText?.let { return it.lineSequence().firstOrNull().orEmpty().take(80) }
    val source = tool.input ?: tool.output ?: return if (tool.isSettled) "完成" else "运行中…"
    return source.lineSequence().firstOrNull().orEmpty().take(80).ifBlank { "…" }
}

@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    sending: Boolean,
    busyMode: BusySendMode = BusySendMode.STEER,
    busyModes: List<BusySendMode> = BusySendMode.entries.toList(),
    onBusyModeChange: (BusySendMode) -> Unit = {},
    images: List<PendingImage>,
    imageError: String?,
    onAttach: () -> Unit,
    onRemoveImage: (String) -> Unit,
    onDismissImageError: () -> Unit,
    onSend: (BusySendMode) -> Unit,
    onStop: () -> Unit,
    mentionFiles: List<FileNode> = emptyList(),
    mentionIndexing: Boolean = false,
    hostCommands: List<HostCommand> = emptyList(),
    onNeedMention: () -> Unit = {},
    onNeedCommands: () -> Unit = {},
    onCommand: (CommandEntry) -> Unit = {},
    onOpenPrompts: () -> Unit = {},
    promptError: String? = null,
    onDismissPromptError: () -> Unit = {},
) {
    // 输入区不需要一个「底板」：之前是 Surface + tonalElevation，会在底部铺出一条
    // 顶边笔直的浅色方块，跟圆角输入卡片放一起很割裂。改成向上渐隐的遮罩，
    // 消息滚到底下时被柔和地盖住，但看不出任何边界。
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0.0f to Color.Transparent,
                    0.45f to MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
                    1.0f to MaterialTheme.colorScheme.surface,
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (images.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(images, key = { it.id }) { image ->
                        AttachmentThumb(image = image, onRemove = { onRemoveImage(image.id) })
                    }
                }
            }
            if (promptError != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        promptError,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismissPromptError) { Text("知道了") }
                }
            }
            if (imageError != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        imageError,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismissImageError) { Text("知道了") }
                }
            }
            // `@文件` / `/命令` 的候选：只在输入框末尾正打着半截引用时出现
            val trigger = remember(value) { detectTrigger(value) }
            LaunchedEffect(trigger) {
                when (trigger) {
                    is ComposerTrigger.Mention -> onNeedMention()
                    is ComposerTrigger.Slash -> onNeedCommands()
                    null -> Unit
                }
            }
            when (trigger) {
                is ComposerTrigger.Mention -> ComposerSuggest(
                    files = filterMentionFiles(mentionFiles, trigger.query),
                    commands = emptyList(),
                    loading = mentionIndexing,
                    onPickFile = { node ->
                        onValueChange(applyMention(value, trigger, node))
                    },
                    onPickCommand = {},
                    modifier = Modifier.padding(bottom = 6.dp),
                )

                is ComposerTrigger.Slash -> ComposerSuggest(
                    files = emptyList(),
                    commands = commandEntries(hostCommands, trigger.query),
                    onPickFile = {},
                    onPickCommand = { entry -> onCommand(entry) },
                    modifier = Modifier.padding(bottom = 6.dp),
                )

                null -> Unit
            }
            // 整条输入区就是一张卡片：正文在上，动作在下。
            // 以前是「圆形按钮 + 输入框 + 圆形按钮」并排，两个浮在框外的圆点看着很别扭。
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 6.dp),
                ) {
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 26.dp, max = 160.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        maxLines = 6,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                        decorationBox = { inner ->
                            Box {
                                if (value.isEmpty()) {
                                    Text(
                                        text = "发消息给这个会话…",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                inner()
                            }
                        },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIconButton(
                            icon = Icons.Default.AddPhotoAlternate,
                            contentDescription = "添加图片",
                            onClick = onAttach,
                            size = 32,
                            container = Color.Transparent,
                        )
                        // 提示词库：桌面端的「提示词」按钮，点开是搜索 + 分类 + 插入。
                        // 放在图片按钮右边，跟发送区隔开 —— 它是「往输入框塞东西」，不是发送动作。
                        AppIconButton(
                            icon = Icons.Default.MenuBook,
                            contentDescription = "提示词库",
                            onClick = onOpenPrompts,
                            size = 32,
                            container = Color.Transparent,
                        )
                        Spacer(Modifier.weight(1f))
                        // 正在跑、而且框里有内容：先给一个「怎么发」的选择器，
                        // 停止按钮和发送按钮并存 —— 停止不会清掉输入框。
                        if (sending && value.isNotBlank()) {
                            BusySendPicker(mode = busyMode, modes = busyModes, onChange = onBusyModeChange)
                            Spacer(Modifier.width(8.dp))
                        }
                        if (sending) {
                            FilledIconButton(
                                onClick = onStop,
                                shape = ControlShape,
                                modifier = Modifier.size(36.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(11.dp)
                                        .background(
                                            MaterialTheme.colorScheme.onPrimary,
                                            RoundedCornerShape(2.dp),
                                        ),
                                )
                            }
                            if (value.isNotBlank()) Spacer(Modifier.width(8.dp))
                        }
                        if (!sending || value.isNotBlank()) {
                            FilledIconButton(
                                onClick = { onSend(busyMode) },
                                enabled = value.isNotBlank() || images.isNotEmpty(),
                                shape = ControlShape,
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(Icons.Default.Send, contentDescription = "发送")
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 「这条消息怎么发」：加入当前回合 / 立即发送 / 排队到下一轮 / 并行发送。
 *
 * 只在会话还在跑、且输入框里有内容时出现 —— 空闲时发送没有歧义，多一个选择器只是噪音。
 * 可选项由后端决定（ACP 没有 steer 通道，那一格换成「立即发送」）。
 */
@Composable
private fun BusySendPicker(
    mode: BusySendMode,
    modes: List<BusySendMode>,
    onChange: (BusySendMode) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Surface(
            shape = ControlShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.clickable { open = true },
        ) {
            Row(
                modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = mode.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "选择发送方式",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            modes.forEach { entry ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(entry.label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = entry.hint,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        open = false
                        onChange(entry)
                    },
                )
            }
        }
    }
}

/**
 * 一轮回复的统计行（桌面端 `ComposerStatsLine` 的移动版）：首字等待 · 两档 tok/s · 缓存命中。
 *
 * 数来自宿主（`runtime/state` 的 perf + 命中率，与桌面端同源），本端秒表只在拿不到时兜底。
 * 常驻一行：没有数就显示破折号，别让它忽隐忽现把输入条顶来顶去。
 */
@Composable
private fun TurnStatsLine(stats: TurnStats?, streaming: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    val strokeWidth = 1.dp
    val cornerRadius = 16.dp
    // 「运行中」= 边框上跑一颗彗星（头亮、尾巴渐隐），代替原先浮在列表底部的「生成中」胶囊
    // 和第一版的「整圈旋转渐变」—— 后者在这么扁的一个圆角矩形上，光带扫过上下两条长边时
    // 一明一暗、四个角还各亮一下，看着像闪烁而不是在跑。
    // 动画值只在绘制阶段读：文字不重组、尺寸不变，长回答的追滚不会被带跑偏。
    val phase = if (streaming) {
        val transition = rememberInfiniteTransition(label = "turnStatsBorder")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1600, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "turnStatsBorderPhase",
        )
    } else {
        null
    }
    // 宽度和输入卡片对齐：输入区自己是 `padding(horizontal = 12.dp)` + `RoundedCornerShape(16.dp)`，
    // 这里必须用同一组数，否则一行字悬在中间、左右各差 4dp，看着像没对齐。
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .drawBehind {
                    val stroke = strokeWidth.toPx()
                    val inset = stroke / 2f
                    val topLeft = Offset(inset, inset)
                    val arcSize = Size(
                        width = (size.width - stroke).coerceAtLeast(0f),
                        height = (size.height - stroke).coerceAtLeast(0f),
                    )
                    val radius = CornerRadius(cornerRadius.toPx())
                    drawRoundRect(
                        color = scheme.outlineVariant.copy(alpha = 0.7f),
                        topLeft = topLeft,
                        size = arcSize,
                        cornerRadius = radius,
                        style = Stroke(width = stroke),
                    )
                    val spin = phase
                    if (spin != null && arcSize.width > 1f && arcSize.height > 1f) {
                        // 沿边框路径量长度，再按百分比截一段出来 —— 光带才会均匀地贴着圆角走
                        val outline = Path().apply {
                            addRoundRect(
                                RoundRect(
                                    rect = Rect(topLeft, arcSize),
                                    cornerRadius = radius,
                                ),
                            )
                        }
                        val measure = PathMeasure().apply { setPath(outline, false) }
                        // 顺时针跑：路径本身是逆时针方向的，所以让头沿路径**倒退**，
                        // 尾巴则朝路径正方向拖（`tailForwards = true`），视觉上就是顺时针。
                        drawComet(
                            measure = measure,
                            headFraction = 1f - spin.value,
                            tailFraction = 0.24f,
                            color = scheme.primary,
                            width = (strokeWidth * 2f).toPx(),
                            steps = 16,
                            tailForwards = true,
                        )
                    }
                },
        ) {
            Text(
                text = formatTurnStats(stats),
                style = MaterialTheme.typography.labelSmall,
                color = if (streaming) scheme.onSurface else scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 沿一条已测量的路径画一颗「彗星」：`headFraction` 处最亮，往后 `tailFraction` 长逐段淡出。
 *
 * 用 PathMeasure 截路径而不是画渐变，是因为这一行又扁又宽：渐变扫过上下两条长边时会
 * 同时亮两段，短边又几乎不亮，看着像闪烁。截路径的话光带是**匀速**沿周长走的。
 *
 * 注意每段的取法：路径方向是**从尾到头**，即 `start = head - tail*(i+1)/n` 要小于
 * `end = head - tail*i/n`。反着取（start 在 end 前面）会被下面的越界处理当成「绕一圈」，
 * 16 段各自盖住几乎整条边框，画出来就是一整圈实心色 —— 0.5.40 第一版就是这样，跑起来
 * 只看到边框变蓝、没有任何移动。
 */
private fun DrawScope.drawComet(
    measure: PathMeasure,
    headFraction: Float,
    tailFraction: Float,
    color: Color,
    width: Float,
    steps: Int = 14,
    /**
     * 尾巴朝路径**正方向**拖（配合 `headFraction = 1 - phase` 让彗星顺时针跑）。
     * 默认 false = 尾巴朝负方向拖，头沿正方向前进（逆时针）。
     */
    tailForwards: Boolean = false,
) {
    val length = measure.length
    if (length <= 0f) return
    val head = (((headFraction % 1f) + 1f) % 1f) * length
    val tail = (length * tailFraction).coerceAtLeast(1f)
    val segment = Path()
    // 第一遍画光晕（更粗、更淡），第二遍画本体的芯
    val passes = floatArrayOf(3.4f, 1f)
    val alphas = floatArrayOf(0.26f, 1f)
    for (pass in passes.indices) {
        val passWidth = width * passes[pass]
        val passAlpha = alphas[pass]
        for (index in 0 until steps) {
            val from = if (tailForwards) {
                head + tail * index / steps + length
            } else {
                head - tail * (index + 1) / steps + length
            }
            val to = if (tailForwards) {
                head + tail * (index + 1) / steps + length
            } else {
                head - tail * index / steps + length
            }
            // 尾 0f → 头 1f，线性淡出：平方会让后半截直接看不见，整条光带只剩一个点
            val fade = 1f - (index + 0.5f) / steps
            segment.reset()
            appendPathSegment(measure, from, to, segment, length)
            if (!segment.isEmpty) {
                drawPath(
                    path = segment,
                    color = color.copy(alpha = fade * passAlpha),
                    style = Stroke(width = passWidth, cap = StrokeCap.Round),
                )
            }
        }
    }
    // 头部再点一颗实心光点：只有渐隐的尾巴时看不出「在跑」，有个亮头才有方向感
    val headPoint = measure.getPosition(head)
    drawCircle(color = color, radius = width * 1.15f, center = headPoint)
}

/** 取 `[from, to]` 这一段路径；越过起点（0 或整周长）时拆成两段，别把整条路径都取出来。 */
private fun appendPathSegment(
    measure: PathMeasure,
    from: Float,
    to: Float,
    dest: Path,
    length: Float,
) {
    val a = ((from % length) + length) % length
    val b = ((to % length) + length) % length
    if (a <= b) {
        measure.getSegment(a, b, dest, true)
    } else {
        measure.getSegment(a, length, dest, true)
        measure.getSegment(0f, b, dest, false)
    }
}

/** 排队到下一轮的那几条：宿主不回传队列内容，这里只报数 + 头几个字。 */
@Composable
private fun QueuedBanner(queued: List<String>) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Schedule,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "已排队 " + queued.size + " 条，本轮结束后自动发送：" +
                    queued.joinToString("；") { it.take(24) },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 并行问询面板：另开的匿名会话在本轮之外作答，答案不打断当前回合。
 *
 * 收尾后可以「插入输入框」，把它当成下一条消息的素材（桌面端也是这么带回主线的）。
 */
@Composable
private fun ParallelAskCard(ask: ParallelAsk, onInsert: () -> Unit, onClose: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (ask.busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Splitscreen,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "并行问询",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                AppIconButton(
                    icon = Icons.Default.Close,
                    contentDescription = "关闭并行问询",
                    onClick = onClose,
                    size = 28,
                    container = Color.Transparent,
                )
            }
            Text(
                text = ask.question,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (ask.answer.isNotBlank()) {
                Text(
                    text = ask.answer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .heightIn(max = 180.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
            ask.error?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (ask.answer.isNotBlank()) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onInsert) { Text("插入输入框") }
                }
            }
        }
    }
}

@Composable
private fun AttachmentThumb(image: PendingImage, onRemove: () -> Unit) {
    Box(modifier = Modifier.size(64.dp)) {
        val bitmap = image.bitmap
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = image.label,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
        Surface(
            onClick = onRemove,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shadowElevation = 2.dp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(20.dp),
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "移除图片",
                modifier = Modifier.padding(3.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private val clockFormat = SimpleDateFormat("HH:mm", Locale.US)

private const val LONG_TEXT_LIMIT = 6000

private fun clock(millis: Long): String =
    if (millis <= 0) "" else clockFormat.format(Date(millis))

/**
 * 长按消息弹出的操作表。
 *
 * 只做「拿走内容」这一类动作（复制全文 / 复制代码块 / 复制思考过程），
 * 不做编辑或删除 —— 消息的权威副本在桌面端，这边改不动也不该假装能改。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageActionsSheet(
    item: TimelineItem,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit,
    onFreeCopy: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val text = when (item) {
        is TimelineItem.UserMessage -> item.text
        is TimelineItem.AssistantMessage -> item.text
    }
    val codes = if (item is TimelineItem.AssistantMessage) {
        parseMarkdown(item.text).filterIsInstance<MdBlock.Code>()
    } else {
        emptyList()
    }
    val thinking = (item as? TimelineItem.AssistantMessage)?.thinking

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
            Text(
                "这条消息",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
            )
            SheetAction(
                label = "复制全文",
                detail = "${text.length} 字",
                enabled = text.isNotBlank(),
                onClick = { onCopy(text) },
            )
            SheetAction(
                label = "自由复制",
                detail = "选中一段再复制",
                enabled = text.isNotBlank(),
                onClick = onFreeCopy,
            )
            if (codes.isNotEmpty()) {
                SheetAction(
                    label = "复制代码块",
                    detail = "${codes.size} 段 · ${codes.sumOf { it.code.length }} 字",
                    onClick = { onCopy(codes.joinToString("\n\n") { it.code }) },
                )
            }
            if (!thinking.isNullOrBlank()) {
                SheetAction(
                    label = "复制思考过程",
                    detail = "${thinking.length} 字",
                    onClick = { onCopy(thinking) },
                )
            }
        }
    }
}

@Composable
private fun SheetAction(
    label: String,
    detail: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.ContentCopy,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.width(14.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.outline,
            modifier = Modifier.weight(1f),
        )
        Text(
            detail,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}
