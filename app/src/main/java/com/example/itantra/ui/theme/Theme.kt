package com.example.itantra.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Fixed iTantra brand colors — same on every device
private val BrandPrimary = Color(0xFF0D47A1)
private val BrandOnPrimary = Color(0xFFFFFFFF)
private val BrandPrimaryContainer = Color(0xFFD6E3FF)
private val BrandOnPrimaryContainer = Color(0xFF001B3F)
private val BrandSecondary = Color(0xFF1E88E5)
private val BrandSecondaryContainer = Color(0xFFE3F2FD)
private val BrandOnSecondaryContainer = Color(0xFF001D36)
private val BrandTertiaryContainer = Color(0xFFFFE0B2)
private val BrandBackground = Color(0xFFF8F9FB)
private val BrandSurface = Color(0xFFFFFFFF)
private val BrandOnSurface = Color(0xFF1A1C1E)
private val BrandSurfaceVariant = Color(0xFFE1E2EC)
private val BrandOnSurfaceVariant = Color(0xFF44474F)
private val BrandOutline = Color(0xFFC4C6CF)
private val BrandError = Color(0xFFB3261E)
private val BrandOnError = Color(0xFFFFFFFF)

private val ITantraLightScheme = lightColorScheme(
    primary = BrandPrimary,
    onPrimary = BrandOnPrimary,
    primaryContainer = BrandPrimaryContainer,
    onPrimaryContainer = BrandOnPrimaryContainer,
    secondary = BrandSecondary,
    onSecondary = BrandOnPrimary,
    secondaryContainer = BrandSecondaryContainer,
    onSecondaryContainer = BrandOnSecondaryContainer,
    tertiaryContainer = BrandTertiaryContainer,
    background = BrandBackground,
    onBackground = BrandOnSurface,
    surface = BrandSurface,
    onSurface = BrandOnSurface,
    surfaceVariant = BrandSurfaceVariant,
    onSurfaceVariant = BrandOnSurfaceVariant,
    outline = BrandOutline,
    error = BrandError,
    onError = BrandOnError
)

private val ITantraDarkScheme = darkColorScheme(
    primary = Color(0xFFA8C8FF),
    onPrimary = Color(0xFF003060),
    primaryContainer = Color(0xFF004787),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFF90CAF9),
    onSecondary = Color(0xFF003258),
    secondaryContainer = Color(0xFF1E4976),
    onSecondaryContainer = Color(0xFFD1E4FF),
    tertiaryContainer = Color(0xFF5D4200),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E6),
    surface = Color(0xFF1A1C1E),
    onSurface = Color(0xFFE2E2E6),
    surfaceVariant = Color(0xFF44474F),
    onSurfaceVariant = Color(0xFFC4C6D0),
    outline = Color(0xFF8E9099),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410)
)

@Composable
fun ITantraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) ITantraDarkScheme else ITantraLightScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            try {
                val window = (view.context as Activity).window
                window.statusBarColor = colorScheme.primary.toArgb()
                window.navigationBarColor = colorScheme.surface.toArgb()
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
                WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = true
            } catch (_: Exception) {
                // Ignore — not an Activity context
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}