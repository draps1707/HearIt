package com.hearit.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val ObsidianBg = Color(0xFF0A0E17)
val ObsidianSurface = Color(0xFF121826)
val ObsidianCard = Color(0xFF1A2234)
val EmeraldAccent = Color(0xFF10B981)
val CyanAccent = Color(0xFF06B6D4)
val TextPrimary = Color(0xFFF1F5F9)
val TextMuted = Color(0xFF94A3B8)

private val HearItColorScheme = darkColorScheme(
    primary = EmeraldAccent,
    secondary = CyanAccent,
    background = ObsidianBg,
    surface = ObsidianSurface,
    surfaceVariant = ObsidianCard,
    onPrimary = Color.Black,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextMuted
)

@Composable
fun HearItTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = HearItColorScheme,
        content = content
    )
}
