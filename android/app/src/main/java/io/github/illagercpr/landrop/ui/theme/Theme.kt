package io.github.illagercpr.landrop.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val BrandGreen = Color(0xFF1B6C4B)
private val BrandGreenLight = Color(0xFF7FD1A8)

private val LightColors = lightColorScheme(
    primary = BrandGreen,
    secondary = Color(0xFF4A6358),
    tertiary = Color(0xFF3D6373),
)

private val DarkColors = darkColorScheme(
    primary = BrandGreenLight,
    secondary = Color(0xFFB1CCC0),
    tertiary = Color(0xFFA5CCDF),
)

@Composable
fun LanDropTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** Android 12+ 动态取色；关闭则使用品牌色 */
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
