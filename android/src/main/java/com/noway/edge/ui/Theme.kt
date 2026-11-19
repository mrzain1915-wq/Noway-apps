package com.noway.edge.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val SignalBackground = Color(0xFF07111F)
val SignalSurface = Color(0xFF0D1A2B)
val SignalSurfaceAlt = Color(0xFF12233A)
val SignalBorder = Color(0xFF223A56)
val SignalText = Color(0xFFF5F8FC)
val SignalMuted = Color(0xFFA8B6C8)
val SignalCyan = Color(0xFF55C8F3)
val SignalBlue = Color(0xFF4D8DFF)
val SignalGreen = Color(0xFF31D0AA)
val SignalAmber = Color(0xFFF4C25C)
val SignalRed = Color(0xFFFF6B78)

private val DarkColors = darkColorScheme(
    primary = SignalCyan,
    onPrimary = SignalBackground,
    primaryContainer = Color(0xFF112E57),
    onPrimaryContainer = Color(0xFFE7EFFF),
    secondary = SignalGreen,
    tertiary = SignalAmber,
    background = SignalBackground,
    onBackground = SignalText,
    surface = SignalSurface,
    onSurface = SignalText,
    surfaceVariant = SignalSurfaceAlt,
    onSurfaceVariant = SignalMuted,
    outline = SignalBorder,
    error = SignalRed,
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF087AA2),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8F3FF),
    secondary = Color(0xFF087E63),
    tertiary = Color(0xFF7A5500),
    background = Color(0xFFEDF2F8),
    onBackground = Color(0xFF0D1A2B),
    surface = Color.White,
    onSurface = Color(0xFF0D1A2B),
    surfaceVariant = Color(0xFFE7EFFF),
    onSurfaceVariant = Color(0xFF5D6B7E),
    outline = Color(0xFFD2DDEA),
    error = Color(0xFFC23343),
)

@Composable
fun NowayTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}

