package com.tsonglew.quodex.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A compact Android dock with room for the system gesture area. */
@Composable
internal fun FloatingNavigation(
    destinations: List<Triple<String, String, ImageVector>>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Box(modifier.fillMaxWidth()
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
        .padding(horizontal = 24.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(50), color = colors.surface,
            border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.25f)), shadowElevation = 8.dp,
            modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth().testTag("floating-navigation")) {
            Row(Modifier.padding(5.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                destinations.forEach { (id, label, icon) ->
                    val active = selected == id
                    val background by animateColorAsState(if (active) colors.surfaceContainerHigh else Color.Transparent,
                        label = "navigation-selection")
                    Surface(color = background, shape = RoundedCornerShape(50), modifier = Modifier.weight(1f)) {
                        Column(Modifier.clip(RoundedCornerShape(50))
                            .selectable(active, role = Role.Tab, onClick = { onSelect(id) })
                            .testTag("tab-$id").heightIn(min = 56.dp).padding(horizontal = 6.dp, vertical = 7.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Icon(icon, null, Modifier.size(24.dp), tint = if (active) colors.primary else colors.onSurfaceVariant)
                            Text(label, fontSize = 12.sp, lineHeight = 16.sp,
                                color = if (active) colors.primary else colors.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
