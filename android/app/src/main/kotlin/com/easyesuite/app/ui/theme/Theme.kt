package com.easyesuite.app.ui.theme

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

// Brand colours lifted from the web app (#0171E3 primary, #2DB682 success).
val Blue = Color(0xFF0171E3)
val Green = Color(0xFF2DB682)
val Amber = Color(0xFFF59E0B)
val Red = Color(0xFFDC2626)
val Slate = Color(0xFF3D3D3D)
val Surface = Color(0xFFF0F5FA)

private val LightColors = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    secondary = Green,
    onSecondary = Color.White,
    tertiary = Amber,
    background = Color.White,
    surface = Color.White,
    surfaceVariant = Surface,
    onSurface = Slate,
    error = Red,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FB8FF),
    secondary = Green,
    tertiary = Amber,
    error = Color(0xFFFF8A80),
)

@Composable
fun EasyEsuiteTheme(useDynamicColor: Boolean = false, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = when {
        useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
