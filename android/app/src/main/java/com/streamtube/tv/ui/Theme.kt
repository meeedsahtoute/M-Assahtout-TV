package com.streamtube.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

object StColors {
    val Background = Color(0xFF0A0A0D)
    val Surface = Color(0xFF14141A)
    val SurfaceElevated = Color(0xFF1E1E27)
    val SurfaceFocus = Color(0xFF282834)
    val Border = Color(0xFF2C2C3A)
    val TextPrimary = Color(0xFFF5F5F7)
    val TextSecondary = Color(0xFFA0A3B1)
    val TextMuted = Color(0xFF6B6E7D)
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
