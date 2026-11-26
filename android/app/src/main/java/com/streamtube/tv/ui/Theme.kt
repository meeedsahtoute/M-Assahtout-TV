package com.streamtube.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/**
 * Exact 1:1 visual token palette mirroring the desktop GPUI dark cinema theme (src/ui/mod.rs).
 */
object StColors {
    val Background = Color(0xFF0F0F0F)
    val Surface = Color(0xFF181818)
    val SurfaceElevated = Color(0xFF212121)
    val SurfaceFocus = Color(0xFF282828)
    val Hover = Color(0xFF3A3A3A)
    val Border = Color(0xFF303030)
    val Chip = Color(0xFF272727)
    val ChipActiveBg = Color(0xFFF1F1F1)
    val ChipActiveFg = Color(0xFF0F0F0F)
    val TextPrimary = Color(0xFFF1F1F1)
    val TextSecondary = Color(0xFFAAAAAA)
    val TextMuted = Color(0xFF717171)
    val LinkBlue = Color(0xFF3EA6FF)
    val LiveRed = Color(0xFFCC0000)
    val Success = Color(0xFF22C55E)
    val Warning = Color(0xFFF59E0B)
    val Error = Color(0xFFEF4444)
}

@Composable
fun StreamTubeTheme(
    accentHex: Long = 0xFF0033,
    content: @Composable () -> Unit
) {
    val accent = Color((0xFF000000L or (accentHex and 0xFFFFFFL)).toInt())
    val scheme = darkColorScheme(
        primary = accent,
        onPrimary = Color.White,
        secondary = accent.copy(alpha = 0.85f),
        onSecondary = Color.White,
        background = StColors.Background,
        onBackground = StColors.TextPrimary,
        surface = StColors.Surface,
        onSurface = StColors.TextPrimary,
        surfaceVariant = StColors.SurfaceElevated,
        onSurfaceVariant = StColors.TextSecondary,
        border = StColors.Border
    )
    MaterialTheme(
        colorScheme = scheme,
        content = content
    )
}
