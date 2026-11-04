package dev.pideck.nativeapp.ui.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 极简 Markdown：只认 agent 回复里真正常出现的形状 —— 围栏代码块、标题、列表、引用、分隔线，
 * 行内加粗 / 斜体 / 行内代码。表格、脚注、HTML 一律按普通文本渲染（不做半吊子解析）。
 */
sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock

    data class Code(val lang: String?, val code: String) : MdBlock

    data class Heading(val level: Int, val text: String) : MdBlock

    data class ListBlock(val ordered: Boolean, val items: List<String>) : MdBlock

    data class Quote(val text: String) : MdBlock

    data object Rule : MdBlock
}

private val fence = Regex("^\\s*```\\s*([\\w+#.-]*)\\s*$")
private val heading = Regex("^(#{1,6})\\s+(.*)$")
private val bullet = Regex("^\\s*[-*+]\\s+(.*)$")
private val ordered = Regex("^\\s*\\d+[.)]\\s+(.*)$")
private val quote = Regex("^\\s*>\\s?(.*)$")
private val rule = Regex("^\\s*([-*_])\\1{2,}\\s*$")

fun parseMarkdown(source: String): List<MdBlock> {
    if (source.isBlank()) return emptyList()
    val blocks = mutableListOf<MdBlock>()
    val paragraph = StringBuilder()
    var codeLang: String? = null
    val code = StringBuilder()
    var inCode = false

    fun flushParagraph() {
        if (paragraph.isNotBlank()) blocks += MdBlock.Paragraph(paragraph.toString().trim())
        paragraph.setLength(0)
    }

    fun flushCode() {
        blocks += MdBlock.Code(codeLang, code.toString().trimEnd('\n'))
        code.setLength(0)
        codeLang = null
    }

    val lines = source.split('\n')
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        index += 1

        if (inCode) {
            if (fence.matches(line)) {
                inCode = false
                flushCode()
            } else {
                code.append(line).append('\n')
            }
            continue
        }

        val fenceMatch = fence.find(line)
        if (fenceMatch != null) {
            flushParagraph()
            inCode = true
            codeLang = fenceMatch.groupValues[1].ifBlank { null }
            continue
        }

        when {
            line.isBlank() -> flushParagraph()

            rule.matches(line) -> {
                flushParagraph()
                blocks += MdBlock.Rule
            }

            heading.matches(line) -> {
                flushParagraph()
                val match = heading.find(line)!!
                blocks += MdBlock.Heading(match.groupValues[1].length, match.groupValues[2].trim())
            }

            bullet.matches(line) || ordered.matches(line) -> {
                flushParagraph()
                val isOrdered = ordered.matches(line)
                val items = mutableListOf<String>()
                var cursor = index - 1
                while (cursor < lines.size) {
                    val candidate = lines[cursor]
                    val match = (if (isOrdered) ordered else bullet).find(candidate)
                    if (match != null) {
                        items += match.groupValues[1].trim()
                        cursor += 1
                    } else if (candidate.isBlank()) {
                        break
                    } else if (candidate.startsWith("  ")) {
                        // 续行：并到上一条
                        items[items.lastIndex] = items.last() + " " + candidate.trim()
                        cursor += 1
                    } else {
                        break
                    }
                }
                index = cursor
                blocks += MdBlock.ListBlock(isOrdered, items)
            }

            quote.matches(line) -> {
                flushParagraph()
                val collected = mutableListOf<String>()
                var cursor = index - 1
                while (cursor < lines.size) {
                    val match = quote.find(lines[cursor])
                    if (match != null) {
                        collected += match.groupValues[1]
                        cursor += 1
                    } else {
                        break
                    }
                }
                index = cursor
                blocks += MdBlock.Quote(collected.joinToString("\n").trim())
            }

            else -> {
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(line)
            }
        }
    }

    if (inCode) {
        // 流式过程中代码块还没闭合：照样按代码块显示，别把 ``` 露给用户。
        inCode = false
        flushCode()
    }
    flushParagraph()
    return blocks
}

/** 行内：`**粗**`、`*斜*`、`` `码` ``。算不出来的原样保留。 */
fun inlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var index = 0
    while (index < text.length) {
        val char = text[index]
        when {
            char == '`' -> {
                val end = text.indexOf('`', index + 1)
                if (end > index) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = androidx.compose.ui.graphics.Color(0x223C8DFF))) {
                        append(text.substring(index + 1, end))
                    }
                    index = end + 1
                } else {
                    append(char)
                    index += 1
                }
            }

            text.startsWith("**", index) -> {
                val end = text.indexOf("**", index + 2)
                if (end > index) {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                        append(text.substring(index + 2, end))
                    }
                    index = end + 2
                } else {
                    append("**")
                    index += 2
                }
            }

            char == '*' -> {
                val end = text.indexOf('*', index + 1)
                if (end > index + 1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(text.substring(index + 1, end))
                    }
                    index = end + 1
                } else {
                    append(char)
                    index += 1
                }
            }

            else -> {
                append(char)
                index += 1
            }
        }
    }
}

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
) {
    val blocks = remember(markdown) { parseMarkdown(markdown) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Paragraph -> Text(
                    text = inlineMarkdown(block.text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                is MdBlock.Heading -> Text(
                    text = inlineMarkdown(block.text),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                is MdBlock.Code -> CodeBlock(lang = block.lang, code = block.code)

                is MdBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    block.items.forEachIndexed { position, item ->
                        Row {
                            Text(
                                text = if (block.ordered) "${position + 1}." else "•",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 6.dp),
                            )
                            Text(
                                text = inlineMarkdown(item),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }

                is MdBlock.Quote -> Row(verticalAlignment = Alignment.Top) {
                    Surface(
                        color = MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(1.dp),
                        modifier = Modifier
                            .padding(end = 10.dp)
                            .width(3.dp)
                            .heightIn(min = 18.dp),
                    ) {}
                    Text(
                        text = inlineMarkdown(block.text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                MdBlock.Rule -> HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    thickness = 1.dp,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
        }
        if (streaming) {
            Text(
                text = "▍",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
