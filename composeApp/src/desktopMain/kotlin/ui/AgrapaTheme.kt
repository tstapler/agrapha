package com.meetingnotes.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AgraphaDarkColors = darkColorScheme(
    primary = Color(0xFF0D9488),
    onPrimary = Color(0xFFF5F5F5),
    primaryContainer = Color(0xFF0A7468),
    onPrimaryContainer = Color(0xFF99F6E4),
    secondary = Color(0xFFD97706),
    onSecondary = Color(0xFF111111),
    secondaryContainer = Color(0xFF3D2200),
    onSecondaryContainer = Color(0xFFFDE68A),
    background = Color(0xFF111111),
    onBackground = Color(0xFFF5F5F5),
    surface = Color(0xFF1C1C1E),
    onSurface = Color(0xFFF5F5F5),
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = Color(0xFF888888),
    error = Color(0xFFEF4444),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFECACA),
    outline = Color(0xFF444444),
    outlineVariant = Color(0xFF2A2A2A),
)

@Composable
fun AgrapaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AgraphaDarkColors,
        content = content,
    )
}
