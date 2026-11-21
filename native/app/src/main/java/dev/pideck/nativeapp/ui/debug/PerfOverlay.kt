package dev.pideck.nativeapp.ui.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pideck.nativeapp.data.FrameSampler
import kotlin.math.roundToInt

/**
 * 性能浮层：一个可拖动的小胶囊，下面是 `FPS 60 · 1% Low 45`。
 *
 * 只在设置里打开时才挂载 —— 每帧一次 `Choreographer` 回调，轻但没必要常开。
 * 样式照参考图：暗色玻璃胶囊 + 左侧太阳图标 + 右侧「…」；点太阳收放文字行，
 * 点「…」展开更多指标；整体可拖动。
 */
@Composable
fun PerfOverlay(modifier: Modifier = Modifier) {
    var fps by remember { mutableIntStateOf(0) }
    var low by remember { mutableIntStateOf(0) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var showText by rememberSaveable { mutableStateOf(true) }
    var dragX by rememberSaveable { mutableFloatStateOf(0f) }
    var dragY by rememberSaveable { mutableFloatStateOf(0f) }

    DisposableEffect(Unit) {
        val sampler = FrameSampler()
        sampler.start { f, l ->
            fps = f
            low = l
        }
        onDispose { sampler.stop() }
    }

    Column(
        modifier = modifier
            .offset { IntOffset(dragX.roundToInt(), dragY.roundToInt()) }
            .padding(top = 96.dp, start = 24.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    dragX += drag.x
                    dragY += drag.y
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Pill(onToggleText = { showText = !showText }, onToggleMore = { expanded = !expanded })

        if (showText) {
            Text(
                text = "FPS $fps · 1% Low $low",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.35f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }

        if (expanded) {
            Surface(
                color = Color(0xFF1C1C1E).copy(alpha = 0.94f),
                shape = RoundedCornerShape(14.dp),
                shadowElevation = 8.dp,
                modifier = Modifier.padding(top = 8.dp).width(172.dp),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    MetricRow("帧率", "$fps FPS")
                    MetricRow("1% Low", "$low FPS")
                    MetricRow("上限", "跟随屏幕刷新率")
                    Text(
                        text = if (low in 1 until (fps * 6 / 10)) "最差 1% 明显低于平均：这一屏有卡顿" else "帧率稳定",
                        color = if (low in 1 until (fps * 6 / 10)) Color(0xFFFFB74D) else Color(0xFF81C784),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Pill(onToggleText: () -> Unit, onToggleMore: () -> Unit) {
    Surface(
        color = Color(0xFF141416).copy(alpha = 0.9f),
        shape = RoundedCornerShape(50),
        shadowElevation = 10.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(52.dp, 40.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onToggleText),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.LightMode,
                    contentDescription = "显示或隐藏帧率文字",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
            Box(
                Modifier
                    .width(1.dp)
                    .height(18.dp)
                    .background(Color.White.copy(alpha = 0.12f)),
            )
            Box(
                modifier = Modifier
                    .size(52.dp, 40.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onToggleMore),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.MoreHoriz,
                    contentDescription = "更多指标",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
        Text(value, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}
