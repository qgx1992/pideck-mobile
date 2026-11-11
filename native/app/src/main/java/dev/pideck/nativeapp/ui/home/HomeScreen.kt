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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AlertDialog
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.pideck.nativeapp.data.AgentBackend
import dev.pideck.nativeapp.data.AvailableModel
import dev.pideck.nativeapp.data.Project
import dev.pideck.nativeapp.data.SessionModel
import dev.pideck.nativeapp.data.UpdateCenter
import dev.pideck.nativeapp.data.UpdateState
import dev.pideck.nativeapp.ui.common.openUrl
import dev.pideck.nativeapp.data.thinkingLabel
import dev.pideck.nativeapp.data.Session
import dev.pideck.nativeapp.ui.theme.AppButton
import dev.pideck.nativeapp.ui.theme.AppButtonStyle
import dev.pideck.nativeapp.ui.theme.AppIconButton
import dev.pideck.nativeapp.ui.theme.BackendBadge
import dev.pideck.nativeapp.ui.theme.SelectChip
import dev.pideck.nativeapp.ui.common.SessionRowText
import dev.pideck.nativeapp.ui.common.relativeTime

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

    // 以前首页只在自己 `init` 里拉一次：从聊天页返回时「最近的会话」、运行态、模型胶囊
    // 全是进 App 那一刻的快照（在桌面端改个名字都要杀掉重进才看得到）。
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onForeground() }

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
            // 查到新版就横一条：只在「自动检查」开着且确实有新版本时出现，点开是 Release 页。
            // 详细的「检查更新」在设置 → 更新（那儿还有一个开关），这里只做提醒。
            (UpdateCenter.state as? UpdateState.Available)?.let { available ->
                item("update-banner") {
                    val context = LocalContext.current
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        onClick = { openUrl(context, available.release.pageUrl) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "有新版 ${available.release.versionName}（本机 ${UpdateCenter.currentVersion}）· 点这里看",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

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

            // 模型 + 思考单独一行：模型名普遍比后端名长，和工作区、后端挤同一行会把三枚都压扁；
            // 行内模型那枚吃掉剩余宽度（超长省略），思考那枚固定宽度、永远不会被挤掉。
            // 只有 pi 才有这一行 —— DSH 有自己的模型体系，ACP 会话宿主不落模型偏好。
            if (viewModel.backend == AgentBackend.Pi) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f)) {
                            ModelPicker(viewModel = viewModel)
                        }
                        ThinkingPicker(viewModel = viewModel)
                    }
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
            enabled = !viewModel.creating,
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 240.dp, max = 340.dp),
        ) {
            // 手机没有目录选择器，只能手输桌面端上的绝对路径；宿主只校验「非空」
            DropdownMenuItem(
                text = { Text("新建工作区…", style = MaterialTheme.typography.bodyMedium) },
                leadingIcon = {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                },
                onClick = {
                    expanded = false
                    viewModel.openWorkspaceEditor()
                },
            )
            if (viewModel.projects.isNotEmpty()) HorizontalDivider()
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
    if (viewModel.workspaceEditorOpen) WorkspaceDialog(viewModel)
}

/** 新建工作区：手输路径 → 宿主登记 → 建完直接选中。 */
@Composable
private fun WorkspaceDialog(viewModel: HomeViewModel) {
    AlertDialog(
        onDismissRequest = { viewModel.closeWorkspaceEditor() },
        title = { Text("新建工作区") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "填桌面端那台机器上的目录绝对路径（用正斜杠最省事），例如 D:/A-my-project/我的项目。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = viewModel.workspacePath,
                    onValueChange = viewModel::editWorkspacePath,
                    label = { Text("目录路径") },
                    singleLine = true,
                    enabled = !viewModel.workspaceBusy,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                viewModel.workspaceError?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    "宿主只检查路径非空：写错了也会建出一个空项目，删掉重来即可。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = viewModel::createWorkspace,
                enabled = viewModel.workspacePath.isNotBlank() && !viewModel.workspaceBusy,
            ) {
                Text(if (viewModel.workspaceBusy) "创建中…" else "创建")
            }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.closeWorkspaceEditor() }, enabled = !viewModel.workspaceBusy) {
                Text("取消")
            }
        },
    )
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
            // 标题 + 元信息走共用块（ui/common/SessionRowText.kt）；右侧那个小圆点是首页自己加的「活跃」记号
            SessionRowText(
                session = session,
                meta = "$projectName · ${relativeTime(session.updatedAt)}",
                acpToolName = acpToolName,
                titleStyle = MaterialTheme.typography.bodyMedium,
                metaStyle = MaterialTheme.typography.labelSmall,
                verticalPadding = 1,
                modifier = Modifier.weight(1f),
            )
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
 * 新会话的思考档位：和顶栏那颗同款（`思考 中`），但在新建之前就能定。
 *
 * 档位表跟着 [HomeViewModel.thinkingLevels] 走：选了具体模型就只列它支持的档位，
 * 没选模型就给 pi 的标准表。默认 `null` = 跟随电脑端默认 —— 宿主按
 * 「模型能力表 → `settings.defaultThinkingLevel`」解析，本端不猜。
 */
@Composable
private fun ThinkingPicker(viewModel: HomeViewModel) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val selected = viewModel.selectedThinking
    val levels = viewModel.thinkingLevels
    val available = viewModel.thinkingAvailable
    Box {
        SelectChip(
            text = when {
                !available -> "不思考"
                selected != null -> "思考 ${thinkingLabel(selected)}"
                else -> "思考 默认"
            },
            onClick = { expanded = true },
            trailing = Icons.Filled.KeyboardArrowDown,
            // 和会话页同一套高亮口径：高档位才着色，默认态保持中性
            accent = available && selected in setOf("high", "xhigh", "max"),
            enabled = available && !viewModel.creating,
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = {
                    Column {
                        Text("跟随电脑端默认", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "不指定，由电脑端按模型能力解析",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                trailingIcon = if (selected == null) {
                    { Icon(Icons.Filled.Check, contentDescription = null) }
                } else {
                    null
                },
                onClick = {
                    viewModel.selectThinking(null)
                    expanded = false
                },
            )
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            levels.forEach { level ->
                DropdownMenuItem(
                    text = { Text(thinkingLabel(level), style = MaterialTheme.typography.bodyMedium) },
                    trailingIcon = if (selected == level) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    onClick = {
                        viewModel.selectThinking(level)
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * 新会话用哪个模型：只有 pi 有这一颗。
 *
 * 默认是「跟随电脑端默认」—— 不带 `model` 字段，宿主按它自己的链路解析
 * （`welcomeModel ?? 设置里的默认模型 ?? 第一个启用模型 ?? 上次用的`）。
 * 选了就原样写进 `POST /api/sessions` 的 body，宿主落盘、这一整个会话都用它
 * （之后还能在会话页顶栏那颗胶囊里改）。
 *
 * 布局：整行一枚胶囊（`fillMaxWidth`），模型名超长省略；下拉按 provider 分组，
 * 首项是「跟随电脑端默认」——「不选」必须是个能点回来的选项，否则选错一次就没路退。
 */
@Composable
private fun ModelPicker(viewModel: HomeViewModel) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val selected = viewModel.selectedModel
    val models = viewModel.models
    Box {
        SelectChip(
            text = when {
                selected != null -> selected.shortLabel
                viewModel.modelsBusy && models.isEmpty() -> "读取模型…"
                models.isEmpty() -> "模型列表没拉到 · 点这里重试"
                else -> "模型 · 跟随电脑端默认"
            },
            onClick = {
                // 列表空（首次没拉到 / 主机刚启动）时，点一下先补一次，别让人对着空下拉；
                // 正在读的时候点了就等它回来，不叠第二个请求。
                when {
                    models.isNotEmpty() -> expanded = true
                    !viewModel.modelsBusy -> viewModel.loadModels(force = true)
                }
            },
            icon = Icons.Default.Memory,
            trailing = Icons.Filled.KeyboardArrowDown,
            // 「跟随默认」是中性态，选过具体模型才高亮
            accent = selected != null,
            enabled = !viewModel.creating,
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 260.dp, max = 360.dp),
        ) {
            DropdownMenuItem(
                text = {
                    Column {
                        Text("跟随电脑端默认", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "不指定，由电脑端按它的默认模型启动",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                trailingIcon = if (selected == null) {
                    { Icon(Icons.Filled.Check, contentDescription = null) }
                } else {
                    null
                },
                onClick = {
                    viewModel.selectModel(null)
                    expanded = false
                },
            )
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            models.groupBy { it.provider }.forEach { (provider, group) ->
                Text(
                    provider.ifBlank { "其他" },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp),
                )
                group.forEach { model ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    model.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    modelSubtitle(model),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        trailingIcon = if (selected?.provider == model.provider && selected.modelId == model.id) {
                            { Icon(Icons.Filled.Check, contentDescription = null) }
                        } else {
                            null
                        },
                        onClick = {
                            viewModel.selectModel(
                                SessionModel(
                                    provider = model.provider,
                                    modelId = model.id,
                                    modelName = model.name,
                                ),
                            )
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

/** 模型下拉的副标题：id · 上下文长度 · 能不能思考。 */
private fun modelSubtitle(model: AvailableModel): String = buildString {
    append(model.id)
    if (model.contextWindow > 0) {
        append(" · ")
        append(if (model.contextWindow >= 1000) "${model.contextWindow / 1000}k" else "${model.contextWindow}")
    }
    if (model.reasoning) append(" · 可思考")
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
