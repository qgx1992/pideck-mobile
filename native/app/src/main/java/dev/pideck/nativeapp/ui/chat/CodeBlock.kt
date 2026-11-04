package dev.pideck.nativeapp.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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
 * 代码块渲染：普通代码走轻量着色，`diff` / `patch` 走整行底色。
 *
 * 这两个形态覆盖面最大 —— agent 的回复里要么是完整文件片段，要么是改动对比；
 * 其它形式（表格、树状图）交给等宽字体已经够用。
 */
@Composable
fun CodeBlock(lang: String?, code: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val palette = remember(scheme.background.luminance()) {
        val dark = scheme.background.luminance() < 0.5f
        CodePalette(
            keyword = scheme.primary,
            type = scheme.secondary,
            string = if (dark) Color(0xFF9CCC9C) else Color(0xFF3F8F4F),
            number = if (dark) Color(0xFFE0A86A) else Color(0xFF9A5B14),
            comment = scheme.onSurfaceVariant.copy(alpha = 0.9f),
            plain = scheme.onSurface,
        )
    }
    val lineCount = remember(code) { code.count { it == '\n' } + 1 }

    Surface(
        color = scheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            Text(
                text = buildString {
                    append(lang?.takeIf { it.isNotBlank() } ?: "code")
                    append("  ·  ")
                    append(lineCount)
                    append(" 行")
                },
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant.copy(alpha = 0.8f),
                modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp),
            )
            if (CodeHighlight.isDiff(lang)) {
                DiffBody(code, modifier = Modifier.padding(top = 6.dp, bottom = 10.dp))
            } else {
                val annotated = remember(code, lang, palette) { highlight(code, lang, palette) }
                Text(
                    text = annotated,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 18.sp,
                    ),
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 12.dp, top = 6.dp, end = 12.dp, bottom = 10.dp),
                )
            }
        }
    }
}

private data class CodePalette(
    val keyword: Color,
    val type: Color,
    val string: Color,
    val number: Color,
    val comment: Color,
    val plain: Color,
)

private fun highlight(code: String, lang: String?, palette: CodePalette): AnnotatedString =
    buildAnnotatedString {
        val tokens = CodeHighlight.tokenize(code, lang)
        var cursor = 0
        for (token in tokens) {
            if (token.start > cursor) append(code.substring(cursor, token.start))
            val style = when (token.kind) {
                TokenKind.Keyword -> SpanStyle(color = palette.keyword, fontWeight = FontWeight.Medium)
                TokenKind.Type -> SpanStyle(color = palette.type)
                TokenKind.String -> SpanStyle(color = palette.string)
                TokenKind.Number -> SpanStyle(color = palette.number)
                TokenKind.Comment -> SpanStyle(color = palette.comment, fontStyle = FontStyle.Italic)
                TokenKind.Plain -> SpanStyle(color = palette.plain)
            }
            withStyle(style) { append(code.substring(token.start, token.end)) }
            cursor = token.end
        }
        if (cursor < code.length) append(code.substring(cursor))
    }

/**
 * diff 渲染：`+` 绿 / `-` 红 / `@@` 蓝的左侧竖条 + 浅底色，其余行保持原样。
 *
 * 为什么用竖条而不是整行底色：横向滚动时底色跟着内容跑，长行会显得很脏；竖条是固定宽度的
 * 视觉锚点，滚到哪都还在。超过 400 行只渲染前 400 行（工具输出里的 diff 动辄几千行，
 * Compose 的 Text 撑不住那么长的行列表）。
 */
@Composable
private fun DiffBody(code: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val lines = remember(code) {
        val all = code.split('\n')
        if (all.size > 400) all.take(400) + listOf("… 还有 ${all.size - 400} 行未显示") else all
    }
    val added = if (scheme.background.luminance() < 0.5f) Color(0x1F4CAF50) else Color(0x1A2E7D32)
    val removed = if (scheme.background.luminance() < 0.5f) Color(0x1FEF5350) else Color(0x1AD32F2F)
    val hunk = if (scheme.background.luminance() < 0.5f) Color(0x1F64B5F6) else Color(0x1A1565C0)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        lines.forEach { line ->
            val (bar, tint) = when {
                line.startsWith("+++") || line.startsWith("---") -> scheme.onSurfaceVariant to Color.Transparent
                line.startsWith("+") -> Color(0xFF4CAF50) to added
                line.startsWith("-") -> Color(0xFFE05252) to removed
                line.startsWith("@@") -> scheme.primary to hunk
                else -> Color.Transparent to Color.Transparent
            }
            Row(modifier = Modifier.background(tint)) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .heightIn(min = 18.dp)
                        .fillMaxHeight()
                        .background(bar),
                )
                Text(
                    text = line.ifEmpty { " " },
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 18.sp,
                        fontStyle = when {
                            line.startsWith("@@") -> FontStyle.Italic
                            else -> FontStyle.Normal
                        },
                    ),
                    color = when {
                        line.startsWith("@@") -> scheme.primary
                        else -> scheme.onSurface
                    },
                    modifier = Modifier.padding(start = 6.dp, end = 8.dp, top = 1.dp, bottom = 1.dp),
                )
            }
        }
    }
}
