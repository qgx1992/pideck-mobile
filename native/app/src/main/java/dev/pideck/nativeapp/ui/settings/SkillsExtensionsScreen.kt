package dev.pideck.nativeapp.ui.settings

import android.content.Context
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.pideck.nativeapp.data.ExtensionEntry
import dev.pideck.nativeapp.data.SkillEntry
import dev.pideck.nativeapp.ui.theme.AppIconButton

/**
 * 「技能 / 扩展」页（设置 → 技能与扩展）。
 *
 * 桌面端在「设置 → PiDeck 插件」里，Web 版是一个对话框；手机放到独立页面，
 * 因为两类东西都是**长列表 + 每行一段说明**，塞进设置页会把那一页拉得很长。
 *
 * 只能开关，不能新建/删除 —— 增删技能与安装扩展在桌面端走 Electron IPC，
 * HTTP 没有对应路由（见 AGENTS.md）。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SkillsExtensionsScreen(
    onBack: () -> Unit,
    viewModel: SkillsViewModel = hiltViewModel(),
) {
    var tab by rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("技能与扩展") },
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
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = { Text("技能（${viewModel.skills.size}）") },
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text("扩展（${viewModel.extensions.size}）") },
                )
            }
            viewModel.error?.let { message ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        message,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (viewModel.loading && viewModel.skills.isEmpty() && viewModel.extensions.isEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp))
                }
                return@Column
            }
            if (tab == 0) {
                SkillsList(
                    skills = viewModel.skills,
                    busyIds = viewModel.busyIds,
                    onToggle = viewModel::toggle,
                    onRefresh = viewModel::refresh,
                )
            } else {
                ExtensionsList(
                    extensions = viewModel.extensions,
                    conflicts = viewModel.conflicts,
                    busyIds = viewModel.busyIds,
                    onToggle = viewModel::toggleExtension,
                )
            }
        }
    }
}

@Composable
private fun SkillsList(
    skills: List<SkillEntry>,
    busyIds: Set<String>,
    onToggle: (SkillEntry, Boolean) -> Unit,
    onRefresh: () -> Unit,
) {
    if (skills.isEmpty()) {
        EmptyBlock("宿主上还没有技能", "桌面端 `~/.pi/agent/skills`、`~/.agents/skills`、`~/.pi/agent/pideck-skills` 三个目录里的技能都会出现在这里。")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "skills-hint") {
            Text(
                "在这里关掉的技能，桌面端与手机会同时生效；改动在下一次新会话或重启会话后完全生效。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(skills, key = { it.id }) { skill ->
            SkillCard(
                skill = skill,
                busy = skill.id in busyIds,
                onToggle = { onToggle(skill, it) },
            )
        }
        item(key = "skills-refresh") {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                onClick = onRefresh,
            ) {
                Text(
                    "重新读取",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(vertical = 10.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun SkillCard(skill: SkillEntry, busy: Boolean, onToggle: (Boolean) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        skill.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!skill.valid) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "失效",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (skill.description.isNotBlank()) {
                    Text(
                        skill.description,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                Text(
                    skill.sourceLabel.ifBlank { skill.sourceId },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
                skill.warnings.firstOrNull()?.let { warning ->
                    Text(
                        warning,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Switch(
                checked = skill.enabled,
                onCheckedChange = onToggle,
                enabled = !busy,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }
    }
}

@Composable
private fun ExtensionsList(
    extensions: List<ExtensionEntry>,
    conflicts: List<String>,
    busyIds: Set<String>,
    onToggle: (ExtensionEntry, Boolean) -> Unit,
) {
    if (extensions.isEmpty()) {
        EmptyBlock("宿主上还没有装扩展", "在桌面端「设置 → PiDeck 插件」里装的扩展会出现在这里。")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (conflicts.isNotEmpty()) {
            item(key = "conflicts") {
                Text(
                    "有 ${conflicts.size} 组同名扩展同时存在，只有优先级高的那份生效：" + conflicts.joinToString("、"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        item(key = "ext-hint") {
            Text(
                "扩展的开关在会话重启后生效。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(extensions, key = { it.id }) { extension ->
            ExtensionRow(
                extension = extension,
                busy = extension.id in busyIds,
                onToggle = { onToggle(extension, it) },
            )
        }
    }
}

@Composable
private fun ExtensionRow(extension: ExtensionEntry, busy: Boolean, onToggle: (Boolean) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Extension,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    extension.source,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(if (extension.builtIn) "内置" else extension.scope)
                        extension.currentVersion?.let { append(" · v").append(it) }
                        if (extension.filtered) append(" · 被同名扩展覆盖")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Switch(
                checked = extension.enabled,
                onCheckedChange = onToggle,
                enabled = !busy,
            )
        }
    }
}

@Composable
private fun EmptyBlock(title: String, hint: String) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
