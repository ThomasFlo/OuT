package com.homestock.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = SteelBlue,
    onPrimary = Color.White,
    secondary = Teal,
    onSecondary = Color.White,
    tertiary = Amber,
    onTertiary = SurfaceDark,
    background = SurfaceDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = CardDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    error = ErrorRed,
)

private val LightColors = lightColorScheme(
    primary = SteelBlue,
    secondary = Teal,
    tertiary = Amber,
)

@Composable
fun HomeStockTheme(
    darkTheme: Boolean = true, // Dark mode is the default per design spec.
    content: @Composable () -> Unit,
) {
    val useDark = darkTheme || isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (useDark) DarkColors else LightColors,
        typography = HomeStockTypography,
        content = content,
    )
}
