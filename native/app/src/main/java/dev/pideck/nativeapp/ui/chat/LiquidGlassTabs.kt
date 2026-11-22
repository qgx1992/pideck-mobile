package dev.pideck.nativeapp.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/** 胶囊高度（含上下各 3dp 留白）。 */
private val TAB_HEIGHT = 42.dp
private val TAB_PAD = 3.dp
private val TAB_RADIUS = 15.dp

/**
 * 液态玻璃分段控件：选中项是一枚磨砂玻璃胶囊。
 *
 * 位置**只有一个来源**：调用方传进来的连续索引 [position]（通常是
 * `pager.currentPage + pager.currentPageOffsetFraction`）。这里不做任何位置动画 ——
 * 之前那版自己拿 `Animatable` 追 `selected`，结果拖动、点按、pager 滚动三套坐标互相打架，
 * 松手时胶囊回跳一下（闪），点远端 tab 时又因为 `currentPage` 逐格变化而一格一格跳。
 *
 * 「被拉长」的效果也不再用 `snapTo(1f)` 硬撑，而是由离最近一格的距离决定（`lean`）：
 * 滑动过程中自然从 0 升到 1 再回落，连续、无状态、不会闪。
 *
 * 磨砂玻璃是半透明渐变 + 1dp 高光边 + 外投影模拟的 —— Compose 没有 backdrop blur。
 */
@Composable
internal fun LiquidGlassTabs(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** 连续位置（以「格」为单位，0.0 = 第一格中心）。拖动 / 滚动时由 pager 提供。 */
    position: Float = selected.toFloat(),
) {
    if (labels.isEmpty()) return
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme

    var widthPx by remember { mutableIntStateOf(0) }
    val segment = if (widthPx == 0) 0f else widthPx.toFloat() / labels.size
    val heightPx = with(density) { TAB_HEIGHT.toPx() }
    val padPx = with(density) { TAB_PAD.toPx() }
    val radiusPx = with(density) { TAB_RADIUS.toPx() }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(TAB_HEIGHT)
            .onSizeChanged { widthPx = it.width }
            .drawBehind {
                if (segment <= 0f) return@drawBehind
                // 离最近一格越远 → 胶囊被拉得越长（0..1），松手自然收回。
                val lean = (abs(position - position.roundToInt()) * 2f).coerceIn(0f, 1f)
                val center = (position + 0.5f) * segment
                val width = (segment * (0.9f + 0.16f * lean) - padPx * 2f).coerceAtLeast(1f)
                val height = heightPx - padPx * 2f
                val left = (center - width / 2f)
                    .coerceIn(padPx, (size.width - width - padPx).coerceAtLeast(padPx))
                val top = (size.height - height) / 2f
                val corner = CornerRadius(radiusPx)

                drawRoundRect(
                    color = Color.Black.copy(alpha = 0.10f),
                    topLeft = Offset(left, top + 2.5f),
                    size = Size(width, height),
                    cornerRadius = corner,
                )
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            colors.primary.copy(alpha = 0.20f),
                            colors.primary.copy(alpha = 0.07f),
                        ),
                        startY = top,
                        endY = top + height,
                    ),
                    topLeft = Offset(left, top),
                    size = Size(width, height),
                    cornerRadius = corner,
                )
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.30f),
                    topLeft = Offset(left + 0.6f, top + 0.6f),
                    size = Size(width - 1.2f, height - 1.2f),
                    cornerRadius = corner,
                    style = Stroke(width = 1.1f),
                )
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val selectedNow = index == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selectedNow) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selectedNow) colors.onSurface else colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}
