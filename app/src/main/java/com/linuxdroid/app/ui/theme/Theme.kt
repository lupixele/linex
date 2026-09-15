package com.linuxdroid.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFE2E2E6),
    onPrimary = Color(0xFF141416),
    primaryContainer = Color(0xFF28282D),
    onPrimaryContainer = Color(0xFFE2E2E6),
    secondary = Color(0xFF9E9EA4),
    onSecondary = Color(0xFF141416),
    background = Color(0xFF101012),
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF18181B),
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF222226),
    onSurfaceVariant = Color(0xFFC7C7CC),
    outline = Color(0xFF323238),
    error = Color(0xFFFF453A),
    onError = Color(0xFF141416)
)

@Composable
fun LinuxDroidTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
