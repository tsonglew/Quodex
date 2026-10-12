package com.tsonglew.quotapal

import androidx.glance.GlanceId
import androidx.glance.action.actionParametersOf
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.tsonglew.quotapal.widget.RefreshWidgetAction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class WidgetRefreshActionDeviceTest {
    @Test fun realRemoteViewsClickFetchesOnceForThreeWidgets() = checkRefresh(slim = true)

    @Test fun standardWidgetRefreshFetchesWithoutOpeningApp() = checkRefresh(slim = false)

    @Test fun minimumSlimWidgetRefreshFetchesWithoutOpeningApp() = checkRefresh(slim = true, width = 130, height = 50)

    @Test fun narrowSlimWidgetRefreshFetchesWithoutOpeningApp() = checkRefresh(slim = true, width = 130, height = 70)

    private fun checkRefresh(slim: Boolean, width: Int = 280, height: Int = if (slim) 70 else 150) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.quotaApp as TestQuotaApplication
        val manager = WorkManager.getInstance(context)
        val activityMonitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        instrumentation.uiAutomation.adoptShellPermissionIdentity(android.Manifest.permission.BIND_APPWIDGET)
        try {
            runBlocking {
                app.repository.logout()
                app.usedPercent = 38
                app.repository.connect(com.tsonglew.quotapal.data.Session("test-access", "test-refresh", "test-account", Long.MAX_VALUE))
            }
            androidx.test.core.app.ActivityScenario.launch<WidgetTestHostActivity>(
                android.content.Intent(context, WidgetTestHostActivity::class.java).putExtra("slim", slim)
                    .putExtra("width", width).putExtra("height", height)).use { scenario ->
                scenario.onActivity { it.addStandardWidget(); it.addThirdWidget() }
                runBlocking { app.updateWidgets(); app.reconcileSync() }
                fun labels(view: android.view.View): List<android.widget.TextView> = when (view) {
                    is android.widget.TextView -> listOf(view)
                    is android.view.ViewGroup -> (0 until view.childCount).flatMap { labels(view.getChildAt(it)) }
                    else -> emptyList()
                }
                var touchDescription = "not tapped"
                fun awaitBoth(expected: String, timeoutMillis: Long = 20_000) {
                    val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMillis
                    var matched = false
                    while (!matched && android.os.SystemClock.elapsedRealtime() < deadline) {
                        scenario.onActivity { activity ->
                            matched = listOf(activity.widgetView, requireNotNull(activity.secondaryWidgetView), requireNotNull(activity.thirdWidgetView)).all {
                                labels(it).any { label -> label.text.contains(expected) }
                            }
                        }
                        if (!matched) Thread.sleep(50)
                    }
                    var actual = ""
                    var deliveredTouches = ""
                    scenario.onActivity { activity ->
                        deliveredTouches = activity.touchEvents.joinToString()
                        actual = listOf(activity.widgetView, requireNotNull(activity.secondaryWidgetView), requireNotNull(activity.thirdWidgetView))
                            .joinToString(" | ") { labels(it).joinToString { label -> label.text.toString() } }
                    }
                    org.junit.Assert.assertTrue("All three widgets must show $expected; actual=$actual; touch=$touchDescription; delivered=$deliveredTouches; requests=${app.usageRequests.get()}; state=${app.repository.state.value}; diagnostics=${app.diagnostics.report()}", matched)
                }
                awaitBoth("62%")
                val before = app.usageRequests.get()
                app.usedPercent = 17
                val responseGate = java.util.concurrent.CountDownLatch(1)
                app.usageResponseGate = responseGate
                runBlocking { app.settings.syncResult(null, lastAttemptAt = 0) }
                // RemoteViews applies asynchronously and semantics may belong to a
                // wrapper rather than its TextView. Wait for the actual action view.
                fun descendants(view: android.view.View): List<android.view.View> =
                    listOf(view) + if (view is android.view.ViewGroup) {
                        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
                    } else emptyList()
                val clickDeadline = android.os.SystemClock.elapsedRealtime() + 20_000
                var refreshBounds: android.graphics.Rect? = null
                var lastTree = ""
                var stableTarget: android.view.View? = null
                var stableBounds: android.graphics.Rect? = null
                var stableSince = 0L
                var recoveredLauncherDialog = false
                while (refreshBounds == null && android.os.SystemClock.elapsedRealtime() < clickDeadline) {
                    instrumentation.waitForIdleSync()
                    var hasWindowFocus = false
                    scenario.onActivity { activity ->
                        hasWindowFocus = activity.hasWindowFocus()
                        val views = descendants(activity.widgetView)
                        lastTree = views.joinToString("\n") {
                            "${it.javaClass.simpleName}: description=${it.contentDescription}, clickable=${it.hasOnClickListeners()}, parent=${it.parent?.javaClass?.simpleName}, shown=${it.isShown}, layoutRequested=${it.isLayoutRequested}, size=${it.width}x${it.height}, windowFocus=${it.hasWindowFocus()}"
                        }
                        val actions = views.filter { it.contentDescription?.toString() == "刷新额度" }
                        org.junit.Assert.assertTrue("Refresh semantics must be unique\n$lastTree", actions.size <= 1)
                        actions.singleOrNull()?.let { action ->
                            // RemoteViews can insert multiple wrappers between semantics
                            // and the action. Reject the widget-wide open-App listener:
                            // that subtree also contains the seeded quota label.
                            val target = generateSequence(action) { it.parent as? android.view.View }
                                .takeWhile { it !== activity.widgetView }
                                .firstOrNull { it.hasOnClickListeners() }
                                ?.takeIf { labels(it).none { label -> label.text.contains("62%") } }
                            val bounds = android.graphics.Rect()
                            if (target != null && activity.hasWindowFocus() && target.isShown &&
                                target.getLocalVisibleRect(bounds) && !bounds.isEmpty) {
                                // Input injection takes screen coordinates. GlobalVisibleRect
                                // is relative to the root View and can omit the window offset.
                                val screen = IntArray(2)
                                target.getLocationOnScreen(screen)
                                bounds.offset(screen[0], screen[1])
                                val now = android.os.SystemClock.uptimeMillis()
                                if (target !== stableTarget || bounds != stableBounds) {
                                    stableTarget = target
                                    stableBounds = android.graphics.Rect(bounds)
                                    stableSince = now
                                }
                                // AppWidget updates can replace a child between DOWN and
                                // UP. Wait for both the actual hit target and host updates
                                // to settle before starting the real gesture.
                                if (now - stableSince >= 500 && now - activity.lastWidgetUpdateAt >= 500) {
                                    refreshBounds = bounds
                                    touchDescription = "screenBounds=$bounds; action=${action.javaClass.simpleName}; target=${target.javaClass.simpleName}; tree=$lastTree"
                                }
                            } else {
                                stableTarget = null
                                stableBounds = null
                            }
                        }
                    }
                    if (!hasWindowFocus && !recoveredLauncherDialog) {
                        recoveredLauncherDialog = dismissLauncherAnrOnEmulator(instrumentation)
                    }
                    if (refreshBounds == null) Thread.sleep(50)
                }
                if (refreshBounds == null) instrumentation.uiAutomation.takeScreenshot()?.let {
                    saveDeviceScreenshot(context, "widget-touch-missing-target", it)
                }
                org.junit.Assert.assertNotNull("Refresh action must become visible\n$lastTree", refreshBounds)
                val bounds = requireNotNull(refreshBounds)
                fun tapRefresh() {
                    val downTime = android.os.SystemClock.uptimeMillis()
                    for (eventAction in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                        val event = android.view.MotionEvent.obtain(downTime, android.os.SystemClock.uptimeMillis(),
                            eventAction, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                        event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                        try {
                            org.junit.Assert.assertTrue("Real refresh touch must be delivered",
                                instrumentation.uiAutomation.injectInputEvent(event, true))
                        } finally { event.recycle() }
                        if (eventAction == android.view.MotionEvent.ACTION_DOWN) Thread.sleep(50)
                    }
                }
                // A real touch targets Glance's action even when its semantic wrapper
                // and PendingIntent live on different nodes. Ancestor performClick can
                // accidentally invoke the widget's open-App action instead.
                tapRefresh()
                Thread.sleep(50)
                tapRefresh()
                try {
                    // Observe actual progress before permitting HTTP completion, within
                    // the production action's eight-second immediate-request budget.
                    awaitBoth("…", 6_000)
                    assertEquals("Progress shares one pending request", before + 1, app.usageRequests.get())
                } finally {
                    responseGate.countDown()
                    app.usageResponseGate = null
                }
                awaitBoth("83%")
                assertEquals("Repeated clicks share one request", before + 1, app.usageRequests.get())
                assertEquals("Refresh must update in place without opening the app", 0, activityMonitor.hits)
                assertEquals(1, manager.getWorkInfosForUniqueWork("codex-periodic-sync").get(10, TimeUnit.SECONDS)
                    .count { !it.state.isFinished })
            }
        } finally {
            instrumentation.removeMonitor(activityMonitor)
            app.usageResponseGate?.countDown()
            app.usageResponseGate = null
            app.responseDelayMillis = 0
            runBlocking { app.repository.logout(); app.reconcileSync() }
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    @Test fun callbackCompletesImmediatelyWithoutOrdinaryBackgroundJob() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.quotaApp
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork("codex-manual-sync").result.get(10, TimeUnit.SECONDS)
        try {
            app.repository.demo()
            val before = manager.getWorkInfosForUniqueWork("codex-manual-sync").get(10, TimeUnit.SECONDS).map { it.id }.toSet()
            RefreshWidgetAction().onAction(context, object : GlanceId {}, actionParametersOf())
            val after = manager.getWorkInfosForUniqueWork("codex-manual-sync").get(10, TimeUnit.SECONDS).map { it.id }.toSet()
            assertEquals("A completed widget action must not wait for scheduled background execution", before, after)
        } finally {
            app.repository.logout()
        }
    }
}
