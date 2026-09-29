package com.example.lanbeam.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// One calm indigo accent on neutral surfaces, in light and dark. Dynamic colour is off so the
// brand (and the QR card contrast) is the same on every phone.
private val Light = lightColorScheme(
    primary = Color(0xFF4F46E5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E7FF),
    onPrimaryContainer = Color(0xFF1E1B4B),
    secondary = Color(0xFF475569),
    background = Color(0xFFF7F7FA),
    onBackground = Color(0xFF111827),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFF1F2F6),
    onSurfaceVariant = Color(0xFF5B6070),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF1F2F6),
    outlineVariant = Color(0xFFE3E5EB),
    error = Color(0xFFDC2626),
    tertiary = Color(0xFF16A34A),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA5B4FC),
    onPrimary = Color(0xFF1E1B4B),
    primaryContainer = Color(0xFF312E81),
    onPrimaryContainer = Color(0xFFE0E7FF),
    secondary = Color(0xFF94A3B8),
    background = Color(0xFF0E0F13),
    onBackground = Color(0xFFECEDF2),
    surface = Color(0xFF16171D),
    onSurface = Color(0xFFECEDF2),
    surfaceVariant = Color(0xFF1F2129),
    onSurfaceVariant = Color(0xFF9CA0AE),
    surfaceContainer = Color(0xFF16171D),
    surfaceContainerHigh = Color(0xFF1F2129),
    outlineVariant = Color(0xFF2A2C36),
    error = Color(0xFFF87171),
    tertiary = Color(0xFF4ADE80),
)

@Composable
fun LANBeamTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) Dark else Light, typography = Typography, content = content)
}
