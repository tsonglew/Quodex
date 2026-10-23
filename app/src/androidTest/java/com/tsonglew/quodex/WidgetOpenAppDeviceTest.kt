package com.tsonglew.quodex

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
import com.tsonglew.quodex.data.Session
import com.tsonglew.quodex.data.absoluteTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
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
            context.startActivity(com.tsonglew.quodex.widget.widgetLaunchIntent(context))
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

    @Test fun slimWidgetTimestampOpensAppWithoutRefreshing() = checkBodyOpensApp(slim = true, footerTimestamp = true)

    @Test fun largeFontStandardQuotaValueOpensApp() = withWidgetFontScale(2f) {
        checkBodyOpensApp(slim = false, quotaValue = true, width = 140)
    }

    @Test fun largeFontSlimQuotaValueOpensApp() = withWidgetFontScale(2f) {
        checkBodyOpensApp(slim = true, quotaValue = true, width = 130)
    }

    private fun checkBodyOpensApp(slim: Boolean, quotaValue: Boolean = false, width: Int = 280,
        footerTimestamp: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.quotaApp as TestQuotaApplication
        val createdWidgetIds = mutableSetOf<Int>()
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        var opened: Activity? = null
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.BIND_APPWIDGET)
        try {
            runBlocking {
                app.repository.logout()
                if (footerTimestamp) {
                    app.usedPercent = 38
                    app.repository.connect(Session("test-access", "test-refresh", "test-account", Long.MAX_VALUE))
                } else if (slim || quotaValue) app.repository.demo()
            }
            val timestamp = if (footerTimestamp)
                absoluteTime(requireNotNull(app.repository.state.value.snapshot).fetchedAt, true) else null
            ActivityScenario.launch<WidgetTestHostActivity>(
                Intent(context, WidgetTestHostActivity::class.java)
                    .putExtra("slim", slim).putExtra("width", width)
                    .putExtra("height", if (slim) 70 else 150),
            ).use { scenario ->
                scenario.onActivity { createdWidgetIds += it.widgetId }
                runBlocking {
                    // Seed the fixture's own instance even when an emulator retains
                    // preferences for this ID or uses a different global preference.
                    createdWidgetIds.forEach { app.settings.saveWidget(it, true, "system") }
                    app.updateWidgets()
                }
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
                        // Keep the touch on the visible title/value/timestamp, not the
                        // center of the action. Its listener is a sibling overlay,
                        // so searching the TextView's ancestors would miss it.
                        val views = descendants(host.widgetView).toList()
                        val title = views.filterIsInstance<TextView>()
                            .firstOrNull { when {
                                timestamp != null -> it.text.toString().contains(timestamp)
                                quotaValue -> it.text.toString() == "62%"
                                else -> it.text.toString().startsWith("Codex")
                            } }
                        val visible = title?.let(::screenVisibleBounds)
                        val action = visible?.let { textBounds ->
                            views.filter { it.hasOnClickListeners() && it.isEnabled && it.contentDescription != "刷新额度" }
                                .mapNotNull { candidate -> screenVisibleBounds(candidate)?.let { candidate to it } }
                                .filter { (_, actionBounds) -> actionBounds.contains(textBounds.centerX(), textBounds.centerY()) }
                                .minByOrNull { (_, actionBounds) -> actionBounds.width().toLong() * actionBounds.height() }
                                ?.first
                        }
                        val hasRefresh = views.any { it.contentDescription == "刷新额度" }
                        if (focused && title != null && action != null && hasRefresh) {
                            val now = SystemClock.uptimeMillis()
                            if (title !== stableView || action !== stableAction || visible != stableBounds) {
                                stableView = title
                                stableAction = action
                                stableBounds = Rect(visible)
                                stableSince = now
                            }
                            if (now - stableSince >= 500 && now - host.lastWidgetUpdateAt >= 500) {
                                assertWidgetActionLayout(host.widgetView)
                                bounds = visible
                            }
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
                assertNotNull("Widget body text and its covering action must be visible and stable (slim=$slim, width=$width)", bounds)
                val target = requireNotNull(bounds)
                val requestsBeforeTap = if (footerTimestamp) {
                    // A mistaken refresh must be observable: a demo account or the
                    // recent-attempt guard would otherwise hide an extra request.
                    // MainActivity's ordinary foreground refresh still uses the
                    // fresh cache, whereas the widget refresh forces a new request.
                    runBlocking { app.settings.syncResult(null, lastAttemptAt = 0) }
                    app.usageRequests.get()
                } else null
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
                assertTrue("Widget body must launch Quodex MainActivity (slim=$slim); $detail; diagnostics=${app.diagnostics.report()}", opened is MainActivity)
                if (requestsBeforeTap != null) {
                    val observationDeadline = SystemClock.uptimeMillis() + 1_000
                    do {
                        assertEquals("Tapping the footer timestamp must not activate refresh",
                            requestsBeforeTap, app.usageRequests.get())
                        Thread.sleep(50)
                    } while (SystemClock.uptimeMillis() < observationDeadline)
                }
            }
        } finally {
            opened?.let { activity -> instrumentation.runOnMainSync { activity.finish() } }
            instrumentation.removeMonitor(monitor)
            runBlocking {
                createdWidgetIds.forEach { app.settings.deleteWidget(it) }
                app.repository.logout()
                app.reconcileSync()
            }
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun screenVisibleBounds(view: View): Rect? {
        val visible = Rect()
        if (!view.isShown || !view.getLocalVisibleRect(visible) || visible.isEmpty) return null
        val screen = IntArray(2)
        view.getLocationOnScreen(screen)
        visible.offset(screen[0], screen[1])
        return visible
    }
}
