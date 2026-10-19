package com.frit.app.ui.theme

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

private val LightColorScheme = lightColorScheme(
    primary          = FritEspresso,
    onPrimary        = FritCream,
    primaryContainer = FritEspresso,
    onPrimaryContainer = FritCream,
    secondary        = FritEspresso,
    onSecondary      = FritCream,
    secondaryContainer = FritEspresso.copy(alpha = 0.14f),
    onSecondaryContainer = FritEspresso,
    tertiary         = FritPositive,
    onTertiary       = FritCream,
    tertiaryContainer = FritPositive.copy(alpha = 0.14f),
    onTertiaryContainer = FritEspresso,
    background       = FritCream,
    onBackground     = FritTextPrimary,
    surface          = FritSurface,
    onSurface        = FritTextPrimary,
    surfaceVariant   = FritSurface,
    onSurfaceVariant = FritTextSecondary,
    surfaceDim       = FritCream,
    surfaceBright    = FritSurface,
    surfaceContainerLowest = FritCream,
    surfaceContainerLow    = FritSurface,
    surfaceContainer       = FritSurface,
    surfaceContainerHigh   = FritSurface,
    surfaceContainerHighest = FritSurface,
    outline          = FritStroke,
    outlineVariant   = FritStroke,
    error            = FritNegative,
    onError          = FritCream,
    errorContainer   = FritNegative.copy(alpha = 0.12f),
    onErrorContainer = FritNegative,
    inverseSurface   = FritEspresso,
    inverseOnSurface = FritCream,
    inversePrimary   = FritCream,
    scrim            = FritEspresso.copy(alpha = 0.32f),
)

private val DarkColorScheme = darkColorScheme(
    primary          = FritCream,
    onPrimary        = Color(0xFF111110),
    primaryContainer = FritCream,
    onPrimaryContainer = Color(0xFF111110),
    secondary        = FritCream,
    onSecondary      = Color(0xFF111110),
    secondaryContainer = FritCream.copy(alpha = 0.16f),
    onSecondaryContainer = FritCream,
    tertiary         = FritPositive,
    onTertiary       = Color(0xFF111110),
    tertiaryContainer = FritPositive.copy(alpha = 0.22f),
    onTertiaryContainer = FritCream,
    background       = Color(0xFF111110),
    onBackground     = FritCream,
    surface          = Color(0xFF1C1A18),
    onSurface        = FritCream,
    surfaceVariant   = Color(0xFF1C1A18),
    onSurfaceVariant = Color(0xFFA89E96),
    surfaceDim       = Color(0xFF111110),
    surfaceBright    = Color(0xFF24211F),
    surfaceContainerLowest = Color(0xFF111110),
    surfaceContainerLow    = Color(0xFF1C1A18),
    surfaceContainer       = Color(0xFF1C1A18),
    surfaceContainerHigh   = Color(0xFF24211F),
    surfaceContainerHighest = Color(0xFF2A2724),
    outline          = FritCream.copy(alpha = 0.22f),
    outlineVariant   = FritCream.copy(alpha = 0.12f),
    error            = Color(0xFFE08073),
    onError          = Color(0xFF111110),
    errorContainer   = Color(0xFFE08073).copy(alpha = 0.16f),
    onErrorContainer = FritCream,
    inverseSurface   = FritCream,
    inverseOnSurface = FritEspresso,
    inversePrimary   = FritEspresso,
    scrim            = Color.Black.copy(alpha = 0.5f),
)

@Composable
fun FritTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography  = MaterialTheme.typography,
        content     = content
    )
}
