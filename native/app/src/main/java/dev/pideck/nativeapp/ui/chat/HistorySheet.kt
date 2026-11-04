package dev.pideck.nativeapp.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pideck.nativeapp.data.TimelineItem

/**
 * 一次提问（含它引出的回答），历史面板里的跳转单位。
 *
 * @param key 这条提问在时间线里的 key，用来回滚定位
 * @param prompt 提问正文（已压成一行）
 * @param reply 回答开头（没有正文时为工具/思考的摘要）
 * @param time 发生时刻，已格式化
 */
data class HistoryTurn(
    val key: String,
    val prompt: String,
    val reply: String,
    val time: String,
)

/**
 * 把时间线折成「提问 → 回答」的轮次，最新的在最前面。
 *
 * 只保留提问作为锚点：回答在长会话里可能连着几十条工具消息，单列会淹没重点。
 */
fun buildHistoryTurns(items: List<TimelineItem>, formatTime: (Long) -> String): List<HistoryTurn> {
    val turns = mutableListOf<HistoryTurn>()
    var currentKey: String? = null
    var currentPrompt = ""
    var currentTime = ""
    val reply = StringBuilder()
    var toolCount = 0

    fun flush() {
        val key = currentKey ?: return
        val text = reply.toString().trim()
        turns += HistoryTurn(
            key = key,
            prompt = currentPrompt,
            // 没有正文时才退化成「调用 N 个工具」，别把摘要和正文拼在一起
            reply = text.ifEmpty { if (toolCount > 0) "调用 $toolCount 个工具" else "" },
            time = currentTime,
        )
        reply.setLength(0)
        toolCount = 0
    }

    items.forEach { item ->
        when (item) {
            is TimelineItem.UserMessage -> {
                flush()
                currentKey = item.key
                currentPrompt = squeeze(item.text)
                currentTime = formatTime(item.timestamp)
            }

            is TimelineItem.AssistantMessage -> {
                toolCount += item.tools.size
                if (item.text.isNotBlank()) {
                    if (reply.isNotEmpty()) reply.append(' ')
                    reply.append(squeeze(item.text).take(120))
                }
            }
        }
    }
    flush()

    return turns.asReversed()
}

private fun squeeze(text: String): String = text.replace(Regex("\\s+"), " ").trim()

/**
 * 会话历史面板：列出这次会话里问过的每一句，点一下跳回去。
 *
 * 顶栏那个历史按钮原先开的是会话抽屉，等于把左侧栏又抄了一遍 —— 这里给的才是
 * 「当前这条会话的历史」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorySheet(
    turns: List<HistoryTurn>,
    onPick: (HistoryTurn) -> Unit,
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
                    .padding(start = 20.dp, end = 20.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "本会话的提问",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${turns.size} 条",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                thickness = 0.5.dp,
            )

            if (turns.isEmpty()) {
                Text(
                    text = "这个会话还没有你的提问",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp),
                )
            } else {
                LazyColumn(Modifier.heightIn(max = 440.dp)) {
                    items(turns, key = { it.key }) { turn ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(turn) }
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = turn.prompt.ifBlank { "（空提问）" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (turn.reply.isNotBlank()) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = turn.reply,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = turn.time,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}
