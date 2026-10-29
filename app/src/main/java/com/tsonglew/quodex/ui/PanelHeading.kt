package com.tsonglew.quodex.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared icon-led headings for the dashboard and preference panels. */
@Composable
internal fun PanelHeading(title: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Icon(icon, null, Modifier.padding(9.dp).size(18.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Text(title, Modifier.weight(1f).semantics { heading() }, fontSize = 15.sp,
            lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    }
}
