package dev.pideck.nativeapp.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 极简 Markdown：只认 agent 回复里真正常出现的形状 —— 围栏代码块、标题、列表、引用、分隔线、
 * 表格（GFM 的 `| a | b |` + `|---|:--:|`），行内加粗 / 斜体 / 行内代码。
 * 脚注、HTML 一律按普通文本渲染（不做半吊子解析）。
 */
sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock

    data class Code(val lang: String?, val code: String) : MdBlock

    data class Heading(val level: Int, val text: String) : MdBlock

    data class ListBlock(val ordered: Boolean, val items: List<String>) : MdBlock

    data class Quote(val text: String) : MdBlock

    data object Rule : MdBlock

    /**
     * GFM 表格。对齐来自分隔行（`:--` 左、`:--:` 中、`--:` 右），列数固定为表头的列数。
     *
     * 之前没有这个块，表格会掉进「段落」里：`| 列 | 列 |` 与 `|---|` 原样显示成一堆竖线，
     * 单元格还会被折行拼成一坨 —— 用户报的「表格显示有点问题」就是这个。
     */
    data class Table(
        val headers: List<String>,
        val aligns: List<TableCellAlign> = headers.map { TableCellAlign.LEFT },
        val rows: List<List<String>> = emptyList(),
        /**
         * 表头是不是空的。GFM 允许 `| | |` 这种无表头的表（常见于模型写的「键 | 值」对照），
         * 这种表不画表头行、也不给它铺底色，直接从数据行开始 —— 否则屏上会出现一条
         * 高度只够背景色的空白带，看起来像表格「顶破了」。
         */
        val showHeader: Boolean = true,
    ) : MdBlock
}

enum class TableCellAlign { LEFT, CENTER, RIGHT }

private val fence = Regex("^\\s*```\\s*([\\w+#.-]*)\\s*$")
private val heading = Regex("^(#{1,6})\\s+(.*)$")
private val bullet = Regex("^\\s*[-*+]\\s+(.*)$")
private val ordered = Regex("^\\s*\\d+[.)]\\s+(.*)$")
private val quote = Regex("^\\s*>\\s?(.*)$")
private val rule = Regex("^\\s*([-*_])\\1{2,}\\s*$")

private val delimiterCell = Regex("^:?-{1,}:?$")

/**
 * 拆一行表格：去掉首尾的竖线、按未转义的 `|` 切单元格。
 *
 * `\|` 是单元格里的字面竖线（模型写 `a \| b` 时不要当成列分隔）。
 */
internal fun splitTableRow(line: String): List<String> {
    var text = line.trim()
    if (text.startsWith("|")) text = text.substring(1)
    if (text.endsWith("|") && !text.endsWith("\\|")) text = text.dropLast(1)
    val cells = mutableListOf<String>()
    val buffer = StringBuilder()
    var index = 0
    while (index < text.length) {
        val char = text[index]
        if (char == '\\' && index + 1 < text.length && text[index + 1] == '|') {
            buffer.append('|')
            index += 2
            continue
        }
        if (char == '|') {
            cells += buffer.toString().trim()
            buffer.setLength(0)
            index += 1
            continue
        }
        buffer.append(char)
        index += 1
    }
    cells += buffer.toString().trim()
    return cells
}

private fun alignOf(delimiter: String): TableCellAlign = when {
    delimiter.startsWith(":") && delimiter.endsWith(":") -> TableCellAlign.CENTER
    delimiter.endsWith(":") -> TableCellAlign.RIGHT
    else -> TableCellAlign.LEFT
}

/**
 * 读一张表：表头在第 [headerIndex] 行，分隔行在下一行。
 *
 * 返回表格块与「表格之后的第一行」下标；不像表格就返回 null（交回普通段落处理）。
 * 行尾多出来的单元格截掉、少了的补空 —— 模型手写表格经常一列多一列少，
 * 为这个整块不认，用户看到的就是一堆竖线，不如按最大列数对齐着显示。
 */
internal fun readTable(lines: List<String>, headerIndex: Int): Pair<MdBlock.Table, Int>? {
    if (headerIndex + 1 >= lines.size) return null
    val headerLine = lines[headerIndex]
    if (!headerLine.contains('|')) return null
    val headers = splitTableRow(headerLine)
    // 至少要能分成两列；表头全空是合法的（后面按「无表头的表」渲染），这里不能因此不认。
    if (headers.size < 2) return null
    val delimiters = splitTableRow(lines[headerIndex + 1])
    if (delimiters.size != headers.size) return null
    if (!delimiters.all { delimiterCell.matches(it) }) return null

    val rows = mutableListOf<List<String>>()
    var cursor = headerIndex + 2
    while (cursor < lines.size) {
        val line = lines[cursor]
        if (line.isBlank() || !line.contains('|')) break
        if (fence.matches(line)) break
        val cells = splitTableRow(line)
        rows += List(headers.size) { position -> cells.getOrElse(position) { "" } }
        cursor += 1
    }
    // 只有分隔行、没有任何数据行的「表」当段落处理（否则屏上就剩一个空盒子）。
    if (rows.isEmpty()) return null
    return MdBlock.Table(
        headers = headers,
        aligns = delimiters.map(::alignOf),
        rows = rows,
        showHeader = headers.any { it.isNotBlank() },
    ) to cursor
}

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

        // 表格要先于其它块判：`| a | b |` 这一行本身看着就像普通段落，
        // 判据是「下一行是分隔行且列数一致」（GFM 的定义），只有这时才当表格。
        if (line.isNotBlank() && line.contains('|')) {
            val table = readTable(lines, index - 1)
            if (table != null) {
                flushParagraph()
                blocks += table.first
                index = table.second
                continue
            }
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

/**
 * 解析结果的 LRU 缓存。
 *
 * 为什么需要：`remember` 只在同一个组合实例内有效，长会话往上滚两屏再滚回来，所有消息都会
 * 重新解析一遍；几万字的消息里这明显能感觉到卡顿。缓存按「整段 markdown」做键，命中率很高
 * （历史消息不会变）。
 */
internal object MarkdownCache {
    private const val CAPACITY = 32

    private val entries = object : LinkedHashMap<String, List<MdBlock>>(CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<MdBlock>>?): Boolean =
            size > CAPACITY
    }

    fun parse(markdown: String): List<MdBlock> = synchronized(entries) {
        entries[markdown] ?: parseMarkdown(markdown).also { entries[markdown] = it }
    }
}

/** 行内样式的解析结果跟着文本走，避免每帧重算一次 AnnotatedString。 */
@Composable
private fun InlineText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val annotated = remember(text) { inlineMarkdown(text) }
    Text(text = annotated, style = style, color = color, modifier = modifier)
}

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
) {
    // 流式那一份每帧都在变，进缓存只会把缓存挤空；收尾后的长消息则反复出现在滚动里，值得留住。
    val blocks = remember(markdown, streaming) {
        if (streaming) parseMarkdown(markdown) else MarkdownCache.parse(markdown)
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Paragraph -> InlineText(
                    text = block.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                is MdBlock.Heading -> InlineText(
                    text = block.text,
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    }.copy(fontWeight = FontWeight.SemiBold),
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
                            InlineText(
                                text = item,
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
                    InlineText(
                        text = block.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is MdBlock.Table -> MdTable(block)

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

/**
 * 表格：表头底色 + 网格线，列数 ≤ 3 时按等宽撑满屏宽，再多就横向滚动（每列 150dp）。
 *
 * 单元格里的文本仍走行内 markdown（`**粗**` / 反引号），所以 `| \`code\` |` 是能正常显示的。
 * 用 IntrinsicSize.Min + fillMaxHeight 是为了让竖线画到整行高度 —— Row 里没有固定高度时，
 * 直接 fillMaxHeight 会撑成 0 高度，竖线就整条消失。
 */
@Composable
private fun MdTable(block: MdBlock.Table, modifier: Modifier = Modifier) {
    val scrollable = block.headers.size >= 4
    val scroll = rememberScrollState()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = if (scrollable) Modifier.horizontalScroll(scroll) else Modifier) {
            if (block.showHeader) {
                TableRow(block.headers, block.aligns, header = true, scrollable = scrollable)
            }
            block.rows.forEachIndexed { index, row ->
                if (block.showHeader || index > 0) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        thickness = 1.dp,
                    )
                }
                TableRow(row, block.aligns, header = false, scrollable = scrollable)
            }
        }
    }
}

@Composable
private fun TableRow(
    cells: List<String>,
    aligns: List<TableCellAlign>,
    header: Boolean,
    scrollable: Boolean,
) {
    Row(
        modifier = Modifier
            .height(IntrinsicSize.Min)
            .then(
                if (header) {
                    Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                } else {
                    Modifier
                },
            ),
    ) {
        cells.forEachIndexed { index, cell ->
            if (index > 0) {
                VerticalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.fillMaxHeight(),
                )
            }
            TableCell(
                text = cell,
                align = aligns.getOrElse(index) { TableCellAlign.LEFT },
                header = header,
                cellModifier = if (scrollable) Modifier.width(150.dp) else Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TableCell(
    text: String,
    align: TableCellAlign,
    header: Boolean,
    cellModifier: Modifier,
) {
    InlineText(
        text = text,
        style = (if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall).copy(
            textAlign = when (align) {
                TableCellAlign.LEFT -> TextAlign.Start
                TableCellAlign.CENTER -> TextAlign.Center
                TableCellAlign.RIGHT -> TextAlign.End
            },
            fontWeight = if (header) FontWeight.SemiBold else null,
        ),
        color = if (header) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        modifier = cellModifier.padding(horizontal = 10.dp, vertical = 8.dp),
    )
}
