package com.tsonglew.quodex.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Share the approved deep-teal palette with Glance widgets as well as Compose screens.
private val DeepTealLightColors = lightColorScheme(
    primary = Color(0xFF007C83), onPrimary = Color.White,
    primaryContainer = Color(0xFFBCEDEA), onPrimaryContainer = Color(0xFF002F32),
    secondary = Color(0xFF486568), onSecondary = Color.White,
    secondaryContainer = Color(0xFFEAF7F4), onSecondaryContainer = Color(0xFF213A3D),
    tertiary = Color(0xFF7D570B), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDEA4), onTertiaryContainer = Color(0xFF271900),
    background = Color(0xFFEEF8F7), onBackground = Color(0xFF112B30),
    surface = Color.White, onSurface = Color(0xFF112B30),
    surfaceVariant = Color(0xFFDCE9E7), onSurfaceVariant = Color(0xFF4B6265),
    outline = Color(0xFF6D8487), outlineVariant = Color(0xFFC1D4D1),
    surfaceTint = Color(0xFF007C83), surfaceBright = Color.White, surfaceDim = Color(0xFFD7E5E2),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF8FCFB),
    surfaceContainer = Color(0xFFF3F9F7), surfaceContainerHigh = Color(0xFFEDF7F5),
    surfaceContainerHighest = Color(0xFFE7F1EF),
    inversePrimary = Color(0xFF5CD8CD), inverseSurface = Color(0xFF112B30), inverseOnSurface = Color(0xFFEEF8F7),
    error = Color(0xFFBA1A1A), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    scrim = Color.Black,
)

private val DeepTealDarkColors = darkColorScheme(
    primary = Color(0xFF5CD8CD), onPrimary = Color(0xFF003734),
    primaryContainer = Color(0xFF005157), onPrimaryContainer = Color(0xFFA0F1E7),
    secondary = Color(0xFFB0CDCC), onSecondary = Color(0xFF1B3537),
    secondaryContainer = Color(0xFF324C4E), onSecondaryContainer = Color(0xFFD0E9E7),
    tertiary = Color(0xFFEFC16B), onTertiary = Color(0xFF402D00),
    tertiaryContainer = Color(0xFF5C420A), onTertiaryContainer = Color(0xFFFFDEA4),
    background = Color(0xFF0B1C20), onBackground = Color(0xFFEEF8F7),
    surface = Color(0xFF112B30), onSurface = Color(0xFFEEF8F7),
    surfaceVariant = Color(0xFF2C4447), onSurfaceVariant = Color(0xFFBCCFCC),
    outline = Color(0xFF869C9E), outlineVariant = Color(0xFF415A5D),
    surfaceTint = Color(0xFF5CD8CD), surfaceBright = Color(0xFF304C50), surfaceDim = Color(0xFF0B1C20),
    surfaceContainerLowest = Color(0xFF08171A), surfaceContainerLow = Color(0xFF0E2428),
    surfaceContainer = Color(0xFF112B30), surfaceContainerHigh = Color(0xFF1C363A),
    surfaceContainerHighest = Color(0xFF274145),
    inversePrimary = Color(0xFF007C83), inverseSurface = Color(0xFFEEF8F7), inverseOnSurface = Color(0xFF112B30),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color.Black,
)

internal fun quodexColorScheme(dark: Boolean): ColorScheme =
    if (dark) DeepTealDarkColors else DeepTealLightColors

@Composable
fun QuotaTheme(mode: String = "system", content: @Composable () -> Unit) {
    val dark = mode == "dark" || mode == "system" && isSystemInDarkTheme()
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = quodexColorScheme(dark), content = content)
}
