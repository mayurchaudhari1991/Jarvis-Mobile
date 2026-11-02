package com.veg.hud

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Fixed palette from the approved HUD design.
 *
 * Single dark theme by intent: this is an instrument panel, and a light variant
 * would undermine the whole read. No isSystemInDarkTheme branching anywhere.
 */
object VegColors {
    val Ground = Color(0xFF06080B)
    val Panel = Color(0xFF0C1117)
    val PanelHi = Color(0xFF121A22)
    val Hairline = Color(0xFF1B2530)
    val HairlineLo = Color(0xFF141C25)
    val Text = Color(0xFFDDE6EF)
    val Neutral = Color(0xFF8A97A6)
    val NeutralDim = Color(0xFF55636F)
    val Cool = Color(0xFF3FD8E8)
    val CoolDeep = Color(0xFF1B8C99)
    val Warm = Color(0xFFFF8A2A)
    val Amber = Color(0xFFF2A93B)
    val Red = Color(0xFFE8604C)
}

private val VegScheme = darkColorScheme(
    primary = VegColors.Cool,
    onPrimary = VegColors.Ground,
    secondary = VegColors.Warm,
    background = VegColors.Ground,
    onBackground = VegColors.Text,
    surface = VegColors.Panel,
    onSurface = VegColors.Text,
    error = VegColors.Red
)

val MonoStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 11.sp,
    letterSpacing = 1.6.sp
)

private val VegTypography = Typography(
    bodyMedium = TextStyle(fontSize = 14.sp, color = VegColors.Text),
    labelSmall = MonoStyle.copy(fontWeight = FontWeight.Medium)
)

@Composable
fun VegTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = VegScheme,
        typography = VegTypography,
        content = content
    )
}
