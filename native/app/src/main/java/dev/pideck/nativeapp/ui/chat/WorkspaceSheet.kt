package dev.pideck.nativeapp.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pideck.nativeapp.data.TodoEntry
import dev.pideck.nativeapp.data.WorkspaceChanges
import dev.pideck.nativeapp.data.WorkspaceTodo

/**
 * 工作区抽屉：待办 / 改动 / 文件。
 *
 * 三页都是只读视图 —— 手机上不打算做编辑器，看得到「agent 现在在干什么、动了哪些文件、
 * 这个仓库里有什么」就够了；真要改还是回桌面端。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceSheet(
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("待办", "改动", "文件")

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("工作区", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (viewModel.workspaceBusy) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            IconButton(onClick = { viewModel.refreshWorkspace() }) {
                Icon(Icons.Default.Refresh, contentDescription = "刷新")
            }
        }
        TabRow(selectedTabIndex = tab) {
            tabs.forEachIndexed { index, label ->
                Tab(
                    selected = tab == index,
                    onClick = { tab = index },
                    text = { Text(label) },
                )
            }
        }
        Box(modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 560.dp)) {
            when (tab) {
                0 -> TodoTab(viewModel.workspaceTodo)
                1 -> ChangesTab(viewModel.workspaceChanges)
                else -> FilesTab(viewModel)
            }
        }
        viewModel.workspaceError?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun TodoTab(todo: WorkspaceTodo?) {
    if (todo == null || todo.todos.isEmpty()) {
        EmptyHint("这个会话还没有待办计划")
        return
    }
    val total = todo.todos.size
    val done = todo.done
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$done / $total",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            LinearProgressIndicator(
                progress = { done.toFloat() / total },
                modifier = Modifier.weight(1f),
            )
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(todo.todos, key = { it.id }) { entry -> TodoRow(entry) }
        }
    }
}

@Composable
private fun TodoRow(entry: TodoEntry) {
    val tint = when {
        entry.isDone -> Color(0xFF4CAF50)
        entry.isActive -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val icon = when {
        entry.isDone -> Icons.Default.Check
        entry.isActive -> Icons.Default.Timelapse
        else -> Icons.Default.RadioButtonUnchecked
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 7.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            entry.text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (entry.isDone) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

@Composable
private fun ChangesTab(changes: WorkspaceChanges?) {
    if (changes == null) {
        EmptyHint("拿不到仓库状态")
        return
    }
    if (!changes.repo) {
        EmptyHint("这个项目不是 git 仓库")
        return
    }
    if (changes.isEmpty) {
        EmptyHint("工作区干净，没有未提交的改动")
        return
    }
    Column(modifier = Modifier.fillMaxSize()) {
        changes.branch?.let { branch ->
            Text(
                "分支 $branch · ${changes.files.size} 个文件",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(changes.files, key = { it.group + it.path }) { file ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        file.letter,
                        style = MaterialTheme.typography.labelLarge,
                        fontFamily = FontFamily.Monospace,
                        color = letterColor(file.letter),
                        modifier = Modifier.width(20.dp),
                    )
                    Text(
                        file.path,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun letterColor(letter: String): Color = when (letter) {
    "M" -> Color(0xFFE0A030)
    "A", "?" -> Color(0xFF4CAF50)
    "D" -> Color(0xFFE05252)
    "U" -> Color(0xFF8B8B8B)
    else -> Color(0xFF7C9CE0)
}

@Composable
private fun FilesTab(viewModel: ChatViewModel) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                enabled = viewModel.workspaceDir.isNotEmpty(),
                onClick = { viewModel.openParentDir() },
            ) { Text("..") }
            Text(
                "/" + viewModel.workspaceDir,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(viewModel.workspaceFiles, key = { it.path + it.isDir }) { node ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (node.isDir) {
                                viewModel.openDir(node.path)
                            } else {
                                viewModel.openFile(node.path)
                            }
                        }
                        .padding(horizontal = 20.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (node.isDir) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        node.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 只读文件预览。桌面上万行的文件在手机上不滚动会卡，先截到 4000 行。 */
@Composable
fun FilePreviewDialog(viewModel: ChatViewModel) {
    val path = viewModel.previewPath ?: return
    AlertDialog(
        onDismissRequest = { viewModel.closePreview() },
        title = {
            Text(
                path.substringAfterLast('/'),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp),
            ) {
                if (viewModel.previewBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp).align(Alignment.Center),
                        strokeWidth = 2.dp,
                    )
                } else {
                    val lines = remember(viewModel.previewText) {
                        viewModel.previewText.lineSequence().take(4000).joinToString("\n")
                    }
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState()),
                    ) {
                        Text(
                            lines,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.closePreview() }) { Text("关闭") }
        },
    )
}

@Suppress("unused")
private val previewShape = RoundedCornerShape(12.dp)

@Suppress("unused")
private val previewSpacing = Arrangement.spacedBy(6.dp)
