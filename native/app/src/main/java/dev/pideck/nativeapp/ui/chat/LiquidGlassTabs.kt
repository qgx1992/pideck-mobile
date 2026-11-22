package dev.pideck.nativeapp.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onSizeChanged
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** 胶囊高度（含上下各 3dp 留白）。 */
private val TAB_HEIGHT = 42.dp
private val TAB_PAD = 3.dp
private val TAB_RADIUS = 15.dp

/**
 * 液态玻璃分段控件：选中项是一枚磨砂玻璃胶囊。
 *
 * 切换时胶囊先横向撑开（像被拉长的水滴），再沿水平方向流到新位置，最后用
 * `EaseOutBack` 过冲一点回弹 —— 全程 340ms，没有硬切也没有闪烁。
 *
 * 为什么不用 `Modifier.blur`：Compose 没有「背景模糊」（backdrop blur），`blur` 只会
 * 把自身内容糊掉。这里是半透明渐变 + 1dp 高光边 + 外投影模拟磨砂玻璃；文字画在胶囊
 * 之上、不参与任何透明度变化，所以对比度不受影响。
 *
 * 动画值只在 `drawBehind` 里读（绘制阶段重绘），文字与布局不随之重组。
 */
@Composable
internal fun LiquidGlassTabs(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** 手指拖动中的实时偏移（以「格」为单位，-1..1）。非 0 时胶囊跟手，否则走自己的吸附动画。 */
    fraction: Float = 0f,
) {
    if (labels.isEmpty()) return
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme

    var widthPx by remember { mutableIntStateOf(0) }
    val position = remember { Animatable(selected.toFloat()) }
    val stretch = remember { Animatable(0f) }
    var first by remember { mutableStateOf(true) }

    // 注意这里同时监听 `fraction`：手指拖动时每帧把动画值钉到实时位置。
    // 不这么做就会在松手那一帧出问题 —— `selected` 已经跳到新格，而 `position` 还停在旧格，
    // 于是胶囊先「瞬移回旧格」再滑过去，看起来就是闪一下。
    LaunchedEffect(selected, fraction) {
        if (fraction != 0f) {
            // 拖动中：直接跟手，不打动画、不拉伸。
            position.snapTo(selected + fraction)
            return@LaunchedEffect
        }
        if (first) {
            first = false
            position.snapTo(selected.toFloat())
            return@LaunchedEffect
        }
        val delta = selected - position.value
        // 已经被拖动带到位（差值 < 2% 格）：什么都不做，否则会原地抖一下。
        if (abs(delta) < 0.02f) return@LaunchedEffect
        // 只有「点按跳格」才做水滴拉伸；拖动收尾不需要，那一下撑开正是另一处闪。
        val jump = abs(delta) >= 0.5f
        if (jump) stretch.snapTo(1f)
        coroutineScope {
            launch {
                position.animateTo(selected.toFloat(), tween(durationMillis = 340, easing = EaseOutBack))
            }
            if (jump) {
                launch {
                    stretch.animateTo(0f, spring(dampingRatio = 0.52f, stiffness = 360f))
                }
            }
        }
    }

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
                // 拖动中直接跟手（不打动画），松手后由 position 的弹簧接上。
                val index = if (fraction != 0f) selected + fraction else position.value
                val center = (index + 0.5f) * segment
                val width = (segment * (0.9f + 0.24f * stretch.value) - padPx * 2f)
                    .coerceAtLeast(1f)
                val height = heightPx - padPx * 2f
                val left = (center - width / 2f).coerceIn(padPx, (size.width - width - padPx).coerceAtLeast(padPx))
                val top = (size.height - height) / 2f
                val corner = CornerRadius(radiusPx)

                // 外投影：压在胶囊下方，让玻璃「浮」起来一点。
                drawRoundRect(
                    color = Color.Black.copy(alpha = 0.10f),
                    topLeft = Offset(left, top + 2.5f),
                    size = Size(width, height),
                    cornerRadius = corner,
                )
                // 玻璃本体：自上而下由淡主色到几乎透明。
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
                // 高光边：浅色主题靠它体现「玻璃」，深色主题靠它把胶囊从底色里分出来。
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
