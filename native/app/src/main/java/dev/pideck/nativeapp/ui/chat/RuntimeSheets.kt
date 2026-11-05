package dev.pideck.nativeapp.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pideck.nativeapp.data.ContextUsage
import dev.pideck.nativeapp.data.PermissionPresets
import dev.pideck.nativeapp.data.formatTokens
import dev.pideck.nativeapp.ui.theme.AppButton
import dev.pideck.nativeapp.ui.theme.AppButtonStyle
import dev.pideck.nativeapp.ui.theme.ControlShapeSmall

/**
 * 权限预设（桌面端「计划模式」那组）：只读 / 工作区可写 / 完全访问。
 *
 * 与桌面端 `DshPermissionMenu.tsx` 的 `DSH_PERMISSION_PRESETS` 同一组 id。切换优先走
 * `runtime/permission`（运行中的会话立刻生效），同时写回会话记录（下次激活用）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionSheet(
    current: String?,
    busy: Boolean,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("权限预设", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            Text(
                "现在：「${PermissionPresets.labelOf(current)}」。运行中的会话立刻生效，否则记在下一轮。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            PermissionPresets.all.forEach { preset ->
                val selected = preset.id == current
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .clip(ControlShapeSmall)
                        .clickable(enabled = !busy) { onPick(preset.id) }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            preset.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            preset.hint,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (selected) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "当前",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * 上下文用量 + 压缩。
 *
 * 数据来自 `runtime/state`（桌面端头部圆环同源）；压缩是 `runtime/compact`，
 * 可选一句「保留什么」的提示词。运行时没起来时整块显示为不可用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextSheet(
    usage: ContextUsage?,
    busy: Boolean,
    compactBusy: Boolean,
    onRefresh: () -> Unit,
    onCompact: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var prompt by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("上下文用量", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }

            if (usage == null || !usage.known) {
                Text(
                    "没拿到用量。可能是这个会话的运行时还没起来（草稿会话、或刚重启过桌面端），" +
                        "也可能是主机版本较老不返回这个字段。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
                )
                AppButton(text = "重新读取", onClick = onRefresh, style = AppButtonStyle.Tonal, enabled = !busy)
                Spacer(Modifier.height(20.dp))
                return@Column
            }

            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 6.dp)) {
                Text(
                    "${usage.contextPercent.toInt().coerceIn(0, 999)}%",
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (usage.overflow) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    usage.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            LinearProgressIndicator(
                progress = { (usage.contextPercent / 100.0).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
            )
            if (usage.overflow) {
                Text(
                    "已经超出窗口，下一轮会自动压缩或截断",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            Spacer(Modifier.height(14.dp))
            UsageRow("本轮输入", formatTokens(usage.inputTokens))
            UsageRow("本轮输出", formatTokens(usage.outputTokens))
            UsageRow("权限预设", PermissionPresets.labelOf(usage.permissionPreset))
            usage.provider?.let { UsageRow("供应商", it) }

            Spacer(Modifier.height(18.dp))
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("压缩时保留什么（可留空）") },
                placeholder = { Text("例如：只保留最近的代码改动和结论") },
                minLines = 2,
                maxLines = 4,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppButton(
                    text = if (compactBusy) "压缩中…" else "压缩上下文",
                    onClick = { onCompact(prompt.takeIf { it.isNotBlank() }) },
                    enabled = !compactBusy && !busy,
                    style = AppButtonStyle.Filled,
                )
                AppButton(text = "重新读取", onClick = onRefresh, enabled = !busy, style = AppButtonStyle.Ghost)
            }
            Text(
                "压缩会在这个会话里插入一条压缩记录，之后的历史还在，只是模型看到的上下文变短。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp, bottom = 22.dp),
            )
        }
    }
}

@Composable
private fun UsageRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
