package dev.pideck.nativeapp.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.pideck.nativeapp.BuildConfig
import dev.pideck.nativeapp.data.ThemeMode

/**
 * 设置页。只放「手机上真的会调」的东西：
 * 主题、跑完通知、当前连接与设备管理、版本信息。
 *
 * 技能 / 扩展 / 用量这些是**会话级**概念（`/api/sessions/:id/dsh/skills`），
 * 挪到全局设置里要再造一层选择器，价值不大 —— 需要时回桌面端看。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onManageDevices: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = viewModel.theme == mode,
                        onClick = { viewModel.chooseTheme(mode) },
                        label = { Text(mode.label) },
                    )
                }
            }
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = viewModel.collapseRestore,
                    onClick = { viewModel.chooseCollapseRestore(true) },
                    label = { Text("恢复上次状态") },
                )
                FilterChip(
                    selected = !viewModel.collapseRestore,
                    onClick = { viewModel.chooseCollapseRestore(false) },
                    label = { Text("一律全部收起") },
                )
            }
            Text(
                "项目分组的收起/展开每次都写盘。选「恢复上次状态」就沿用退出时的样子；选「全部收起」则每次打开 App 从收起来开始。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            SectionTitle("连接")
            InfoRow("地址", viewModel.device?.baseUrl ?: "还没配对设备")
            InfoRow("桌面端版本", viewModel.hostVersion ?: "未知（回会话列表刷新一次）")
            InfoRow("已配对设备", "${viewModel.deviceCount} 台")
            InfoRow("会话数", "${viewModel.sessionCount}")
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = onManageDevices) { Text("管理设备 / 添加新设备") }

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
