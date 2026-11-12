package dev.pideck.nativeapp.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pideck.nativeapp.data.SubagentEntry
import dev.pideck.nativeapp.ui.common.relativeTime
import kotlin.math.max

/**
 * 子代理那一行的元信息：类型 · 工具次数 · tokens · 耗时。
 *
 * 宿主这几项都是可选的（只有 id / type / status 一定有），缺什么就少写一段，
 * 不要用 `0 tok` / `0 秒` 去占位 —— 那会被读成「真的没用 token」。
 */
internal fun subagentMetaText(entry: SubagentEntry, now: Long = System.currentTimeMillis()): String {
    val parts = mutableListOf<String>()
    entry.type.trim().takeIf { it.isNotEmpty() }?.let { parts += it }
    entry.toolUses?.takeIf { it > 0 }?.let { parts += "$it 次工具" }
    entry.tokens?.takeIf { it > 0 }?.let { parts += "$it tok" }
    val started = entry.startedAt
    val ended = entry.completedAt
    when {
        started != null && ended != null && ended >= started -> parts += formatSpan(ended - started)
        // 还在跑：算到现在，别报「刚刚」（读起来像刚启动）
        started != null -> parts += formatSpan(max(0L, now - started)) + "（跑着）"
        ended != null -> parts += relativeTime(ended, now)
    }
    return parts.joinToString(" · ")
}

private fun formatSpan(millis: Long): String {
    val seconds = max(0L, millis / 1000)
    return if (seconds >= 60) "${seconds / 60} 分 ${seconds % 60} 秒" else "$seconds 秒"
}

/** 子代理列表（`GET /api/sessions/:id/subagents`）。数据本来就在 ViewModel 里，这里只负责摆出来。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubagentsSheet(
    entries: List<SubagentEntry>,
    busy: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "子代理",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (entries.isEmpty()) "0 个" else "${entries.size} 个",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onRefresh, enabled = !busy) {
                    Text(if (busy) "读取中…" else "刷新")
                }
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                thickness = 0.5.dp,
            )

            when {
                busy && entries.isEmpty() -> Row(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("正在读取…", style = MaterialTheme.typography.bodyMedium)
                }

                entries.isEmpty() -> Text(
                    text = error
                        ?: ("这个会话还没有派过子代理。\n"
                            + "它出现在两处：一是模型的 subagent 工具，二是会话里用过的 acp_delegate。"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (error != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(20.dp),
                )

                else -> LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    items(entries, key = { it.id.ifBlank { it.description.take(24) } }) { entry ->
                        SubagentRow(entry)
                    }
                }
            }
            if (entries.isNotEmpty() && error != null) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SubagentRow(entry: SubagentEntry) {
    // 描述可能是好几段任务书：默认三行，点一下展开全部（长按选中留给正文浮窗，这里不做复制）。
    var expanded by remember(entry.id) { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        StatusDot(entry.status)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.description.trim().ifBlank { "（没有描述）" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subagentMetaText(entry),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!expanded && entry.description.length > 60) {
                TextButton(
                    onClick = { expanded = true },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) {
                    Text("展开", style = MaterialTheme.typography.labelSmall)
                }
            }
            entry.error?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = entry.statusLabel,
            style = MaterialTheme.typography.labelSmall,
            color = when {
                entry.isRunning -> MaterialTheme.colorScheme.primary
                entry.status == "error" -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.outline
            },
        )
    }
}

@Composable
private fun StatusDot(status: String) {
    val color = when (status) {
        "running", "queued" -> MaterialTheme.colorScheme.primary
        "error" -> MaterialTheme.colorScheme.error
        "completed" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outline
    }
    Box(
        Modifier
            .padding(top = 6.dp)
            .size(8.dp)
            .background(color, CircleShape),
    )
}
