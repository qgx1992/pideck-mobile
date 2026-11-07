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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pideck.nativeapp.data.PromptSummary
import kotlinx.coroutines.delay

/**
 * 提示词库（桌面端 Web 的 `WebPromptPicker`，`GET /api/prompts`）。
 *
 * 桌面端是「插到光标处」，手机没有光标概念，一律**追加到输入框末尾**（与 `/` 命令插入同一套语义）。
 * 列表接口只给摘要，「插入」要再按 slug 取正文；写操作（新建/删除提示词）是 IPC，搬不了。
 *
 * 分页：第一页 20 条，滚到底再取下一页 —— 不要在进面板时把所有页拉完，
 * 库大了会很慢，而大多数时候用户在第一页就挑到了。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PromptLibrarySheet(
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
    onPick: (PromptSummary) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text("提示词库", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索提示词…") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onDismiss() }),
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
                        PromptRow(prompt = prompt, onPick = { onPick(prompt) })
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
