package dev.pideck.nativeapp.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.filled.ContentCopy
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
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.pideck.nativeapp.data.TimelineItem
import dev.pideck.nativeapp.data.ToolView
import dev.pideck.nativeapp.data.SessionModel
import dev.pideck.nativeapp.data.thinkingLabel
import dev.pideck.nativeapp.data.toTimelineItem
import dev.pideck.nativeapp.ui.theme.AppIconButton
import dev.pideck.nativeapp.ui.theme.ControlShape
import dev.pideck.nativeapp.data.ImagePrep
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
    var draft by remember { mutableStateOf("") }

    // 拍平成「行」：连续的过程条目（思考 / 工具）合成一个过程组，正文回答单独成行。
    val rows = remember(viewModel.items, viewModel.live, viewModel.streaming) {
        buildChatRows(
            items = viewModel.items,
            live = viewModel.live?.toTimelineItem(streaming = viewModel.streaming),
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
    LaunchedEffect(Unit) { viewModel.loadModels() }

    var showWorkspace by remember { mutableStateOf(false) }
    // 顶栏的模型 / 思考档位选择器
    var showModelSheet by remember { mutableStateOf(false) }
    var showThinkingSheet by remember { mutableStateOf(false) }
    // 本会话的历史提问：点一条跳回那一段
    var showHistory by remember { mutableStateOf(false) }
    // 图片附件：系统相册多选；粘贴走剪贴板
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents(),
    ) { uris -> viewModel.addImages(uris) }
    var showAttachSheet by remember { mutableStateOf(false) }

    // 长按消息 → 复制全文 / 复制代码块 / 自由复制
    var actionTarget by remember { mutableStateOf<TimelineItem?>(null) }
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
                        modifier = Modifier.padding(end = 6.dp),
                    )
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
                SelectChip(
                    text = viewModel.sessionModel?.shortLabel ?: "选择模型",
                    onClick = { showModelSheet = true },
                    icon = Icons.Default.Memory,
                    trailing = Icons.Filled.KeyboardArrowDown,
                    accent = viewModel.sessionModel == null,
                    modifier = Modifier.widthIn(max = 188.dp),
                )
                val levels = viewModel.thinkingLevels
                if (levels.isNotEmpty()) {
                    val level = viewModel.thinkingLevel?.takeIf { it in levels } ?: levels.first()
                    SelectChip(
                        text = "思考 ${thinkingLabel(level)}",
                        onClick = { showThinkingSheet = true },
                        trailing = Icons.Filled.KeyboardArrowDown,
                        accent = level in setOf("high", "xhigh", "max"),
                    )
                }
                Spacer(Modifier.weight(1f))
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
                Composer(
                value = draft,
                onValueChange = { draft = it },
                sending = viewModel.streaming,
                images = viewModel.images,
                imageError = viewModel.imageError,
                onAttach = { showAttachSheet = true },
                onRemoveImage = { viewModel.removeImage(it) },
                onDismissImageError = { viewModel.dismissImageError() },
                onSend = {
                    val text = draft
                    draft = ""
                    atBottom = true
                    viewModel.send(text)
                },
                onStop = { viewModel.stop() },
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
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = viewModel.unavailable ?: "这个会话还没有消息",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "在下面输入框里发第一条",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
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
                                )

                                is TimelineItem.AssistantMessage -> AssistantBlock(
                                    item = item,
                                    onLongPress = { actionTarget = item },
                                )
                            }

                            is ChatRow.Process -> ProcessGroupBlock(
                                row = row,
                                autoOpen = row.streaming && row.key == lastKey,
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

            // 正在跑的时候，转圈贴在列表底部（顶栏留白给标题，别跟按钮抢位置）
            AnimatedVisibility(
                visible = viewModel.streaming,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 14.dp),
                enter = fadeIn(tween(180)),
                exit = fadeOut(tween(180)),
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shadowElevation = 2.dp,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "生成中",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
            showWorkspace = false
            atBottom = true
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
            current = viewModel.sessionModel,
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
            current = viewModel.thinkingLevel,
            modelLabel = viewModel.sessionModel?.shortLabel,
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
    if (showHistory) {
        val turns = remember(viewModel.items) { buildHistoryTurns(viewModel.items, ::clock) }
        HistorySheet(
            turns = turns,
            onDismiss = { showHistory = false },
            onPick = { turn ->
                showHistory = false
                val index = rows.indexOfFirst { it.key == turn.key }
                if (index >= 0) {
                    atBottom = false
                    scope.launch { listState.scrollToItem(index) }
                }
            },
        )
    }
    FilePreviewDialog(viewModel = viewModel)
    DiffDialog(viewModel = viewModel)
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun UserBubble(
    item: TimelineItem.UserMessage,
    onLongPress: () -> Unit = {},
) {
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
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    text = item.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
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
) {
    var thinkingOpen by remember(item.key, item.streaming) { mutableStateOf(item.streaming) }

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
                open = thinkingOpen,
                streaming = item.streaming,
                onToggle = { thinkingOpen = !thinkingOpen },
            )
            Spacer(Modifier.size(8.dp))
        }

        item.tools.forEach { tool ->
            ToolCard(tool)
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
@Composable
private fun ProcessGroupBlock(row: ChatRow.Process, autoOpen: Boolean) {
    var open by rememberSaveable(row.key) { mutableStateOf(autoOpen) }
    // 运行中自动展开（跟着流式看清楚在干什么），跑完自动收起
    LaunchedEffect(autoOpen) { if (open != autoOpen) open = autoOpen }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { open = !open }
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
            )
            if (row.toolCount > 0) {
                Text(
                    text = " · ${row.toolCount} 次调用",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            spanText(row.durationMs)?.let { span ->
                Text(
                    text = " · $span",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
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

        if (open) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(12.dp),
            ) {
                val inner = rememberScrollState()
                // 组里也是流式追加：不钉住的话新工具卡长在可视区外，看着像「没跟上」
                LaunchedEffect(row.steps.size, row.streaming) {
                    if (row.streaming) {
                        withFrameNanos { }
                        inner.scrollTo(inner.maxValue)
                    }
                }
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(inner)
                        .padding(10.dp),
                ) {
                    row.steps.forEach { step ->
                        step.thinking?.let { thinking ->
                            // 组里默认展开：并进组只是为了把「过程」收成一行，内容不该被藏起来
                            var thinkingOpen by remember(step.key) { mutableStateOf(true) }
                            ThinkingCard(
                                text = thinking,
                                open = thinkingOpen,
                                streaming = step.streaming,
                                onToggle = { thinkingOpen = !thinkingOpen },
                            )
                            Spacer(Modifier.size(6.dp))
                        }
                        step.tools.forEach { tool ->
                            ToolCard(tool)
                            Spacer(Modifier.size(6.dp))
                        }
                        step.errorText?.let { message ->
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
                    }
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

@Composable
private fun ThinkingCard(text: String, open: Boolean, streaming: Boolean, onToggle: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (streaming) "正在思考…" else "思考过程",
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
                    imageVector = if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (open) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp),
                )
            }
            if (open) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ToolCard(tool: ToolView) {
    var open by remember(tool.id) { mutableStateOf(false) }
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
            .clickable { open = !open },
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                    imageVector = if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp),
                )
            }
            if (open) {
                val detail = buildString {
                    tool.input?.let { append("入参\n").append(it).append("\n\n") }
                    tool.errorText?.let { append("错误\n").append(it) }
                    if (tool.errorText == null) tool.output?.let { append("结果\n").append(it) }
                }.trim()
                if (detail.isNotEmpty()) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 17.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .heightIn(max = 320.dp),
                    )
                }
            }
        }
    }
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
    images: List<PendingImage>,
    imageError: String?,
    onAttach: () -> Unit,
    onRemoveImage: (String) -> Unit,
    onDismissImageError: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
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
            // 整条输入区就是一张卡片：正文在上，动作在下。
            // 以前是「圆形按钮 + 输入框 + 圆形按钮」并排，两个浮在框外的圆点看着很别扭。
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
                        Spacer(Modifier.weight(1f))
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
                        } else {
                            FilledIconButton(
                                onClick = onSend,
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
