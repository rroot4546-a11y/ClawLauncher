package com.roox.clawlauncher.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Colors matching the video
val ClawRed = Color(0xFFE53935)
val ClawGreen = Color(0xFF4CAF50)
val ClawDarkBg = Color(0xFF121212)
val ClawCardBg = Color(0xFF1E1E1E)
val ClawCardBgLight = Color(0xFF2A2A2A)
val ClawTextPrimary = Color(0xFFFFFFFF)
val ClawTextSecondary = Color(0xFFB0B0B0)
val ClawAccent = Color(0xFFE53935)
val ClawYellow = Color(0xFFFFC107)
val ClawBlue = Color(0xFF2196F3)
val ClawOrange = Color(0xFFFF9800)

private val DarkColorScheme = darkColorScheme(
    primary = ClawRed,
    onPrimary = Color.White,
    secondary = ClawGreen,
    background = ClawDarkBg,
    surface = ClawCardBg,
    onBackground = ClawTextPrimary,
    onSurface = ClawTextPrimary,
    surfaceVariant = ClawCardBgLight,
    error = ClawRed,
)

@Composable
fun ClawLauncherTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = ClawDarkBg.toArgb()
            window.navigationBarColor = ClawDarkBg.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
