package com.example.apneamonitor.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = OffWhite,
    onPrimary = MidnightBlue,
    secondary = GlassGlow,
    onSecondary = MidnightBlue,
    tertiary = SoftPurple,
    background = BackdropStart,
    onBackground = OffWhite,
    surface = GlassSurfaceStrong,
    onSurface = OffWhite,
    surfaceVariant = GlassSurface,
    onSurfaceVariant = MutedText,
    error = CoralRed,
    outline = GlassBorder
)

@Composable
fun ApneaMonitorTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    // Explicitly use DarkColorScheme as requested for the clinical dark mode
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
