package dev.pideck.nativeapp.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pideck.nativeapp.data.AgentBackend
import dev.pideck.nativeapp.data.Session
import dev.pideck.nativeapp.ui.theme.BackendBadge

/**
 * 会话行里「标题 + 一行元信息 + 后端标记」这一段，四处共用。
 *
 * 为什么要抽出来：会话行有**四**份布局（全部会话 / 首页「最近的会话」/ 抽屉·聊天 / 抽屉·活动），
 * 每次加行级元素都只改到其中一两处 —— 后端标记就这样漏了两次（0.5.26 补列表页、
 * 0.5.27 补首页、0.5.28 补齐抽屉，0.5.41 活动段还是没带）。真正的修法是让四处共用同一段文字块，
 * 而不是靠记性去四边对表；外壳（缩进、圆角、点击手势、右侧时间）各留各的。
 *
 * @param meta 元信息那一行（相对时间，必要时带项目名 / 模型 id）
 * @param metaColor 抽屉里用 `outline`（更淡），列表页用 `onSurfaceVariant`
 */
@Composable
internal fun SessionRowText(
    session: Session,
    meta: String,
    acpToolName: String? = null,
    titleStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    metaStyle: TextStyle = MaterialTheme.typography.labelSmall,
    metaColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    titleMaxLines: Int = 1,
    verticalPadding: Int = 2,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.Center) {
        Text(
            text = session.title.ifBlank { "未命名会话" },
            style = titleStyle,
            maxLines = titleMaxLines,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.padding(top = verticalPadding.dp))
        SessionRowMeta(
            meta = meta,
            acpToolName = acpToolName,
            backend = session.backend,
            metaStyle = metaStyle,
            metaColor = metaColor,
        )
    }
}

/** 只有元信息行（给右侧时间那种「时间放右边」的行布局用）。 */
@Composable
internal fun SessionRowMeta(
    meta: String,
    backend: String?,
    acpToolName: String? = null,
    metaStyle: TextStyle = MaterialTheme.typography.labelSmall,
    metaColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = meta,
            style = metaStyle,
            color = metaColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        // 后端标记：同名会话并列时靠它区分（桌面端库里 dsh / acp / pi 混着放）。
        AgentBackend.badgeOf(backend, acpToolName)?.let { label ->
            Spacer(Modifier.width(6.dp))
            BackendBadge(
                label,
                muted = backend.isNullOrBlank() || backend == "pi",
            )
        }
    }
}
