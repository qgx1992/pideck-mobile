package dev.pideck.nativeapp.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.ViewSidebar
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.pideck.nativeapp.data.TimelineItem
import dev.pideck.nativeapp.data.ToolView
import dev.pideck.nativeapp.data.toTimelineItem
import java.text.SimpleDateFormat
import kotlinx.coroutines.launch
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }

    val rows = remember(viewModel.items, viewModel.live) {
        buildList {
            addAll(viewModel.items)
            viewModel.live?.let { snapshot -> add(snapshot.toTimelineItem(streaming = viewModel.streaming)) }
        }
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
    val liveLength = viewModel.live?.text?.length ?: 0
    LaunchedEffect(lastKey, liveLength) {
        if (atBottom) stickToBottom()
    }
    // 键盘弹起 / 收起会压缩或放大视口。等这一轮布局量完再钉，否则钉的是「旧的底部」，
    // 键盘一冒出来内容就又跑到下面去了（这就是上一版键盘弹起不贴底的原因）。
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.viewportSize.height }.collect {
            if (atBottom) stickToBottom()
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onForeground() }

    var showWorkspace by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel.error) {
        viewModel.error?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    Scaffold(
        modifier = Modifier.statusBarsPadding(),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
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
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    val todo = viewModel.workspaceTodo
                    if (todo != null && todo.todos.isNotEmpty()) {
                        Text(
                            "${todo.done}/${todo.todos.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                    IconButton(
                        onClick = {
                            viewModel.loadWorkspace()
                            showWorkspace = true
                        },
                    ) {
                        Icon(Icons.Filled.ViewSidebar, contentDescription = "工作区")
                    }
                    if (viewModel.streaming) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 16.dp)
                                .size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(onClick = { viewModel.refreshHistory() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            Composer(
                value = draft,
                onValueChange = { draft = it },
                sending = viewModel.streaming,
                onSend = {
                    val text = draft
                    draft = ""
                    atBottom = true
                    viewModel.send(text)
                },
                onStop = { viewModel.stop() },
            )
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
                    items(rows, key = { it.key }) { item ->
                        when (item) {
                            is TimelineItem.UserMessage -> UserBubble(item)
                            is TimelineItem.AssistantMessage -> AssistantBlock(item)
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

    if (showWorkspace) {
        WorkspaceSheet(viewModel = viewModel, onDismiss = { showWorkspace = false })
    }
    FilePreviewDialog(viewModel = viewModel)
}

@Composable
private fun UserBubble(item: TimelineItem.UserMessage) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
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
private fun AssistantBlock(item: TimelineItem.AssistantMessage) {
    var thinkingOpen by remember(item.key, item.streaming) { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
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
            MarkdownText(markdown = item.text, streaming = item.streaming)
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
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("发消息给这个会话…") },
                maxLines = 6,
                shape = RoundedCornerShape(18.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            )
            Spacer(Modifier.width(8.dp))
            if (sending) {
                FilledIconButton(onClick = onStop) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(MaterialTheme.colorScheme.onPrimary, RoundedCornerShape(2.dp)),
                    )
                }
            } else {
                FilledIconButton(onClick = onSend, enabled = value.isNotBlank()) {
                    Icon(Icons.Filled.Send, contentDescription = "发送")
                }
            }
        }
    }
}

private val clockFormat = SimpleDateFormat("HH:mm", Locale.US)

private fun clock(millis: Long): String =
    if (millis <= 0) "" else clockFormat.format(Date(millis))
