package com.tsonglew.quodex.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp

/** A quiet scene derived from the existing blue/violet palette, shared by every page. */
internal fun glassSceneColors(dark: Boolean): List<Color> {
    val colors = quodexColorScheme(dark)
    return listOf(colors.background, colors.primaryContainer, colors.tertiaryContainer,
        colors.secondaryContainer, colors.background)
}

/** Record only the scene, never the panels that replay it (no display-list feedback). */
@Composable
internal fun Modifier.glassSceneBackground(): Modifier {
    val source = LocalGlassScene.current ?: return this
    val environment = LocalGlassEnvironment.current
    val colors = glassSceneColors(environment.dark)
    val reduced = environment.preferences.reduceTransparency || environment.highContrast
    return onGloballyPositioned { source.origin = it.localToScreen(Offset.Zero) }.drawWithCache {
        val brush = Brush.linearGradient(colors, start = Offset.Zero, end = Offset(size.width, size.height))
        var recorded = false
        onDrawWithContent {
            if (!recorded) {
                source.layer.record {
                    if (reduced) drawRect(colors.first()) else drawRect(brush)
                }
                recorded = true
                Snapshot.withoutReadObservation { source.generation++ }
            }
            drawLayer(source.layer)
            drawContent()
        }
    }
}

/** The existing Material Surface keeps layout, shape, touch handling and content semantics. */
@Composable
internal fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(color),
    content: @Composable () -> Unit,
) {
    val material = rememberGlassSurface("page-panels", shape, source = LocalGlassScene.current)
    Surface(modifier = modifier.then(material.modifier), shape = shape, color = Color.Transparent,
        contentColor = contentColor, tonalElevation = 0.dp, content = content)
}
