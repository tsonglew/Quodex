package com.tsonglew.quodex.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Material still owns layout, typography, focus, buttons, dismiss behavior and semantics. */
@Composable
internal fun GlassAlertDialog(
    surfaceId: String,
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    val shape = AlertDialogDefaults.shape
    val material = rememberGlassSurface(surfaceId, shape)
    val tokens = material.tokens
    val scheme = quodexColorScheme(tokens.lightInk).copy(onSurface = tokens.primary, onSurfaceVariant = tokens.secondary)
    CompositionLocalProvider(LocalGlassBackdrop provides null) {
        MaterialTheme(colorScheme = scheme) {
            AlertDialog(onDismissRequest = onDismissRequest, confirmButton = confirmButton,
                modifier = modifier.shadow(if (tokens.solid) 0.dp else 6.dp, shape, clip = false)
                    .then(material.modifier), dismissButton = dismissButton,
                title = title, text = text, shape = shape, containerColor = Color.Transparent,
                tonalElevation = 0.dp, titleContentColor = tokens.primary, textContentColor = tokens.secondary)
        }
    }
}

@Composable
internal fun GlassDropdownMenu(expanded: Boolean, onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    // Popup scale animation has its own compositing layer: no lens or color split.
    val material = rememberGlassSurface("background-brand-menu", MenuDefaults.shape, lens = false, active = expanded)
    val tokens = material.tokens
    MaterialTheme(colorScheme = quodexColorScheme(tokens.lightInk).copy(onSurface = tokens.primary, onSurfaceVariant = tokens.secondary)) {
        DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest, modifier = material.modifier,
            containerColor = Color.Transparent, tonalElevation = 0.dp, content = content)
    }
}
