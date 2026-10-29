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

// Neutral surfaces and a blue accent, shared by Compose and Glance.
private val DashboardLightColors = lightColorScheme(
    primary = Color(0xFF0065D0), onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E7FF), onPrimaryContainer = Color(0xFF001B3F),
    secondary = Color(0xFF555F71), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4EAF5), onSecondaryContainer = Color(0xFF192331),
    tertiary = Color(0xFF685581), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEFDBFF), onTertiaryContainer = Color(0xFF231035),
    background = Color(0xFFF1EFF2), onBackground = Color(0xFF1B1C20),
    surface = Color.White, onSurface = Color(0xFF1B1C20),
    surfaceVariant = Color(0xFFE7E5EA), onSurfaceVariant = Color(0xFF464B59),
    outline = Color(0xFF777B86), outlineVariant = Color(0xFFD4D4DE),
    surfaceTint = Color(0xFF0065D0), surfaceBright = Color.White, surfaceDim = Color(0xFFDEDEE5),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFCF9FC),
    surfaceContainer = Color(0xFFF0EDF2), surfaceContainerHigh = Color(0xFFECEAF0),
    surfaceContainerHighest = Color(0xFFE3E1E8),
    inversePrimary = Color(0xFF9EC5FF), inverseSurface = Color(0xFF1B1C20), inverseOnSurface = Color(0xFFF1EFF2),
    error = Color(0xFFBA1A1A), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    scrim = Color.Black,
)

private val DashboardDarkColors = darkColorScheme(
    primary = Color(0xFF9EC5FF), onPrimary = Color(0xFF003263),
    primaryContainer = Color(0xFF164B82), onPrimaryContainer = Color(0xFFD9E7FF),
    secondary = Color(0xFFBDC7DC), onSecondary = Color(0xFF273141),
    secondaryContainer = Color(0xFF3E4859), onSecondaryContainer = Color(0xFFD9E7FF),
    tertiary = Color(0xFFD5BDEB), onTertiary = Color(0xFF39264F),
    tertiaryContainer = Color(0xFF503D66), onTertiaryContainer = Color(0xFFEFDBFF),
    background = Color(0xFF121316), onBackground = Color(0xFFF1EFF2),
    surface = Color(0xFF202125), onSurface = Color(0xFFF1EFF2),
    surfaceVariant = Color(0xFF44464F), onSurfaceVariant = Color(0xFFC5C6D0),
    outline = Color(0xFF8F909B), outlineVariant = Color(0xFF444650),
    surfaceTint = Color(0xFF9EC5FF), surfaceBright = Color(0xFF38393F), surfaceDim = Color(0xFF121316),
    surfaceContainerLowest = Color(0xFF0C0D10), surfaceContainerLow = Color(0xFF191A1F),
    surfaceContainer = Color(0xFF1B1C20), surfaceContainerHigh = Color(0xFF2A2B31),
    surfaceContainerHighest = Color(0xFF35363D),
    inversePrimary = Color(0xFF0065D0), inverseSurface = Color(0xFFF1EFF2), inverseOnSurface = Color(0xFF1B1C20),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color.Black,
)

internal fun quodexColorScheme(dark: Boolean): ColorScheme =
    if (dark) DashboardDarkColors else DashboardLightColors

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
