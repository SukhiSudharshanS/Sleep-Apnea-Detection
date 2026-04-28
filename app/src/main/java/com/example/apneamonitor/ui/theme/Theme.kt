package com.example.apneamonitor.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Cyan,
    onPrimary = MidnightBlue,
    secondary = CyanDim,
    onSecondary = MidnightBlue,
    tertiary = SoftPurple,
    background = MidnightBlue,
    onBackground = Color.White,
    surface = DeepNavy,
    onSurface = Color.White,
    surfaceVariant = SurfaceLighter,
    onSurfaceVariant = Color.LightGray,
    error = CoralRed,
    outline = Color.Gray.copy(alpha = 0.5f)
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