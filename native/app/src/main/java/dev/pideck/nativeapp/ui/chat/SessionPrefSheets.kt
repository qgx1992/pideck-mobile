package dev.pideck.nativeapp.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pideck.nativeapp.data.AvailableModel
import dev.pideck.nativeapp.data.SessionModel
import dev.pideck.nativeapp.data.thinkingLabel
import dev.pideck.nativeapp.ui.theme.AppIconButton

/**
 * 模型选择。
 *
 * 按 provider 分组（同一家底下十几个模型，平铺会看到一堵墙），每行给「名字 + id + 上下文长度」，
 * 当前选中的打勾。列表来自主机自己的 `/api/models`，不在客户端写死任何模型名。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSheet(
    models: List<AvailableModel>,
    current: SessionModel?,
    busy: Boolean,
    onRefresh: () -> Unit,
    onPick: (AvailableModel) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("选择模型", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                }
                AppIconButton(
                    icon = Icons.Default.Refresh,
                    contentDescription = "刷新模型列表",
                    onClick = onRefresh,
                    enabled = !busy,
                    size = 34,
                )
            }
            Text(
                if (current == null) {
                    // 没选过：手机上新建的会话记录本来就不带模型，真正生效的是电脑端的默认模型
                    "现在跟随电脑端的默认模型；选一个只改这个会话，下一轮对话生效"
                } else {
                    "改的是这个会话的记录，下一轮对话生效"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
        }
        if (models.isEmpty() && !busy) {
            Text(
                "没拿到模型列表 —— 点右上角刷新再试一次。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
            Spacer(Modifier.height(16.dp))
            return@ModalBottomSheet
        }
        LazyColumn(modifier = Modifier.heightIn(max = 520.dp)) {
            models.groupBy { it.provider }.forEach { (provider, group) ->
                item(key = "p-$provider") {
                    Text(
                        provider.ifBlank { "其他" },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
                items(group, key = { "$provider/${it.id}" }) { model ->
                    val selected = current?.provider == model.provider && current.modelId == model.id
                    ModelRow(model = model, selected = selected, onClick = { onPick(model) })
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ModelRow(model: AvailableModel, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                model.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(model.id)
                    if (model.contextWindow > 0) {
                        append(" · ")
                        append(if (model.contextWindow >= 1000) "${model.contextWindow / 1000}k" else "${model.contextWindow}")
                    }
                    if (model.reasoning) append(" · 可思考")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 思考档位。只列当前模型支持的档位 —— 传上去不支持的档位，主机会拒或静默降级。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThinkingSheet(
    levels: List<String>,
    current: String?,
    modelLabel: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text("思考程度", style = MaterialTheme.typography.titleMedium)
            Text(
                listOfNotNull(
                    modelLabel,
                    if (current == null) "跟随电脑端默认档位" else null,
                    "下一轮对话生效",
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(12.dp))
        }
        Column(Modifier.fillMaxWidth()) {
            levels.forEach { level ->
                val selected = level == current
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(level) }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        thinkingLabel(level),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    if (selected) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
