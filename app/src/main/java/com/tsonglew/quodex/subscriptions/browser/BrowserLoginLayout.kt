package com.tsonglew.quodex.subscriptions.browser

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Keep the website outside Compose's virtual autofill tree so password managers see its fields. */
internal class BrowserLoginLayout(context: Context) : LinearLayout(context) {
    private var imeVisible by mutableStateOf(false)
    val compactChrome: Boolean get() = imeVisible || resources.configuration.fontScale >= 1.5f
    val header = ComposeView(context).apply { id = View.generateViewId() }
    val browserContainer = FrameLayout(context).apply { id = View.generateViewId() }
    val footer = ComposeView(context).apply { id = View.generateViewId() }

    init {
        orientation = VERTICAL
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(browserContainer, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(footer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
    }
}
