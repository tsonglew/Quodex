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
    @Test fun standardWidgetBodyOpensAppWithoutAccount() = checkBodyOpensApp(slim = false)

    @Test fun slimWidgetBodyOpensAppWithQuota() = checkBodyOpensApp(slim = true)

    private fun checkBodyOpensApp(slim: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.quotaApp
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        var opened: Activity? = null
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.BIND_APPWIDGET)
        try {
            runBlocking {
                app.repository.logout()
                if (slim) app.repository.demo()
            }
            ActivityScenario.launch<WidgetTestHostActivity>(
                Intent(context, WidgetTestHostActivity::class.java)
                    .putExtra("slim", slim).putExtra("width", 280)
                    .putExtra("height", if (slim) 70 else 150),
            ).use { scenario ->
                runBlocking { app.updateWidgets() }
                val deadline = SystemClock.uptimeMillis() + 20_000
                var bounds: Rect? = null
                var stableView: View? = null
                var stableBounds: Rect? = null
                var stableSince = 0L
                var recoveredLauncher = false
                while (bounds == null && SystemClock.uptimeMillis() < deadline) {
                    instrumentation.waitForIdleSync()
                    var focused = false
                    scenario.onActivity { host ->
                        focused = host.hasWindowFocus()
                        // Tap the visible title itself, away from the refresh action.
                        // No performClick or direct Activity launch: dispatch must reach
                        // the widget body listener through the actual child TextView.
                        val title = descendants(host.widgetView).filterIsInstance<TextView>()
                            .firstOrNull { it.text.toString().startsWith("Codex") }
                        val visible = Rect()
                        if (focused && title != null && title.isShown &&
                            title.getLocalVisibleRect(visible) && !visible.isEmpty) {
                            val screen = IntArray(2)
                            title.getLocationOnScreen(screen)
                            visible.offset(screen[0], screen[1])
                            val now = SystemClock.uptimeMillis()
                            if (title !== stableView || visible != stableBounds) {
                                stableView = title
                                stableBounds = Rect(visible)
                                stableSince = now
                            }
                            if (now - stableSince >= 500 && now - host.lastWidgetUpdateAt >= 500) bounds = visible
                        } else {
                            stableView = null
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
                assertTrue("Widget body must launch QuotaPal MainActivity (slim=$slim)", opened is MainActivity)
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
