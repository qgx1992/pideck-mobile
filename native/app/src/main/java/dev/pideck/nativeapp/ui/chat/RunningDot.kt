package dev.pideck.nativeapp.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 运行中的绿点。呼吸的外圈用 graphicsLayer 做，不参与布局，不然行高会跳。 */
private val RunningGreen = Color(0xFF4ADE80)

/**
 * 会话状态点。
 *
 * 槽位固定 14dp（外圈最多扩到 2.4 倍也在这个范围里），所以「空闲灰点」和「运行中呼吸点」
 * 切换时文字不会左右跳。动画只在这个组合项存活时跑，列表滚走的项不会继续耗帧。
 */
@Composable
fun RunningDot(
    active: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 6.dp,
) {
    Box(modifier = modifier.size(14.dp), contentAlignment = Alignment.Center) {
        if (!active) {
            Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant))
            return@Box
        }
        val transition = rememberInfiniteTransition(label = "running-dot")
        val pulse by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1500, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "pulse",
        )
        // 外圈：从 1 倍扩到 2.3 倍、同时淡出，像水面一圈涟漪
        Box(
            Modifier
                .size(size)
                .graphicsLayer {
                    val grow = 1f + pulse * 1.3f
                    scaleX = grow
                    scaleY = grow
                    alpha = (1f - pulse) * 0.55f
                }
                .clip(CircleShape)
                .background(RunningGreen),
        )
        Box(Modifier.size(size).clip(CircleShape).background(RunningGreen))
    }
}
