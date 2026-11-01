package com.eddiew.commandersim.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Dark grey surfaces with an electric-blue signal colour. */
object Hud {
    val Bg = Color(0xFF0E1014)
    val Surface = Color(0xFF151920)
    val SurfaceHi = Color(0xFF1C212A)
    val Grid = Color(0xFF1F2530)
    val Outline = Color(0xFF2A323E)
    val Text = Color(0xFFE6EDF5)
    val Muted = Color(0xFF8592A6)
    val Dim = Color(0xFF55606F)

    val Electric = Color(0xFF1EA7FF)
    val ElectricDeep = Color(0xFF0A5BFF)
    val Cyan = Color(0xFF3DF2FF)
    val Ice = Color(0xFF9FE4FF)

    val Positive = Color(0xFF3DFFB0)
    val Negative = Color(0xFFFF4D6D)
    val Warn = Color(0xFFFFC24D)

    /** Ordered series palette: blues first, then contrast accents. */
    val Series = listOf(
        Electric, Cyan, Color(0xFF7C6CFF), Warn, Color(0xFFFF5C8A), Positive, Ice, Color(0xFF4D7CFF),
    )

    /** Magic colour pips. */
    fun manaColor(c: Char): Color = when (c) {
        'W' -> Color(0xFFF4EBC8)
        'U' -> Color(0xFF3E9BFF)
        'B' -> Color(0xFF9A8C98)
        'R' -> Color(0xFFFF5A3D)
        'G' -> Color(0xFF2FD07A)
        else -> Color(0xFFB8C0CC)
    }
}

val Mono = FontFamily.Monospace

private val scheme = darkColorScheme(
    primary = Hud.Electric,
    onPrimary = Color(0xFF00121F),
    primaryContainer = Hud.ElectricDeep,
    onPrimaryContainer = Hud.Text,
    secondary = Hud.Cyan,
    onSecondary = Color(0xFF00181A),
    tertiary = Hud.Ice,
    background = Hud.Bg,
    onBackground = Hud.Text,
    surface = Hud.Surface,
    onSurface = Hud.Text,
    surfaceVariant = Hud.SurfaceHi,
    onSurfaceVariant = Hud.Muted,
    surfaceContainer = Hud.Surface,
    surfaceContainerHigh = Hud.SurfaceHi,
    surfaceContainerHighest = Hud.SurfaceHi,
    surfaceContainerLow = Hud.Surface,
    surfaceContainerLowest = Hud.Bg,
    outline = Hud.Outline,
    outlineVariant = Hud.Grid,
    error = Hud.Negative,
)

private val typography = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp, letterSpacing = 1.sp, color = Hud.Text),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = 0.5.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.2.sp),
    labelMedium = TextStyle(fontFamily = Mono, fontSize = 11.sp, letterSpacing = 1.2.sp),
    labelSmall = TextStyle(fontFamily = Mono, fontSize = 10.sp, letterSpacing = 1.sp),
)

@Composable
fun CommanderSimTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
