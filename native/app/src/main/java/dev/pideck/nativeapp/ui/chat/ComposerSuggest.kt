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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pideck.nativeapp.data.FileIndex
import dev.pideck.nativeapp.data.FileNode
import dev.pideck.nativeapp.data.HostCommand
import dev.pideck.nativeapp.ui.theme.ControlShapeSmall

/** 输入框里正在输入的那种「半截引用」。 */
sealed interface ComposerTrigger {
    /** `@` 后面跟的半截路径。 */
    data class Mention(val query: String, val start: Int) : ComposerTrigger

    /** 行首的 `/` 命令。 */
    data class Slash(val query: String, val start: Int) : ComposerTrigger
}

/** 一条斜杠命令。只列「客户端接管」的：其余 `/xxx` 直接发给 pi，由它的模板/扩展展开。 */
data class SlashCommand(val id: String, val label: String, val hint: String)

val BUILTIN_SLASH_COMMANDS: List<SlashCommand> = listOf(
    SlashCommand("new", "/new", "开一个新会话（回首页选工作区）"),
    SlashCommand("compact", "/compact", "压缩这个会话的上下文，可带一句「保留什么」"),
)

/**
 * 候选列表里的一行。`insert` 是插进输入框的文本（不含斜杠），`builtin` 非空表示由客户端自己执行。
 *
 * 宿主命令（扩展 / 提示词模板 / 技能）来自 `runtime/commands`，名字要原样插 ——
 * 技能必须是 `skill:名称`，裸 `/名称` pi 会当未知命令拒掉。
 */
data class CommandEntry(
    val insert: String,
    val label: String,
    val hint: String,
    val badge: String?,
    val builtin: String? = null,
)

/** 客户端接管的命令 + 宿主命令（扩展 / 模板 / 技能），按查询过滤。 */
fun commandEntries(
    hostCommands: List<HostCommand>,
    query: String,
    limit: Int = 12,
): List<CommandEntry> {
    val needle = query.trim().lowercase()
    val builtins = BUILTIN_SLASH_COMMANDS.map {
        CommandEntry(
            insert = it.id,
            label = it.label,
            hint = it.hint,
            badge = "客户端",
            builtin = it.id,
        )
    }
    val hosted = hostCommands.map {
        CommandEntry(
            insert = it.name,
            label = "/" + it.displayName,
            hint = it.description,
            badge = it.sourceLabel,
        )
    }
    val all = builtins + hosted
    if (needle.isEmpty()) return all.take(limit)
    val (prefix, rest) = all.partition {
        it.label.lowercase().substring(1).startsWith(needle) ||
            it.insert.lowercase().startsWith(needle)
    }
    return (prefix + rest.filter {
        it.label.lowercase().contains(needle) || it.hint.lowercase().contains(needle)
    }).take(limit)
}

/**
 * 从输入框内容推断要不要弹候选。
 *
 * 只看光标前（这里光标永远在末尾，[text] 就是全文）。`@` 必须在行首或空白之后，
 * 后面不能有空格 —— 否则「邮件 a@b.com」「第 3 行 @ 某人」这类正文会被误当成引用。
 */
fun detectTrigger(text: String): ComposerTrigger? {
    if (text.isEmpty()) return null
    if (text.startsWith("/")) {
        val rest = text.substring(1)
        if (rest.any { it.isWhitespace() }) return null
        return ComposerTrigger.Slash(rest, 0)
    }
    val at = text.lastIndexOf('@')
    if (at < 0) return null
    if (at > 0 && !text[at - 1].isWhitespace()) return null
    val query = text.substring(at + 1)
    if (query.any { it.isWhitespace() } || query.contains('@')) return null
    if (query.length > 60) return null
    return ComposerTrigger.Mention(query, at)
}

/**
 * 把路径写成输入框里的 `@` 引用，规则与桌面端 `formatFilePathRef` 一致：
 * 目录必须带尾斜杠（裸 `@src` 会被当成「@ 某人」，模型也不认）；含空格或引号要用 `@"…"`。
 */
fun mentionRef(path: String, isDirectory: Boolean): String {
    var normalized = path.trimEnd('/', '\\')
    if (normalized.isEmpty()) return "@"
    if (isDirectory) normalized = "$normalized/"
    val needsQuote = normalized.any { it.isWhitespace() || it == '"' }
    return if (needsQuote) "@\"" + normalized.replace("\"", "\\\"") + "\"" else "@$normalized"
}

/** 选中一条路径后替换掉那半截 `@xxx`。 */
fun applyMention(text: String, trigger: ComposerTrigger.Mention, node: FileNode): String =
    text.take(trigger.start) + mentionRef(node.path, node.isDir) + " "

/** 选中一条命令后替换掉那半截 `/xxx`（命令本身留给用户按发送）。 */
fun applySlash(text: String, trigger: ComposerTrigger.Slash, command: String): String =
    text.take(trigger.start) + "/" + command + " "

/**
 * 整条文本是不是一条「客户端接管」的斜杠命令。
 *
 * 与桌面端 `app/vendor/pideck/src/renderer/src/utils/composerSlashCommand.ts` 同口径：
 * 名字后面必须跟空白或直接结束，`/newer` 这种正文不会被吞。
 */
fun slashIntent(text: String): SlashCommand? {
    val trimmed = text.trim()
    val name = trimmed.substringBefore(' ')
    return BUILTIN_SLASH_COMMANDS.firstOrNull { trimmed == "/${it.id}" || trimmed.startsWith("/${it.id} ") }
        ?.takeIf { name == "/${it.id}" }
}

/** 发给服务端之前把命令后面的参数剥出来（`/compact 只留结论` → `只留结论`）。 */
fun slashArgument(text: String, command: SlashCommand): String =
    text.trim().removePrefix("/${command.id}").trim()

/** 候选条：`@` 给文件、`/` 给命令。点一条就把输入框里的半截替换掉。 */
@Composable
fun ComposerSuggest(
    files: List<FileNode>,
    commands: List<CommandEntry>,
    loading: Boolean = false,
    onPickFile: (FileNode) -> Unit,
    onPickCommand: (CommandEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows: Int = if (commands.isNotEmpty()) commands.size else files.size
    if (rows == 0) {
        if (loading) {
            Text(
                text = "正在扫工作区…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
        return
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = ControlShapeSmall,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 208.dp),
    ) {
        LazyColumn(modifier = Modifier.padding(vertical = 4.dp)) {
            if (commands.isNotEmpty()) {
                items(commands, key = { it.insert }) { command ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(ControlShapeSmall)
                            .clickable { onPickCommand(command) }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            command.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            command.hint,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        command.badge?.let { badge ->
                            Spacer(Modifier.width(8.dp))
                            Text(
                                badge,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            } else {
                items(files, key = { it.path }) { node ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(ControlShapeSmall)
                            .clickable { onPickFile(node) }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (node.isDir) Icons.Default.Folder else Icons.Default.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                node.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (node.path != node.name) {
                                Text(
                                    node.path.substringBeforeLast(node.name, ""),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(6.dp))
}

/** 过滤：精确 > 前缀 > 包含 > 路径包含 > 模糊子序列；目录优先，最多 [FileIndex.MAX_SUGGESTIONS] 条。 */
fun filterMentionFiles(files: List<FileNode>, query: String): List<FileNode> =
    FileIndex.rank(files, query)
