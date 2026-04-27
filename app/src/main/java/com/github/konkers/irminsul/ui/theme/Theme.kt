package com.github.konkers.irminsul.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.colorResource

// 原神主题色彩
val Purple80 = Purple80
val PurpleGrey80 = PurpleGrey80
val Pink80 = Pink80

val Purple40 = Purple40
val PurpleGrey40 = PurpleGrey40
val Pink40 = Pink40

// 原神元素色彩
val AnemoColor = 0xFF80FFD0.toInt()
val GeoColor = 0xFFFFB800.toInt()
val ElectroColor = 0xFFAA00FF.toInt()
val DendroColor = 0xFF00AA00.toInt()
val HydroColor = 0xFF00AAFF.toInt()
val PyroColor = 0xFFFF5500.toInt()
val CryoColor = 0xFF80EFFF.toInt()

// 稀有度色彩
val FiveStarColor = 0xFFFFA500.toInt()
val FourStarColor = 0xFF800080.toInt()
val ThreeStarColor = 0xFF0000FF.toInt()

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40
)

@Composable
fun IrminsulTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
