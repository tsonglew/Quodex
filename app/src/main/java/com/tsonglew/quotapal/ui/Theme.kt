package com.tsonglew.quotapal.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import android.app.Activity

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
    val colors = if (dark) darkColorScheme(
        primary = Color(0xFFA3C9FF), onPrimary = Color(0xFF07305F),
        primaryContainer = Color(0xFF214B79), onPrimaryContainer = Color(0xFFD4E5FF),
        secondary = Color(0xFFBDD1E6), onSecondary = Color(0xFF263E53),
        secondaryContainer = Color(0xFF374E65), onSecondaryContainer = Color(0xFFDAE7F4),
        tertiary = Color(0xFFEFC16B), onTertiary = Color(0xFF402D00),
        tertiaryContainer = Color(0xFF5C420A), onTertiaryContainer = Color(0xFFFFDEA4),
        background = Color(0xFF101113), surface = Color(0xFF1A1C20),
        surfaceVariant = Color(0xFF24272C), onBackground = Color(0xFFF3F4F6), onSurface = Color(0xFFF3F4F6),
        onSurfaceVariant = Color(0xFFB8BEC8), outline = Color(0xFF828D9C), outlineVariant = Color(0xFF42474F),
        surfaceTint = Color(0xFFA3C9FF), surfaceBright = Color(0xFF36393E), surfaceDim = Color(0xFF101113),
        surfaceContainerLowest = Color(0xFF0C0E10), surfaceContainerLow = Color(0xFF17191D),
        surfaceContainer = Color(0xFF1E2125), surfaceContainerHigh = Color(0xFF292C31), surfaceContainerHighest = Color(0xFF34373D),
    ) else lightColorScheme(
        primary = Color(0xFF2166B6), onPrimary = Color.White,
        primaryContainer = Color(0xFFD8E7FF), onPrimaryContainer = Color(0xFF102D50),
        secondary = Color(0xFF3D607F), onSecondary = Color.White,
        secondaryContainer = Color(0xFFDAE7F4), onSecondaryContainer = Color(0xFF142D45),
        tertiary = Color(0xFF7D570B), onTertiary = Color.White,
        tertiaryContainer = Color(0xFFFFDEA4), onTertiaryContainer = Color(0xFF271900),
        background = Color(0xFFF5F6F8), surface = Color.White,
        surfaceVariant = Color(0xFFEBEDF1), onBackground = Color(0xFF17191D), onSurface = Color(0xFF17191D),
        onSurfaceVariant = Color(0xFF535D6A), outline = Color(0xFF727D8C), outlineVariant = Color(0xFFD0D5DD),
        surfaceTint = Color(0xFF2166B6), surfaceBright = Color.White, surfaceDim = Color(0xFFDADDE2),
        surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF5F6F8),
        surfaceContainer = Color(0xFFEFF1F4), surfaceContainerHigh = Color(0xFFE9ECF0), surfaceContainerHighest = Color(0xFFE2E6EB),
    )
    MaterialTheme(colorScheme = colors, content = content)
}
