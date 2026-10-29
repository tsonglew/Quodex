package com.tsonglew.quodex

import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import kotlin.math.abs

/** Inspect the real action leaves after RemoteViews and their layout have settled. */
internal fun assertWidgetActionLayout(widget: View, refreshDescription: String = "刷新额度", openSuffix: String = "打开 Quodex") {
    // API29/30 use Glance's separate emulated-Button path, exercised by the
    // same real touch tests without requiring the API31+ resource replacement.
    if (Build.VERSION.SDK_INT < 31) return
    fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) {
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
    } else emptyList()
    val views = descendants(widget)
    val targets = views.filter { it.isShown && it.hasOnClickListeners() }
    val open = targets.filter { it.contentDescription?.toString()?.endsWith(openSuffix) == true }
    val refresh = targets.filter { it.contentDescription?.toString() == refreshDescription }
    val description = targets.joinToString("\n") {
        "${it.javaClass.simpleName}: id=${it.id}, description=${it.contentDescription}, size=${it.width}x${it.height}"
    }
    assertTrue("At least one open-App action must be present\n$description", open.isNotEmpty())
    assertEquals("Refresh must have one distinct action\n$description", 1, refresh.size)
    assertEquals("Every clickable widget view must identify its action\n$description", open.size + refresh.size, targets.size)
    assertTrue("Clickable ripple ImageViews must not cover widget actions",
        views.none { it is ImageView && it.hasOnClickListeners() })
    val bounds = targets.map { target ->
        // Button extends TextView: an `is TextView` check would accept the
        // launcher-repainted Button that this resource workaround replaces.
        assertEquals("Widget actions must be direct TextView leaves\n$description", TextView::class.java, target.javaClass)
        assertTrue("Action IDs must be valid\n$description", target.id > 0)
        assertTrue("Action must support touch and keyboard focus\n$description", target.isEnabled && target.isFocusable)
        val visible = Rect()
        assertTrue("Action must have visible bounds\n$description", target.getLocalVisibleRect(visible) && !visible.isEmpty)
        val screen = IntArray(2).also(target::getLocationOnScreen)
        visible.offset(screen[0], screen[1])
        visible
    }
    assertEquals("Open and refresh action IDs must be unique within each widget\n$description",
        targets.size, targets.map { it.id }.distinct().size)
    for (first in bounds.indices) for (second in first + 1 until bounds.size) {
        assertFalse("Widget actions must not cover each other: ${bounds[first]} / ${bounds[second]}\n$description",
            Rect.intersects(bounds[first], bounds[second]))
    }
}

/** Apply the platform font scale before creating the host and restore it after teardown. */
internal fun withWidgetFontScale(scale: Float, block: () -> Unit) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
        java.io.FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText().trim() }
    }
    fun awaitScale(expected: Float, scenario: ActivityScenario<MainActivity>) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        var activityScale = Float.NaN
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            scenario.onActivity { activityScale = it.resources.configuration.fontScale }
            if (abs(context.resources.configuration.fontScale - expected) <= 0.01f &&
                abs(activityScale - expected) <= 0.01f) break
            Thread.sleep(100)
        }
        assertTrue("Platform font scale must be $expected, context=${context.resources.configuration.fontScale}, activity=$activityScale",
            abs(context.resources.configuration.fontScale - expected) <= 0.01f && abs(activityScale - expected) <= 0.01f)
    }
    val savedSetting = shell("settings get system font_scale").toFloatOrNull()
    var savedScale = savedSetting ?: context.resources.configuration.fontScale
    fun changeScale(command: String, expected: Float, captureDefault: Boolean = false) {
        // Android can defer configuration delivery while the test process has no
        // foreground Activity. Resume one for both application and restoration;
        // close it before the widget test creates monitors or HTTP baselines.
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            if (captureDefault && savedSetting == null)
                scenario.onActivity { savedScale = it.resources.configuration.fontScale }
            shell(command)
            awaitScale(expected, scenario)
        }
    }
    try {
        changeScale("settings put system font_scale $scale", scale, captureDefault = true)
        block()
    } finally {
        changeScale(if (savedSetting == null) "settings delete system font_scale" else "settings put system font_scale $savedSetting",
            savedScale)
    }
}
