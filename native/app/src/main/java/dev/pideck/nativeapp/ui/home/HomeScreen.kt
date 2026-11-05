package dev.pideck.nativeapp.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.pideck.nativeapp.data.AgentBackend
import dev.pideck.nativeapp.data.Project
import dev.pideck.nativeapp.data.Session
import dev.pideck.nativeapp.ui.theme.AppButton
import dev.pideck.nativeapp.ui.theme.AppButtonStyle
import dev.pideck.nativeapp.ui.theme.AppIconButton
import dev.pideck.nativeapp.ui.theme.BackendBadge
import dev.pideck.nativeapp.ui.theme.SelectChip
import dev.pideck.nativeapp.ui.sessions.relativeTime

/**
 * 首页 —— 桌面网页版的默认落地页在手机上的对应物。
 *
 * 与网页版的差别只在这一处：网页版替你挑了默认项目（`kind == "chat"` 或第一个），
 * 这里把工作区摆出来让你选，快捷键是「选完直接说事」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenChat: (sessionId: String, draft: String) -> Unit,
    onOpenSessions: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    var text by rememberSaveable { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("PiDeck", style = MaterialTheme.typography.titleMedium)
                        viewModel.deviceName?.let { name ->
                            Text(
                                name,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    AppIconButton(
                        icon = Icons.AutoMirrored.Filled.List,
                        contentDescription = "会话列表",
                        onClick = onOpenSessions,
                    )
                    Spacer(Modifier.width(8.dp))
                    AppIconButton(
                        icon = Icons.Default.Settings,
                        contentDescription = "设置",
                        onClick = onOpenSettings,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = 8.dp,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "开始一个新会话",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            // 工作区 + 后端并排两枚胶囊：都是「下拉选一个」，形态一致就不用两套心智。
            // 工作区那枚吃掉剩余宽度（项目名可能很长），下面再补一行完整路径，免得同名项目分不清。
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        WorkspacePicker(viewModel = viewModel)
                    }
                    BackendPicker(viewModel = viewModel)
                }
            }

            viewModel.selectedProject?.path?.takeIf { it.isNotBlank() }?.let { path ->
                item {
                    Text(
                        path,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                    )
                }
            }

            item {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("让 Agent 在这个工作区里做什么…") },
                    minLines = 3,
                    maxLines = 8,
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "回车换行 · 点按钮发送",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                    SendButton(
                        enabled = text.isNotBlank() &&
                            viewModel.selectedProject != null &&
                            !viewModel.creating,
                        busy = viewModel.creating,
                        onClick = {
                            val projectId = viewModel.selectedProjectId ?: return@SendButton
                            val prompt = text.trim()
                            viewModel.startSession(projectId) { sessionId ->
                                text = ""
                                onOpenChat(sessionId, prompt)
                            }
                        },
                    )
                }
            }

            viewModel.error?.let { message ->
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            AppButton(text = "重新加载", onClick = { viewModel.load() }, style = AppButtonStyle.Ghost)
                        }
                    }
                }
            }

            if (viewModel.projects.isEmpty() && !viewModel.loading) {
                item {
                    InfoCard(
                        "还没有工作区",
                        "桌面端里加一个项目（工作区），或者先把 Agent 指向一个目录，这里就会出现可选的项。",
                    )
                }
            }

            if (viewModel.recent.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "最近的会话",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                }
                items(viewModel.recent, key = { it.id }) { session ->
                    RecentSessionRow(
                        session = session,
                        projectName = viewModel.projectNameOf(session.projectId),
                        acpToolName = viewModel.acpTools
                            .firstOrNull { it.id == session.acpToolId }?.name,
                        onClick = { onOpenChat(session.id, "") },
                    )
                }
            }
        }
    }
}

/**
 * 工作区选择：和后端那枚一样是「胶囊 + 下拉」。
 *
 * 以前是一排横向滚动的 FilterChip —— 项目一多就得左右划，也看不出选的是哪个目录。
 * 现在名字在胶囊里（超长省略），完整路径另起一行显示在它下面。
 */
@Composable
private fun WorkspacePicker(viewModel: HomeViewModel) {
    if (viewModel.loading && viewModel.projects.isEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(
                "读取工作区…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
        return
    }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val selected = viewModel.selectedProject
    Box {
        SelectChip(
            text = selected?.let(::projectLabel) ?: "选择工作区",
            onClick = { expanded = true },
            icon = Icons.Outlined.FolderOpen,
            trailing = Icons.Filled.KeyboardArrowDown,
            accent = selected == null,
            // 一个工作区都没有时别让它可点：空下拉只会让人以为卡住了
            enabled = viewModel.projects.isNotEmpty() && !viewModel.creating,
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 240.dp, max = 340.dp),
        ) {
            viewModel.projects.forEach { project ->
                val isSelected = project.id == viewModel.selectedProjectId
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                projectLabel(project),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            project.path?.takeIf { it.isNotBlank() }?.let { path ->
                                Text(
                                    path,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    },
                    trailingIcon = if (isSelected) {
                        {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "当前工作区",
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    } else {
                        null
                    },
                    onClick = {
                        viewModel.select(project.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun projectLabel(project: Project): String =
    project.name.ifBlank { project.path?.ifBlank { null } ?: "未命名工作区" }

@Composable
private fun SendButton(enabled: Boolean, busy: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.size(48.dp),
    ) {
        IconButton(onClick = onClick, enabled = enabled) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Icon(
                    Icons.Default.ArrowUpward,
                    contentDescription = "发送",
                    tint = if (enabled) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RecentSessionRow(
    session: Session,
    projectName: String,
    onClick: () -> Unit,
    acpToolName: String? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    session.title.ifBlank { "未命名会话" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$projectName · ${relativeTime(session.updatedAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    AgentBackend.badgeOf(session.backend, acpToolName)?.let { label ->
                        Spacer(Modifier.width(6.dp))
                        BackendBadge(
                            label,
                            muted = session.backend.isNullOrBlank() || session.backend == "pi",
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(
                        if (session.status == "active") MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                        RoundedCornerShape(50),
                    ),
            )
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 「用哪个 Agent 后端开这个会话」：Pi / DSH / 每个 ACP 工具。
 *
 * 值直接进 `POST /api/sessions` 的 body（宿主全量透传），所以选项表就是
 * 「pi + DSH + `/api/state` 的 `acpTools`」—— imagegen / videogen 走不通这条路由，不列。
 */
@Composable
private fun BackendPicker(viewModel: HomeViewModel) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val selected = viewModel.backend
    val options = viewModel.backendOptions
    Box {
        SelectChip(
            text = "后端 ${selected.label}",
            onClick = { expanded = true },
            icon = Icons.Default.Hub,
            trailing = Icons.Filled.KeyboardArrowDown,
            enabled = !viewModel.creating,
            accent = selected != AgentBackend.Pi,
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(option.label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                option.hint,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        viewModel.selectBackend(option)
                        expanded = false
                    },
                )
            }
        }
    }
}
