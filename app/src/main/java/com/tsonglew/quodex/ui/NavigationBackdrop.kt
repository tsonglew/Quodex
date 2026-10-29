package com.tsonglew.quodex.ui

import android.os.Build
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot

/** Only the page is recorded: the dock must never sample its own glass or labels. */
@Stable
internal class NavigationBackdrop(val layer: GraphicsLayer) {
    var origin by mutableStateOf(Offset.Zero)
    val supportsBlur: Boolean get() = Build.VERSION.SDK_INT >= 31
}

internal fun Modifier.navigationBackdropSource(backdrop: NavigationBackdrop): Modifier =
    if (!backdrop.supportsBlur) this else onGloballyPositioned {
        backdrop.origin = it.positionInRoot()
    }.drawWithContent {
        backdrop.layer.record { this@drawWithContent.drawContent() }
        drawLayer(backdrop.layer)
    }
