package dev.pideck.nativeapp.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.pideck.nativeapp.data.TranslationState
import dev.pideck.nativeapp.ui.theme.AppButton
import dev.pideck.nativeapp.ui.theme.AppButtonStyle
import dev.pideck.nativeapp.ui.theme.AppIconButton

/**
 * 点开「思考」「工具」卡时那块浮窗的内容：标题、一行提示、正文（可选等宽）、字数。
 */
data class DetailContent(
    val title: String,
    val hint: String,
    val text: String,
    val mono: Boolean = false,
    /** 思考过程这类「读得想让人翻一下」的正文才给「翻译」按钮，工具卡不给。 */
    val translatable: Boolean = false,
)

/**
 * 「自由复制」：把整段正文摊开，交给系统选区。
 *
 * 为什么不是自己画一个可拖选的文本：中文长段落里按字符拖选，手感和系统的
 * `SelectionContainer`（带放大镜、两端手柄、跨行扩展）差得远。这里只负责
 * 「给你一块可以随便选的地方」，选中后走系统的复制菜单；不想选就直接按「复制全文」。
 */
@Composable
fun FreeCopyDialog(
    title: String = "自由复制",
    text: String,
    onCopyAll: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    DetailDialog(
        content = DetailContent(
            title = title,
            hint = "长按选中任意一段，用系统的复制菜单；也可以直接复制全文",
            text = text,
        ),
        onCopyAll = onCopyAll,
        onDismiss = onDismiss,
    )
}

/**
 * 通用的「摊开一段文本」浮窗：思考过程、工具入参/结果都走这一个壳。
 *
 * 与「自由复制」同一套外观（标题 + 提示 + 可滚动选区 + 底部字数与按钮），
 * 这样用户在聊天页点任何一张卡，看到的都是同一块窗口，不用学第二套。
 */
@Composable
fun DetailDialog(
    content: DetailContent,
    onCopyAll: (String) -> Unit,
    onDismiss: () -> Unit,
    translation: TranslationState? = null,
    onTranslate: (() -> Unit)? = null,
    onFlipTranslation: (() -> Unit)? = null,
    onCopyTranslation: (String) -> Unit = {},
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.82f),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 0.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 10.dp, top = 12.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(content.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            content.hint,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    AppIconButton(
                        icon = Icons.Default.Close,
                        contentDescription = "关闭",
                        onClick = onDismiss,
                        size = 34,
                        container = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                ) {
                    SelectionContainer {
                        Text(
                            text = content.text,
                            style = if (content.mono) {
                                MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 18.sp,
                                )
                            } else {
                                MaterialTheme.typography.bodyMedium
                            },
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (translation != null) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "译文 · ${translation.target.label}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            if (!translation.busy && onFlipTranslation != null) {
                                TextButton(onClick = onFlipTranslation) {
                                    Text(
                                        "改译成${translation.target.flipped().label}",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                            if (!translation.busy && translation.text.isNotBlank()) {
                                TextButton(onClick = { onCopyTranslation(translation.text) }) {
                                    Text("复制译文", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        when {
                            translation.busy -> Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "正在翻…（用的是宿主上那个 agent，和你平时的模型同一个）",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            translation.error != null -> Text(
                                text = translation.error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )

                            translation.text.isNotBlank() -> SelectionContainer {
                                Text(
                                    text = translation.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${content.text.length} 字",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (onTranslate != null) {
                        TextButton(onClick = onTranslate, enabled = translation?.busy != true) {
                            Text(
                                text = when {
                                    translation == null -> "翻译"
                                    translation.busy -> "翻译中…"
                                    else -> "重新翻译"
                                },
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                    Spacer(Modifier.width(6.dp))
                    AppButton(
                        text = "复制全文",
                        onClick = { onCopyAll(content.text) },
                        style = AppButtonStyle.Filled,
                    )
                }
            }
        }
    }
}
