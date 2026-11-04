package dev.pideck.nativeapp.ui.splash

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 冷启动的过渡页。
 *
 * 只做一件事：把「读盘 + 决定落哪一页」这段时间盖住，别让用户看到设备页闪一下再跳走。
 * 所以它必须有最短显示时长（见 [AppNav]），否则读盘快的机器上等于没显示。
 * 动效克制：圆点呼吸 + 标题淡入放大，不做全屏粒子那套。
 */
@Composable
fun SplashScreen(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }

    val fade by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(520, easing = FastOutSlowInEasing),
        label = "splash-fade",
    )
    val rise by animateFloatAsState(
        targetValue = if (appeared) 1f else 0.94f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 220f),
        label = "splash-rise",
    )
    val breathe = rememberInfiniteTransition(label = "splash-breathe")
    val pulse by breathe.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "splash-pulse",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(scheme.background, scheme.surfaceVariant.copy(alpha = 0.55f)),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .alpha(fade)
                .scale(rise),
        ) {
            Surface(
                shape = CircleShape,
                color = scheme.primary.copy(alpha = 0.14f),
                modifier = Modifier.size(96.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Surface(
                        shape = CircleShape,
                        color = scheme.primary,
                        modifier = Modifier
                            .size(40.dp)
                            .scale(pulse),
                    ) {}
                }
            }
            Text(
                "PiDeck",
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.5.sp,
                color = scheme.onBackground,
                modifier = Modifier.padding(top = 22.dp),
            )
            Text(
                "连接桌面上的 Agent",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
