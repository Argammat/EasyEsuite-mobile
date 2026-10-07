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

// Brand colours lifted from the web app's "Modern" theme (erp.easyesuite.com, Oct 2026):
// green primary, dark-green text on a light-green tint for active states, blue only for links/UPCs.
val Green = Color(0xFF2DB682)          // brand green (accents, success)
val GreenDeep = Color(0xFF0D7A51)      // filled buttons — readable with white text
val GreenText = Color(0xFF006947)      // active nav / chip text
val GreenTint = Color(0xFFDAF2E8)      // active nav / chip background
val Blue = Color(0xFF0171E3)           // links, UPCs, data
val Amber = Color(0xFFF59E0B)
val Red = Color(0xFFCB2026)
val Ink = Color(0xFF0F172A)            // headings
val Slate = Color(0xFF2C2F30)          // body text
val Muted = Color(0xFF595C5D)          // secondary text
val Surface = Color(0xFFEEF1F2)        // inputs, chips, muted panels
val Page = Color(0xFFFBFBFB)           // page background
val Stroke = Color(0xFFE4ECF4)         // card borders / dividers

private val LightColors = lightColorScheme(
    primary = GreenDeep,
    onPrimary = Color.White,
    primaryContainer = GreenTint,
    onPrimaryContainer = GreenText,
    secondary = Green,
    onSecondary = Color.White,
    secondaryContainer = GreenTint,
    onSecondaryContainer = GreenText,
    tertiary = Amber,
    background = Page,
    onBackground = Slate,
    surface = Color.White,
    onSurface = Slate,
    surfaceVariant = Surface,
    onSurfaceVariant = Muted,
    outline = Muted,
    outlineVariant = Stroke,
    error = Red,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5FD3A6),
    onPrimary = Color(0xFF04231A),
    primaryContainer = Color(0xFF0D4A34),
    onPrimaryContainer = Color(0xFFBFF0DB),
    secondary = Green,
    secondaryContainer = Color(0xFF0D4A34),
    onSecondaryContainer = Color(0xFFBFF0DB),
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
