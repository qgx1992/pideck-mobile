package dev.pideck.nativeapp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 与桌面端同一套基色：深灰底 + 蓝紫强调色，避免「原生 App 长得像另一个产品」。
private val Accent = Color(0xFF4C8DFF)
private val AccentDark = Color(0xFFB4C8FF)

private val DarkColors = darkColorScheme(
    primary = AccentDark,
    onPrimary = Color(0xFF10233F),
    primaryContainer = Color(0xFF1F3A66),
    onPrimaryContainer = Color(0xFFD7E3FF),
    secondary = Color(0xFFB9C6DA),
    background = Color(0xFF121316),
    onBackground = Color(0xFFE4E6EA),
    surface = Color(0xFF121316),
    onSurface = Color(0xFFE4E6EA),
    surfaceVariant = Color(0xFF22252B),
    onSurfaceVariant = Color(0xFFA9AFBA),
    surfaceContainer = Color(0xFF181A1E),
    surfaceContainerHigh = Color(0xFF1E2126),
    outline = Color(0xFF3A3F48),
    outlineVariant = Color(0xFF2A2E35),
    error = Color(0xFFFFB4AB),
)

private val LightColors = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE6FF),
    onPrimaryContainer = Color(0xFF001A41),
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF14161A),
    surface = Color(0xFFF7F8FA),
    onSurface = Color(0xFF14161A),
    surfaceVariant = Color(0xFFE7E9EE),
    onSurfaceVariant = Color(0xFF5A616D),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF1F3F6),
    outline = Color(0xFFC5C9D2),
    outlineVariant = Color(0xFFDDE0E6),
    error = Color(0xFFBA1A1A),
)

@Composable
fun PiDeckTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        // 默认 M3 的 4/8/12/16dp 圆角在手机上偏方；统一上调一档，卡片/弹层/按钮看着更软。
        shapes = Shapes(
            extraSmall = RoundedCornerShape(10.dp),
            small = RoundedCornerShape(14.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(22.dp),
            extraLarge = RoundedCornerShape(28.dp),
        ),
        content = content,
    )
}
