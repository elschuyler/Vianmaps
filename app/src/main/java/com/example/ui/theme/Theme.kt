package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Consistent White & Sky Blue color scheme enforced across both system light and dark modes
private val SkyBlueWhiteColorScheme = lightColorScheme(
    primary = SkyBluePrimary,
    onPrimary = Color.White,
    primaryContainer = SkyBlueLight,
    onPrimaryContainer = SkyBlueDim,
    secondary = SkyBluePrimary,
    onSecondary = Color.White,
    secondaryContainer = SkyBlueLight,
    onSecondaryContainer = SkyBlueDim,
    tertiary = SkyBlueDim,
    onTertiary = Color.White,
    background = SurfaceWhite,
    onBackground = TextDarkPrimary,
    surface = SurfaceWhite,
    onSurface = TextDarkPrimary,
    surfaceVariant = SurfaceOffWhite,
    onSurfaceVariant = TextDarkSecondary,
    outline = SkyBlueBorder,
    outlineVariant = SurfaceBorderLight
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = false, // Ignored: System theme must not alter the app's visual appearance
    dynamicColor: Boolean = false, // Ignored: Dynamic color disabled to strictly preserve Sky Blue & White branding
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = SkyBlueWhiteColorScheme,
        typography = Typography,
        content = content
    )
}
