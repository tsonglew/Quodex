package com.tsonglew.quotapal

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercise the PendingIntent installed in real RemoteViews, including logged-out widgets. */
class WidgetOpenAppDeviceTest {
    @Test fun launcherEntryStartsFromNonActivityContext() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext.applicationContext
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        var opened: Activity? = null
        try {
            // A PendingIntent sender can hide missing task flags. Starting through
            // an application Context also checks the intent's explicit task boundary.
            context.startActivity(com.tsonglew.quotapal.widget.widgetLaunchIntent(context))
            opened = monitor.waitForActivityWithTimeout(10_000)
            assertTrue("Main entry must support a non-Activity caller", opened is MainActivity)
        } finally {
            opened?.let { activity -> instrumentation.runOnMainSync { activity.finish() } }
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test fun standardWidgetBodyOpensAppWithoutAccount() = checkBodyOpensApp(slim = false)

    @Test fun slimWidgetBodyOpensAppWithQuota() = checkBodyOpensApp(slim = true)

    @Test fun standardQuotaValueOpensApp() = checkBodyOpensApp(slim = false, quotaValue = true)

    @Test fun minimumSlimWidgetBodyOpensApp() = checkBodyOpensApp(slim = true, width = 130, height = 50)

    @Test fun narrowSlimWidgetBodyOpensApp() = checkBodyOpensApp(slim = true, width = 130, height = 70)

    @Test fun minimumSlimQuotaValueOpensApp() = checkBodyOpensApp(slim = true, quotaValue = true, width = 130, height = 50)

    private fun checkBodyOpensApp(slim: Boolean, quotaValue: Boolean = false, width: Int = 280,
        height: Int = if (slim) 70 else 150) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.quotaApp
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        var opened: Activity? = null
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.BIND_APPWIDGET)
        try {
            runBlocking {
                app.repository.logout()
                if (slim || quotaValue) app.repository.demo()
            }
            ActivityScenario.launch<WidgetTestHostActivity>(
                Intent(context, WidgetTestHostActivity::class.java)
                    .putExtra("slim", slim).putExtra("width", width)
                    .putExtra("height", height),
            ).use { scenario ->
                runBlocking { app.updateWidgets() }
                val deadline = SystemClock.uptimeMillis() + 20_000
                var bounds: Rect? = null
                var stableView: View? = null
                var stableAction: View? = null
                var stableBounds: Rect? = null
                var stableSince = 0L
                var recoveredLauncher = false
                while (bounds == null && SystemClock.uptimeMillis() < deadline) {
                    instrumentation.waitForIdleSync()
                    var focused = false
                    scenario.onActivity { host ->
                        focused = host.hasWindowFocus()
                        // Tap visible body content, away from the refresh action.
                        // No performClick or direct Activity launch: dispatch must reach
                        // the widget body listener through the actual child TextView.
                        val title = descendants(host.widgetView).filterIsInstance<TextView>()
                            .firstOrNull { if (quotaValue) it.text.toString() == "62%" else it.text.toString().startsWith("Codex") }
                        val action = title?.let {
                            generateSequence<View>(it) { child -> child.parent as? View }
                                .takeWhile { child -> child !== host.widgetView }
                                .firstOrNull { child -> child.hasOnClickListeners() }
                        }
                        val hasRefresh = descendants(host.widgetView).any { it.contentDescription == "刷新额度" }
                        val visible = Rect()
                        if (focused && title != null && action != null && hasRefresh && title.isShown &&
                            title.getLocalVisibleRect(visible) && !visible.isEmpty) {
                            val screen = IntArray(2)
                            title.getLocationOnScreen(screen)
                            visible.offset(screen[0], screen[1])
                            val now = SystemClock.uptimeMillis()
                            if (title !== stableView || action !== stableAction || visible != stableBounds) {
                                stableView = title
                                stableAction = action
                                stableBounds = Rect(visible)
                                stableSince = now
                            }
                            if (now - stableSince >= 500 && now - host.lastWidgetUpdateAt >= 500) bounds = visible
                        } else {
                            stableView = null
                            stableAction = null
                            stableBounds = null
                        }
                    }
                    if (!focused && !recoveredLauncher) recoveredLauncher = dismissLauncherAnrOnEmulator(instrumentation)
                    if (bounds == null) Thread.sleep(50)
                }
                if (bounds == null) instrumentation.uiAutomation.takeScreenshot()?.let {
                    saveDeviceScreenshot(context, "widget-open-app-missing-target-$slim", it)
                }
                assertNotNull("Widget title must be visible and stable (slim=$slim)", bounds)
                val target = requireNotNull(bounds)
                val downTime = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                        target.exactCenterX(), target.exactCenterY(), 0)
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
                    finally { event.recycle() }
                    if (action == MotionEvent.ACTION_DOWN) Thread.sleep(50)
                }
                opened = monitor.waitForActivityWithTimeout(10_000)
                var detail = ""
                if (opened !is MainActivity) {
                    instrumentation.uiAutomation.takeScreenshot()?.let {
                        saveDeviceScreenshot(context, "widget-open-app-no-launch-$slim", it)
                    }
                    scenario.onActivity { host ->
                        detail = "bounds=$target; touches=${host.touchEvents}; focus=${host.hasWindowFocus()}; " +
                            "tree=" + descendants(host.widgetView).joinToString { view ->
                                val screen = IntArray(2).also(view::getLocationOnScreen)
                                "${view.javaClass.simpleName}(text=${(view as? TextView)?.text}, clickable=${view.hasOnClickListeners()}, shown=${view.isShown}, enabled=${view.isEnabled}, bounds=${screen.toList()}+${view.width}x${view.height})"
                            }
                    }
                }
                assertTrue("Widget body must launch QuotaPal MainActivity (slim=$slim); $detail; diagnostics=${app.diagnostics.report()}", opened is MainActivity)
            }
        } finally {
            opened?.let { activity -> instrumentation.runOnMainSync { activity.finish() } }
            instrumentation.removeMonitor(monitor)
            runBlocking { app.repository.logout(); app.reconcileSync() }
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
