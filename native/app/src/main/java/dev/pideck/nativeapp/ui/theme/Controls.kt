package dev.pideck.nativeapp.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 全 App 共用的几种控件。
 *
 * 形状统一成**矩形**（12dp 圆角）：胶囊（圆角 50%）在手机上一排排出现时太"软"，
 * 与卡片、输入框的直角体系也不搭。所有按钮 / 图标按钮 / 选择项都从下面这几个入口出发，
 * 要调整风格只改这里的 [ControlShape] / [ControlShapeSmall]。
 */
internal val ControlShape = RoundedCornerShape(12.dp)
internal val ControlShapeSmall = RoundedCornerShape(10.dp)

enum class AppButtonStyle { Filled, Tonal, Ghost }

@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    style: AppButtonStyle = AppButtonStyle.Filled,
    fillWidth: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val container = when (style) {
        AppButtonStyle.Filled -> if (enabled) scheme.primary else scheme.surfaceVariant
        AppButtonStyle.Tonal -> if (enabled) scheme.surfaceContainerHigh else scheme.surfaceVariant.copy(alpha = 0.6f)
        AppButtonStyle.Ghost -> Color.Transparent
    }
    val content = when (style) {
        AppButtonStyle.Filled -> if (enabled) scheme.onPrimary else scheme.onSurfaceVariant
        AppButtonStyle.Tonal -> if (enabled) scheme.onSurface else scheme.onSurfaceVariant
        AppButtonStyle.Ghost -> if (enabled) scheme.primary else scheme.onSurfaceVariant
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = ControlShape,
        color = container,
        contentColor = content,
        border = if (style == AppButtonStyle.Ghost) {
            BorderStroke(1.dp, scheme.outline.copy(alpha = if (enabled) 0.7f else 0.3f))
        } else {
            null
        },
        modifier = if (fillWidth) modifier.fillMaxWidth() else modifier,
    ) {
        Row(
            modifier = Modifier
                .defaultMinSize(minHeight = 44.dp)
                .padding(horizontal = 20.dp, vertical = 11.dp),
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

/** 顶栏用的图标按钮：矩形圆角、有底色与边界，不再是看不见的透明热区。 */
@Composable
fun AppIconButton(
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
        shape = ControlShapeSmall,
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

/** 顶栏/输入条上的小选择项（模型、档位这类可点开的选择项）。 */
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
        shape = ControlShapeSmall,
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

/**
 * 后端小标签：会话行里跟在时间后面，告诉人这条是 pi / DSH / 哪个 ACP 工具。
 *
 * pi 用弱色（绝大多数会话都是它，别抢眼），其余用主题色描一下。
 * 文案不在这里拼 —— 统一由 `AgentBackend.badgeOf(backend, acpToolName)` 给。
 */
@Composable
fun BackendBadge(label: String, muted: Boolean = false, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = if (muted) scheme.surfaceContainerHigh else scheme.primary.copy(alpha = 0.16f),
        shape = RoundedCornerShape(5.dp),
        modifier = modifier,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (muted) scheme.onSurfaceVariant else scheme.primary,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

/**
 * 卡片类区域的按压反馈。
 *
 * 不用默认的 ripple：它是一块**直角**的半透明色块，铺在圆角卡片上会从角上溢出来，
 * 深色主题里看着像瞬间盖了一层生硬的灰影（用户反馈「展开时的阴影太生硬」）。
 * 这里改成整块轻微提亮 + 裁剪到卡片形状，松手 110ms 淡出，跟卡片是一体的。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.softPress(
    shape: Shape,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val progress by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = tween(durationMillis = 110),
        label = "softPress",
    )
    val tint = MaterialTheme.colorScheme.onSurface
    val clickableModifier = if (onLongClick == null) {
        Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)
    } else {
        Modifier.combinedClickable(
            interactionSource = interaction,
            indication = null,
            onClick = onClick,
            onLongClick = onLongClick,
        )
    }
    return this
        .clip(shape)
        .then(clickableModifier)
        .drawWithContent {
            drawContent()
            if (progress > 0f) {
                drawRect(color = tint.copy(alpha = 0.055f * progress))
            }
        }
}
