package dev.pideck.nativeapp.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 全 App 共用的几种控件。
 *
 * 起因是「按钮太丑」：Material3 的默认 Button/FilledTonalButton 是 40dp 高、4~8dp 圆角、
 * 左右内边距 24dp，在手机上看着又矮又方；顶栏又一律是裸 IconButton（40dp 透明热区，看不见边界）。
 * 这里把两件事定死：**胶囊形按钮（46dp，圆角 50%）** 与 **圆形底色的图标按钮（38dp）**。
 */
enum class PillStyle { Filled, Tonal, Ghost }

@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    style: PillStyle = PillStyle.Filled,
    fillWidth: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val container = when (style) {
        PillStyle.Filled -> if (enabled) scheme.primary else scheme.surfaceVariant
        PillStyle.Tonal -> if (enabled) scheme.surfaceContainerHigh else scheme.surfaceVariant.copy(alpha = 0.6f)
        PillStyle.Ghost -> Color.Transparent
    }
    val content = when (style) {
        PillStyle.Filled -> if (enabled) scheme.onPrimary else scheme.onSurfaceVariant
        PillStyle.Tonal -> if (enabled) scheme.onSurface else scheme.onSurfaceVariant
        PillStyle.Ghost -> if (enabled) scheme.primary else scheme.onSurfaceVariant
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        color = container,
        contentColor = content,
        border = if (style == PillStyle.Ghost) {
            BorderStroke(1.dp, scheme.outline.copy(alpha = if (enabled) 0.7f else 0.3f))
        } else {
            null
        },
        modifier = if (fillWidth) modifier.fillMaxWidth() else modifier,
    ) {
        Row(
            modifier = Modifier
                .defaultMinSize(minHeight = 46.dp)
                .padding(horizontal = 22.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    text,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp),
                )
            } else {
                Text(
                    text,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 顶栏用的圆形图标按钮：有底色、有边界，不再是看不见的透明热区。 */
@Composable
fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Int = 38,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = container,
        modifier = modifier.size(size.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = if (enabled) tint else tint.copy(alpha = 0.4f),
                modifier = Modifier.size((size * 0.5f).dp),
            )
        }
    }
}

/** 顶栏/输入条上的小胶囊（模型、档位这类可点开的选择项）。 */
@Composable
fun SelectChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    trailing: ImageVector? = null,
    enabled: Boolean = true,
    accent: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        color = if (accent) scheme.primary.copy(alpha = 0.14f) else scheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (accent) scheme.primary else scheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                color = if (accent) scheme.primary else scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = if (icon != null) 6.dp else 0.dp)
                    .padding(end = if (trailing != null) 2.dp else 0.dp),
            )
            if (trailing != null) {
                Icon(
                    trailing,
                    contentDescription = null,
                    tint = if (accent) scheme.primary else scheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = 2.dp)
                        .size(14.dp),
                )
            }
        }
    }
}

