package dev.pideck.nativeapp.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.pideck.nativeapp.BuildConfig
import dev.pideck.nativeapp.ui.theme.AppButton
import dev.pideck.nativeapp.ui.theme.AppButtonStyle
import dev.pideck.nativeapp.ui.theme.AppIconButton
import dev.pideck.nativeapp.ui.theme.SelectChip
import dev.pideck.nativeapp.data.HostCompat
import dev.pideck.nativeapp.data.ThemeMode

/**
 * 设置项的下拉选择器：一枚和首页同款的胶囊，点开是带勾的菜单。
 *
 * 设置页原来用一排 `FilterChip` 表示选项，选项一多就换行、和下面的说明文字挤在一起；
 * 统一成下拉之后，行高固定、当前值一眼可读（胶囊上就是当前值）。
 */
@Composable
private fun <T> DropdownSetting(
    value: T,
    options: List<T>,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        SelectChip(
            text = label(value),
            onClick = { expanded = true },
            trailing = Icons.Filled.KeyboardArrowDown,
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 200.dp),
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(label(option)) },
                    trailingIcon = if (option == value) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * 设置页。只放「手机上真的会调」的东西：
 * 主题、跑完通知、当前连接与设备管理、版本信息。
 *
 * 技能 / 扩展现在有独立的子页（宿主 `/api/skills`、`/api/extensions` 是全局接口，
 * 0.5.x 早期只有 dsh 会话级私有接口时的「回桌面端看」判断已经不成立了）。
 * 用量额度仍未做（`/api/usage/:provider`，默认关闭，价值一般）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onManageDevices: () -> Unit,
    onOpenSkills: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    AppIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        onClick = onBack,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SectionTitle("外观")
            DropdownSetting(
                value = viewModel.theme,
                options = ThemeMode.entries.toList(),
                label = { it.label },
                onSelect = { viewModel.chooseTheme(it) },
            )
            Text(
                "深色是默认口味，和桌面端同一套基色。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            SectionTitle("通知")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("跑完时通知我", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "回复是电脑上在跑。开着这个，手机在后台也能收到「跑完了」，点通知直接进那个会话；App 在前台时不打扰你。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = viewModel.notifyDone, onCheckedChange = { viewModel.toggleNotifyDone(it) })
            }

            Spacer(Modifier.height(12.dp))
            SectionTitle("会话列表")
            DropdownSetting(
                value = viewModel.collapseRestore,
                options = listOf(true, false),
                label = { if (it) "恢复上次状态" else "一律全部收起" },
                onSelect = { viewModel.chooseCollapseRestore(it) },
            )
            Text(
                "项目分组的收起/展开每次都写盘。选「恢复上次状态」就沿用退出时的样子；选「全部收起」则每次打开 App 从收起来开始。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            SectionTitle("连接")
            InfoRow("地址", viewModel.device?.baseUrl ?: "还没配对设备")
            InfoRow("桌面端版本", viewModel.hostVersion ?: "未知（回会话列表刷新一次）")
            HostCompat.hint(viewModel.hostVersion)?.let { hint ->
                Text(
                    hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            InfoRow("已配对设备", "${viewModel.deviceCount} 台")
            InfoRow("会话数", "${viewModel.sessionCount}")
            Spacer(Modifier.height(6.dp))
            AppButton(
                text = "管理设备 / 添加新设备",
                onClick = onManageDevices,
                style = AppButtonStyle.Tonal,
                icon = Icons.Default.Dns,
                fillWidth = true,
            )

            Spacer(Modifier.height(12.dp))
            SectionTitle("技能与扩展")
            AppButton(
                text = "管理技能 / 扩展",
                onClick = onOpenSkills,
                style = AppButtonStyle.Tonal,
                icon = Icons.Default.Extension,
                fillWidth = true,
            )
            Text(
                "宿主上的技能与扩展开关（与桌面端共用同一份配置）。只能开关，不能新建或删除 —— 增删要在桌面端做。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            SectionTitle("诊断")
            // 闪退现场一没就只剩一句描述。这里给出栈，用户直接复制粘贴就能定位。
            val crash = viewModel.crashLog
            if (crash == null) {
                InfoRow("崩溃记录", "没有记录（最近一次启动正常）")
            } else {
                Text(
                    crash.lineSequence().take(14).joinToString(separator = "\n"),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                        )
                        .padding(10.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppButton(
                        text = "复制栈",
                        onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(crash)) },
                        style = AppButtonStyle.Ghost,
                        icon = Icons.Default.ContentCopy,
                    )
                    AppButton(
                        text = "清除",
                        onClick = { viewModel.clearCrashLog() },
                        style = AppButtonStyle.Ghost,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            SectionTitle("关于")
            InfoRow("App 版本", "${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）")
            InfoRow("包名", BuildConfig.APPLICATION_ID)
            InfoRow("说明", "这是和 PiDeck 共存的原生客户端：桌面端在跑，这边只做「看 + 发」。")
            Spacer(Modifier.height(18.dp))
            Text(
                "技能、扩展、用量这些是会话级的东西，回桌面端看更清楚。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(92.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Suppress("unused")
private val dot = @Composable {
    Surface(modifier = Modifier.size(8.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primary) {}
}

@Suppress("unused")
private val divider = @Composable { HorizontalDivider() }

@Suppress("unused")
private val bg = @Composable { Modifier.background(MaterialTheme.colorScheme.surface) }
