package com.tsonglew.quodex.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A glass capsule over the scrolling page; the surrounding space stays transparent and touchable. */
@Composable
internal fun FloatingNavigation(
    destinations: List<Triple<String, String, ImageVector>>,
    selected: String,
    onSelect: (String) -> Unit,
    backdrop: NavigationBackdrop,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    val shape = RoundedCornerShape(50)
    var origin by remember { mutableStateOf(Offset.Zero) }
    val blurRadius = with(LocalDensity.current) { 16.dp.toPx() }
    val blur = remember(blurRadius) { BlurEffect(blurRadius, blurRadius, TileMode.Clamp) }
    val tint = colors.surface.copy(alpha = if (backdrop.supportsBlur) 0.58f else 0.88f)
    val rim = remember(dark) { Brush.linearGradient(listOf(
        Color.White.copy(alpha = if (dark) 0.45f else 0.92f),
        Color.White.copy(alpha = 0.08f),
        Color.White.copy(alpha = if (dark) 0.20f else 0.65f),
    )) }
    val selectedIndex by animateFloatAsState(destinations.indexOfFirst { it.first == selected }.coerceAtLeast(0).toFloat(),
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 450f), label = "glass-selection")
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    Box(modifier.fillMaxWidth()
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
        .padding(horizontal = 24.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.widthIn(max = 320.dp).fillMaxWidth()
            .testTag("floating-navigation").onGloballyPositioned { origin = it.positionInRoot() }
            .shadow(12.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.16f),
                spotColor = Color.Black.copy(alpha = 0.22f))
            .clip(shape).border(1.dp, rim, shape)) {
            if (backdrop.supportsBlur) {
                // Blur only the sampled backdrop. Icons, labels and the selection remain sharp.
                Canvas(Modifier.matchParentSize().graphicsLayer { renderEffect = blur }) {
                    val offset = origin - backdrop.origin
                    scale(1.035f) {
                        translate(-offset.x, -offset.y) { drawLayer(backdrop.layer) }
                    }
                }
            }
            Box(Modifier.matchParentSize().drawWithCache {
                val sheen = Brush.verticalGradient(listOf(
                    Color.White.copy(alpha = if (dark) 0.09f else 0.34f),
                    Color.Transparent,
                    Color.White.copy(alpha = if (dark) 0.025f else 0.10f),
                ))
                onDrawBehind { drawRect(tint); drawRect(sheen) }
            })
            Box(Modifier.padding(5.dp)) {
                Canvas(Modifier.matchParentSize()) {
                    val gap = 4.dp.toPx()
                    val itemWidth = (size.width - gap * (destinations.size - 1)) / destinations.size
                    val index = if (rtl) destinations.lastIndex - selectedIndex else selectedIndex
                    val left = (itemWidth + gap) * index
                    val corner = CornerRadius(size.height / 2)
                    val pillSize = Size(itemWidth, size.height)
                    drawRoundRect(colors.primary.copy(alpha = if (dark) 0.17f else 0.10f),
                        topLeft = Offset(left, 0f), size = pillSize, cornerRadius = corner)
                    drawRoundRect(Color.White.copy(alpha = if (dark) 0.13f else 0.55f),
                        topLeft = Offset(left + 0.5.dp.toPx(), 0.5.dp.toPx()),
                        size = Size(itemWidth - 1.dp.toPx(), size.height - 1.dp.toPx()),
                        cornerRadius = corner, style = Stroke(1.dp.toPx()))
                }
                Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    destinations.forEach { (id, label, icon) ->
                        val active = selected == id
                        val foreground by animateColorAsState(if (active) colors.primary else colors.onSurfaceVariant,
                            label = "navigation-color")
                        Column(Modifier.weight(1f).clip(shape)
                            .selectable(active, role = Role.Tab, onClick = { onSelect(id) })
                            .testTag("tab-$id").heightIn(min = 56.dp).padding(horizontal = 6.dp, vertical = 7.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Icon(icon, null, Modifier.size(24.dp), tint = foreground)
                            Text(label, fontSize = 12.sp, lineHeight = 16.sp, color = foreground)
                        }
                    }
                }
            }
        }
    }
}
