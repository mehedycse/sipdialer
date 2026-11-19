package com.noxsip.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = Indigo600,
    onPrimary = Color.White,
    primaryContainer = Navy100,
    onPrimaryContainer = Navy900,
    secondary = Sky500,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0F2FE),
    onSecondaryContainer = Color(0xFF0C4A6E),
    tertiary = Violet500,
    onTertiary = Color.White,
    background = Slate50,
    onBackground = Slate900,
    surface = Color.White,
    onSurface = Slate900,
    surfaceVariant = Color(0xFFE8EEF9),
    onSurfaceVariant = Slate600,
    surfaceContainer = Navy50,
    outline = Slate400,
    outlineVariant = Slate200,
    error = Red700,
    onError = Color.White,
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF5B8DEF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1F1F1F),
    onPrimaryContainer = Color(0xFFE4E7EE),
    secondary = Sky500,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF1F1F1F),
    onSecondaryContainer = Color(0xFFE4E7EE),
    tertiary = Violet500,
    onTertiary = Color.White,
    background = Color(0xFF000000),
    onBackground = Color(0xFFF4F4F5),
    surface = Color(0xFF0E0E0E),
    onSurface = Color(0xFFF4F4F5),
    surfaceVariant = Color(0xFF1F1F1F),
    onSurfaceVariant = Color(0xFFA1A1AA),
    surfaceContainer = Color(0xFF0E0E0E),
    outline = Color(0xFF52525B),
    outlineVariant = Color(0xFF333333),
    error = Red500,
    onError = Color.White,
)

@Composable
fun SipDialerTheme(
    darkTheme: Boolean = when (ThemeMode.mode) {
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        else -> false
    },
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = (if (darkTheme) Color(0xFF000000) else Color(0xFF1E3FA0)).toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            // নিচের নেভিগেশন বারের আইকন: লাইটে কালো, ডার্কে সাদা
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }

    // পুরো অ্যাপের লেখা বড় করা: সিস্টেম ফন্ট স্কেলের ওপর আমাদের গুণক (সর্বোচ্চ 1.6 পর্যন্ত)
    val baseDensity = LocalDensity.current
    val effectiveFontScale = (baseDensity.fontScale * UiTextScale.scale).coerceAtMost(1.6f)
    CompositionLocalProvider(
        LocalDensity provides Density(baseDensity.density, effectiveFontScale),
        LocalDarkTheme provides darkTheme
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
