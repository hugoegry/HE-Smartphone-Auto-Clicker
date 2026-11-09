package com.he.he_smartphone_auto_clicker.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val HeBlack = Color(0xFF0B0B0B)
val HeBg = Color(0xFF121212)
val HeSurface = Color(0xFF1C1C1C)
val HeSurfaceUp = Color(0xFF262626)
val HeGold = Color(0xFFD4AF37)
val HeGoldSoft = Color(0xFFE8C96A)
val HeGray = Color(0xFF9E9E9E)
val HeText = Color(0xFFF0F0F0)

private val HeDarkColors = darkColorScheme(
    primary = HeGold,
    onPrimary = HeBlack,
    secondary = HeGoldSoft,
    onSecondary = HeBlack,
    background = HeBg,
    onBackground = HeText,
    surface = HeSurface,
    onSurface = HeText
)

@Composable
fun HeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = HeDarkColors, content = content)
}
