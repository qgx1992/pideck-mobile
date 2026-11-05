package dev.pideck.nativeapp.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.pideck.nativeapp.data.SessionActivity

private val Blue = Color(0xFF60A5FA)
private val Orange = Color(0xFFF59E0B)
private val Green = Color(0xFF4ADE80)
private val Red = Color(0xFFF87171)

private fun colorOf(status: SessionActivity.Status): Color = when (status) {
    SessionActivity.Status.Running -> Blue
    SessionActivity.Status.Waiting -> Orange
    SessionActivity.Status.Done -> Green
    SessionActivity.Status.Failed -> Red
    SessionActivity.Status.Idle -> Color.Unspecified
}

/**
 * 会话状态灯。槽位固定 14dp，所以五种状态互相切换时同一行文字不会左右跳。
 *
 * - 运行中：蓝色转圈（速度 900ms 一圈，比呼吸更明确地表达「在动」）；
 * - 等待回答：橙色实心点；
 * - 完成：绿色实心点；
 * - 失败/中断：红色实心点；
 * - 空闲/未跑过：主题里的灰。
 *
 * 动画只在「运行中」这一个组合项里创建，其它状态是静态绘制，列表滚出屏幕就停。
 */
@Composable
fun StatusDot(
    status: SessionActivity.Status,
    modifier: Modifier = Modifier,
    slot: Dp = 14.dp,
) {
    val idleColor = MaterialTheme.colorScheme.outlineVariant
    val color = colorOf(status).takeIf { it != Color.Unspecified } ?: idleColor

    Box(modifier = modifier.size(slot), contentAlignment = Alignment.Center) {
        if (status == SessionActivity.Status.Running) {
            val transition = rememberInfiniteTransition(label = "status-spinner")
            val angle by transition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 900, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "angle",
            )
            Canvas(Modifier.size(slot)) {
                val stroke = 1.8.dp.toPx()
                val radius = (size.minDimension - stroke) / 2f * 0.82f
                drawArc(
                    color = color,
                    startAngle = angle,
                    sweepAngle = 260f,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        } else {
            val dot = if (status == SessionActivity.Status.Idle) 6.dp else 7.dp
            Canvas(Modifier.size(slot)) {
                drawCircle(color = color, radius = dot.toPx() / 2f, center = center)
            }
        }
    }
}
