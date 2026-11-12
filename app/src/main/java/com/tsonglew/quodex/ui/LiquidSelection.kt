package com.tsonglew.quodex.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import kotlin.math.abs
import kotlin.math.min

/** One decorative surface follows real item bounds; the controls keep their own hit targets. */
@Composable
internal fun <T : Any> LiquidSelectionGroup(
    selected: T,
    color: Color,
    modifier: Modifier = Modifier,
    outlineColor: Color = Color.Transparent,
    content: @Composable (itemModifier: (T) -> Modifier) -> Unit,
) {
    val bounds = remember { mutableStateMapOf<T, Rect>() }
    var container by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val indicator = remember { Animatable(Rect.Zero, Rect.VectorConverter) }
    var initialized by remember { mutableStateOf(false) }
    var previousSelection by remember { mutableStateOf<T?>(null) }
    var previousLayout by remember { mutableStateOf<Map<T, Rect>>(emptyMap()) }
    val layout = bounds.toMap()
    val target = layout[selected]

    LaunchedEffect(selected, layout) {
        val destination = target ?: return@LaunchedEffect
        val origin = layout[previousSelection]
        val sameRow = origin != null && abs(origin.center.y - destination.center.y) < 1f
        val animate = initialized && previousSelection != selected && previousLayout == layout && sameRow
        previousSelection = selected
        previousLayout = layout
        if (animate) {
            // Cancellation keeps the current position, so rapid taps can redirect the surface.
            indicator.animateTo(destination, spring(dampingRatio = 0.85f, stiffness = 650f,
                visibilityThreshold = Rect(0.5f, 0.5f, 0.5f, 0.5f)))
        } else {
            // Initial layout, resize, RTL changes and wrapped rows never fly across the page.
            indicator.snapTo(destination)
            initialized = true
        }
    }

    Box(modifier.onGloballyPositioned { container = it }.drawBehind {
        val rect = if (initialized) indicator.value else target
        if (rect != null && rect.width > 0f && rect.height > 0f) {
            // A small, bounded stretch follows speed and vanishes at rest. Read animation
            // frames only here: no per-frame layout, blur, shader, or content deformation.
            val stretch = if (initialized) min(abs(indicator.velocity.center.x) * 0.012f, rect.width * 0.07f) else 0f
            val left = (rect.left - stretch).coerceAtLeast(0f)
            val right = (rect.right + stretch).coerceAtMost(size.width)
            val inset = stretch * 0.12f
            val height = (rect.height - inset * 2f).coerceAtLeast(1f)
            drawRoundRect(color, Offset(left, rect.top + inset), Size((right - left).coerceAtLeast(1f), height),
                CornerRadius(height / 2f))
            if (outlineColor.alpha > 0f) {
                val stroke = 1.dp.toPx()
                drawRoundRect(outlineColor, Offset(left + stroke / 2f, rect.top + inset + stroke / 2f),
                    Size((right - left - stroke).coerceAtLeast(1f), (height - stroke).coerceAtLeast(1f)),
                    CornerRadius(height / 2f), style = Stroke(stroke))
            }
        }
    }) {
        content { item ->
            Modifier.onGloballyPositioned { child ->
                val parent = container
                if (parent != null && parent.isAttached && child.isAttached) {
                    val rect = parent.localBoundingBoxOf(child, clipBounds = false)
                    if (rect.width > 0f && rect.height > 0f) bounds[item] = rect
                }
            }
        }
    }
}
