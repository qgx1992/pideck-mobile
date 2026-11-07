package dev.pideck.nativeapp.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pideck.nativeapp.data.PromptSummary
import dev.pideck.nativeapp.data.QuickMessages
import dev.pideck.nativeapp.ui.theme.SelectChip
import kotlinx.coroutines.delay

/** 面板的两段：提示词库（宿主上的模板）/ 快捷消息（本机的一句话清单）。 */
internal enum class ComposerPanelTab(val label: String) { Prompts("提示词库"), Quick("快捷消息") }

/**
 * 输入区左下角那颗书本按钮打开的面板。
 *
 * 两段的分工照桌面端：
 * - **提示词库**（`GET /api/prompts`）：带分类、带描述的模板。点一条**先展开正文**
 *   （`GET /api/prompts/:slug`），看清模板再「插入到输入框」—— 模板里常有变量与多段说明，
 *   直接塞进去会让人不知道自己在发什么。
 * - **快捷消息**：一句话，点一下插进输入框、或者直接发出去。桌面端那份存在
 *   `userData/quick-messages.json` 且只有 IPC，手机读不到，所以这里是**本机**维护的一份。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComposerPanelSheet(
    tab: ComposerPanelTab,
    onTabChange: (ComposerPanelTab) -> Unit,
    // ---- 提示词库 ----
    prompts: List<PromptSummary>,
    categories: List<String>,
    category: String?,
    query: String,
    loading: Boolean,
    error: String?,
    hasMore: Boolean,
    preview: PromptSummary?,
    previewBody: String?,
    previewBusy: Boolean,
    onQueryChange: (String) -> Unit,
    onCategoryChange: (String?) -> Unit,
    onLoadMore: () -> Unit,
    onPreview: (PromptSummary) -> Unit,
    onBackFromPreview: () -> Unit,
    onInsertPreview: (String) -> Unit,
    // ---- 快捷消息 ----
    quickMessages: List<String>,
    quickDraft: String,
    onQuickDraftChange: (String) -> Unit,
    onAddQuick: () -> Unit,
    onRemoveQuick: (String) -> Unit,
    onInsertQuick: (String) -> Unit,
    onSendQuick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            if (preview != null) {
                PromptPreview(
                    prompt = preview,
                    body = previewBody,
                    busy = previewBusy,
                    onBack = onBackFromPreview,
                    onInsert = onInsertPreview,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ComposerPanelTab.entries.forEach { entry ->
                        SelectChip(
                            text = entry.label,
                            accent = tab == entry,
                            onClick = { onTabChange(entry) },
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                }
                Spacer(Modifier.height(10.dp))
                when (tab) {
                    ComposerPanelTab.Prompts -> PromptList(
                        prompts = prompts,
                        categories = categories,
                        category = category,
                        query = query,
                        loading = loading,
                        error = error,
                        hasMore = hasMore,
                        onQueryChange = onQueryChange,
                        onCategoryChange = onCategoryChange,
                        onLoadMore = onLoadMore,
                        onPreview = onPreview,
                    )

                    ComposerPanelTab.Quick -> QuickMessageList(
                        items = quickMessages,
                        draft = quickDraft,
                        onDraftChange = onQuickDraftChange,
                        onAdd = onAddQuick,
                        onRemove = onRemoveQuick,
                        onInsert = onInsertQuick,
                        onSend = onSendQuick,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    }
}

@Composable
private fun PromptList(
    prompts: List<PromptSummary>,
    categories: List<String>,
    category: String?,
    query: String,
    loading: Boolean,
    error: String?,
    hasMore: Boolean,
    onQueryChange: (String) -> Unit,
    onCategoryChange: (String?) -> Unit,
    onLoadMore: () -> Unit,
    onPreview: (PromptSummary) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("搜索提示词…") },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { }),
    )
    if (categories.isNotEmpty()) {
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(key = "cat-all") {
                FilterChip(
                    selected = category == null,
                    onClick = { onCategoryChange(null) },
                    label = { Text("全部分类") },
                )
            }
            items(categories, key = { "cat-" + it }) { name ->
                FilterChip(
                    selected = category == name,
                    onClick = { onCategoryChange(name) },
                    label = { Text(name) },
                )
            }
        }
    }
    error?.let {
        Text(
            it,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(bottom = 6.dp),
        )
    }
    when {
        loading && prompts.isEmpty() -> Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp))
        }

        prompts.isEmpty() -> Text(
            "没有匹配的提示词",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 24.dp),
        )

        else -> LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
            contentPadding = PaddingValues(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(prompts, key = { it.slug }) { prompt ->
                PromptRow(prompt = prompt, onPick = { onPreview(prompt) })
            }
            if (hasMore) {
                item(key = "more") {
                    LaunchedEffect(prompts.size) { delay(50); onLoadMore() }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun PromptRow(prompt: PromptSummary, onPick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onPick),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    prompt.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                prompt.category?.let { name ->
                    Spacer(Modifier.width(8.dp))
                    Text(
                        name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            if (prompt.description.isNotBlank()) {
                Text(
                    prompt.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** 模板正文。等宽字体 + 可滚动：模板里常有缩进与代码块，挤成正文样式就看不出结构了。 */
@Composable
private fun PromptPreview(
    prompt: PromptSummary,
    body: String?,
    busy: Boolean,
    onBack: () -> Unit,
    onInsert: (String) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回提示词列表")
        }
        Spacer(Modifier.width(4.dp))
        Text(
            prompt.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        prompt.category?.let { name ->
            Text(
                name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
    if (prompt.description.isNotBlank()) {
        Text(
            prompt.description,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        when {
            busy -> Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            }

            body.isNullOrBlank() -> Text(
                "取不到这条提示词的内容",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(14.dp),
            )

            else -> Text(
                text = body,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = onBack) { Text("返回") }
        TextButton(
            onClick = { body?.takeIf { it.isNotBlank() }?.let(onInsert) },
            enabled = !body.isNullOrBlank() && !busy,
        ) { Text("插入到输入框") }
    }
}

@Composable
private fun QuickMessageList(
    items: List<String>,
    draft: String,
    onDraftChange: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onInsert: (String) -> Unit,
    onSend: (String) -> Unit,
) {
    OutlinedTextField(
        value = draft,
        onValueChange = onDraftChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("新增一条快捷消息…") },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onAdd() }),
        trailingIcon = {
            IconButton(onClick = onAdd, enabled = draft.isNotBlank()) {
                Icon(Icons.Default.Add, contentDescription = "添加")
            }
        },
        supportingText = {
            Text(
                "快捷消息是**这台手机**上的清单（桌面端那份存在本机配置文件里，HTTP 读不到）。" +
                    "点一条插进输入框，右边的箭头直接发出去。",
                style = MaterialTheme.typography.labelSmall,
            )
        },
    )
    Spacer(Modifier.height(4.dp))
    if (items.isEmpty()) {
        Text(
            "还没有快捷消息",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 24.dp),
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp),
        contentPadding = PaddingValues(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(items, key = { it }) { text ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().clickable { onInsert(text) },
            ) {
                Row(
                    modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { onSend(text) }, modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "直接发送",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    IconButton(onClick = { onRemove(text) }, modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "删除这条快捷消息",
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
    Text(
        "上限 ${QuickMessages.MAX_COUNT} 条 · 每条 ${QuickMessages.MAX_LENGTH} 字",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}
