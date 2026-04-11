package com.ysxq.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = SakuraPrimary,
    onPrimary = DarkBackground,
    primaryContainer = Lavender,
    onPrimaryContainer = TextPrimary,
    secondary = SkyBlue,
    onSecondary = DarkBackground,
    secondaryContainer = SkyBlueDark,
    onSecondaryContainer = SkyBlueLight,
    tertiary = Lavender,
    background = DarkBackground,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = TextTertiary,
    outlineVariant = DarkSurfaceVariant,
    inverseSurface = TextPrimary,
    inverseOnSurface = DarkBackground,
)

@Composable
fun YsxqAppTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = DarkColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        val window = (view.context as Activity).window
        SideEffect {
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = DarkBackground.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
