package com.example.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = StudioPrimaryAccent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3B1A6E),
    onPrimaryContainer = Color(0xFFF3E8FF),
    secondary = StudioSecondaryAccent,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF4C163B),
    onSecondaryContainer = Color(0xFFFCE7F3),
    tertiary = StudioTertiaryAccent,
    background = StudioBackgroundDark,
    onBackground = Color(0xFFF5F3FF),
    surface = StudioSurfaceDark,
    onSurface = Color(0xFFF5F3FF),
    surfaceVariant = StudioSurfaceVariantDark,
    onSurfaceVariant = Color(0xFFD4C6EE),
    outline = Color(0xFF4D3E72)
)

private val LightColorScheme = lightColorScheme(
    primary = PodcastPurple40,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF3E8FF),
    onPrimaryContainer = Color(0xFF3B0764),
    secondary = PodcastMagenta40,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFAE8FF),
    onSecondaryContainer = Color(0xFF4A044E),
    tertiary = PodcastRose40,
    background = StudioBackgroundLight,
    onBackground = Color(0xFF1A102C),
    surface = StudioSurfaceLight,
    onSurface = Color(0xFF1A102C),
    surfaceVariant = StudioSurfaceVariantLight,
    onSurfaceVariant = Color(0xFF4B3C66),
    outline = Color(0xFFB5A3D4)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
