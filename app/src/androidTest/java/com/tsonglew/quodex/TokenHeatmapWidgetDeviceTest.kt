package com.tsonglew.quodex

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker.Result
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.tsonglew.quodex.data.Session
import com.tsonglew.quodex.data.absoluteTime
import com.tsonglew.quodex.sync.TokenWidgetSyncWorker
import com.tsonglew.quodex.sync.UsageSyncWorker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real launcher RemoteViews and PendingIntents; HTTP is served by the synthetic test application. */
class TokenHeatmapWidgetDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val app get() = context.quotaApp as TestQuotaApplication
    private val texts get() = com.tsonglew.quodex.i18n.AndroidTextResources(context)
    private val refreshDescription get() = texts(R.string.refresh_token_statistics)

    @Test fun minimumEnglishAndJapaneseWidgetsKeepAllLabelsVisible() = fixture {
        runBlocking { app.repository.demo() }
        fun select(tag: String) {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity {
                    com.tsonglew.quodex.i18n.AppLanguages.select(tag)
                    app.languageChanged()
                }
                compose.waitUntil(10_000) { texts.locale.language == tag.substringBefore('-') }
            }
        }
        try {
            for (language in listOf("en", "ja")) {
                select(language)
                withHost(110, 110, "light") { scenario ->
                    awaitLabels(scenario, "2026-10-22", texts(R.string.sample_data))
                    verifyLayoutAndSave(scenario, "token-heatmap-widget-$language")
                }
            }
        } finally { select("zh-Hans") }
    }

    @Test fun responsiveLightAndDarkWidgetsKeepThirtyDaysAndActionsVisible() = fixture {
        runBlocking { app.repository.demo() }
        for ((width, height, theme) in listOf(Triple(110, 110, "light"), Triple(160, 160, "light"), Triple(160, 160, "dark"),
            Triple(220, 160, "light"), Triple(280, 180, "light"), Triple(360, 220, "dark"))) {
            withHost(width, height, theme) { scenario ->
                awaitLabels(scenario, "2026-10-22", "示例数据")
                if (width >= 220) awaitLabels(scenario, "30/30 天有数据")
                verifyLayoutAndSave(scenario, "token-heatmap-widget-$width-$theme")
            }
        }
    }

    @Test fun minimumWidgetSupportsLargeSystemFonts() = withWidgetFontScale(2f) {
        fixture {
            runBlocking { app.repository.demo() }
            withHost(110, 110, "light") { scenario ->
                awaitLabels(scenario, "2026-10-22", "示例数据")
                verifyLayoutAndSave(scenario, "token-heatmap-widget-large-font")
            }
        }
    }

    @Test fun pinFetchesTokensRefreshKeepsQuotaSeparateAndBodyOpensThirtyDays() = fixture {
        runBlocking { app.repository.connect(Session("test-access", "test-refresh", "heatmap-account", Long.MAX_VALUE)) }
        assertNull(app.repository.state.value.snapshot?.tokenUsage)
        val quotaRequests = app.usageRequests.get()
        val tokenRequests = app.tokenUsageRequests.get()
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        var opened: Activity? = null
        try {
            withHost(160, 160, "light") { scenario ->
                // Adding just this widget fetches its optional data without opening MainActivity.
                awaitLabels(scenario, "57K", "3/30 天", "2026-10-22")
                assertEquals(tokenRequests + 1, app.tokenUsageRequests.get())
                assertEquals(quotaRequests, app.usageRequests.get())
                assertTrue(runBlocking { app.backgroundSyncRequired() })
                val tokenTime = app.repository.state.value.snapshot!!.tokenUsage!!.fetchedAt
                scenario.onActivity { host -> assertTrue(descendants(host.widgetView).any {
                    it.contentDescription?.toString()?.contains(absoluteTime(tokenTime, true)) == true
                }) }
                val beforeRefresh = app.tokenUsageRequests.get()
                app.tokenUsageBody = """{"stats":{"daily_usage_buckets":[{"start_date":"2026-10-29","tokens":0},{"start_date":"2026-10-30","tokens":96000}]}}"""
                tapAction(scenario) { it == refreshDescription }
                awaitLabels(scenario, "96K", "2/30 天", "2026-10-30")
                assertEquals(beforeRefresh + 1, app.tokenUsageRequests.get())
                assertEquals(quotaRequests, app.usageRequests.get())
                assertEquals("Refresh must not launch the app", 0, monitor.hits)
                // Periodic work leaves a fresh token cache alone.
                runBlocking {
                    assertEquals(Result.success(), TestListenableWorkerBuilder<UsageSyncWorker>(context)
                        .setInputData(workDataOf("periodic" to true)).build().doWork())
                }
                assertEquals(beforeRefresh + 1, app.tokenUsageRequests.get())
                app.tokenUsageStatusCode = 403
                tapAction(scenario) { it == refreshDescription }
                // Before API 33 the app locale is resolved by AndroidX, while a
                // plain application Context can still expose the system language.
                awaitLabels(scenario, "96K", texts(R.string.cached))
                assertEquals(beforeRefresh + 2, app.tokenUsageRequests.get())
                verifyLayoutAndSave(scenario, "token-heatmap-widget-cached")
                tapAction(scenario) { it.endsWith("打开 Quodex") }
                opened = monitor.waitForActivityWithTimeout(10_000)
                assertTrue("Chart action must open MainActivity", opened is MainActivity)
                compose.waitUntil(10_000) {
                    compose.onAllNodes(hasTestTag("token-trend-30") and isSelected()).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag("token-trend-chart").assertIsDisplayed()
                compose.onNodeWithTag("token-trend-total").assertTextEquals("96,000")
                assertEquals("Open chart must not force another token request", beforeRefresh + 2, app.tokenUsageRequests.get())
                opened?.let { activity -> instrumentation.runOnMainSync { activity.finish() } }
                opened = null
                runBlocking { app.repository.logout(); app.updateWidgets() }
                awaitLabels(scenario, "连接你的 Codex")
                scenario.onActivity { host ->
                    assertFalse(labels(host.widgetView).any { it.text.contains("96K") })
                }
            }
        } finally {
            opened?.let { activity -> instrumentation.runOnMainSync { activity.finish() } }
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test fun scheduledTokenRetryIsBoundedAndPreservesCachedData() = fixture {
        runBlocking {
            app.repository.connect(Session("test-access", "test-refresh", "heatmap-account", Long.MAX_VALUE))
            app.repository.refreshTokenUsage(force = true)
            val cached = app.repository.state.value.snapshot
            val quotaRequests = app.usageRequests.get()
            val tokenRequests = app.tokenUsageRequests.get()
            app.tokenUsageStatusCode = 503
            for (attempt in 0..2) {
                val worker = TestListenableWorkerBuilder<TokenWidgetSyncWorker>(context)
                    .setInputData(workDataOf("force" to true)).setRunAttemptCount(attempt).build()
                assertEquals(if (attempt < 2) Result.retry() else Result.failure(), worker.doWork())
                assertEquals(cached, app.repository.state.value.snapshot)
                assertEquals(quotaRequests, app.usageRequests.get())
                assertEquals("One token request per worker attempt", tokenRequests + attempt + 1, app.tokenUsageRequests.get())
            }
        }
    }

    private fun fixture(block: () -> Unit) {
        val body = app.tokenUsageBody
        val compact = runBlocking { app.settings.read().compactTokens }
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.BIND_APPWIDGET)
        try {
            runBlocking { app.repository.logout(); app.settings.compactTokens(false) }
            app.tokenUsageStatusCode = 200
            block()
        } finally {
            app.tokenUsageBody = body
            app.tokenUsageStatusCode = 200
            runBlocking { app.repository.logout(); app.settings.compactTokens(compact); app.reconcileSync() }
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    private fun withHost(width: Int, height: Int, theme: String, block: (ActivityScenario<WidgetTestHostActivity>) -> Unit) {
        var id = 0
        try {
            ActivityScenario.launch<WidgetTestHostActivity>(Intent(context, WidgetTestHostActivity::class.java)
                .putExtra("heatmap", true).putExtra("width", width).putExtra("height", height)).use { scenario ->
                scenario.onActivity { id = it.widgetId }
                runBlocking { app.settings.saveWidget(id, true, theme); app.updateWidgets(); app.reconcileSync() }
                block(scenario)
            }
        } finally {
            if (id != 0) runBlocking { app.settings.deleteWidget(id) }
        }
    }

    private fun awaitLabels(scenario: ActivityScenario<WidgetTestHostActivity>, vararg expected: String) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        var actual = ""
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            scenario.onActivity { actual = labels(it.widgetView).joinToString(" | ") { label -> label.text.toString() } }
            if (expected.all { it in actual }) return
            Thread.sleep(50)
        }
        fail("Missing ${expected.toList()}; actual=$actual; state=${app.repository.state.value}")
    }

    private fun verifyLayoutAndSave(scenario: ActivityScenario<WidgetTestHostActivity>, name: String) {
        tapTarget(scenario) { it == refreshDescription } // Wait for a stable, laid-out RemoteViews tree.
        scenario.onActivity { host ->
            val view = host.widgetView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            saveDeviceScreenshot(context, name, bitmap)
            assertWidgetActionLayout(view, refreshDescription, texts(R.string.value_open_quodex, ""))
            assertTrue("Heatmap bitmap must be visible", descendants(view).filterIsInstance<ImageView>().any { it.drawable != null && it.width > 0 && it.height > 0 })
            // Inspect the rendered host, including ImageView scaling: a visible but narrow
            // bitmap used to pass while leaving most of the 2×2 widget empty.
            val greens = com.tsonglew.quodex.ui.TokenHeatmapRenderer.colors(false).drop(1).toSet() +
                com.tsonglew.quodex.ui.TokenHeatmapRenderer.colors(true).drop(1).toSet()
            val occupiedColumns = (0 until bitmap.width).filter { x ->
                (0 until bitmap.height).any { y -> bitmap.getPixel(x, y) in greens }
            }
            assertTrue("Heatmap must span most of the widget width",
                occupiedColumns.isNotEmpty() && occupiedColumns.last() - occupiedColumns.first() > bitmap.width * .68f)
            for (label in labels(view).filter { it.text.isNotEmpty() }) {
                val bounds = Rect()
                assertTrue("Hidden widget text: ${label.text}", label.getLocalVisibleRect(bounds))
                assertEquals("Clipped widget text: ${label.text}", label.height, bounds.height())
                val layout = requireNotNull(label.layout)
                assertTrue("Ellipsized widget text: ${label.text}", (0 until layout.lineCount).all { layout.getEllipsisCount(it) == 0 })
            }
        }
    }

    private fun tapTarget(scenario: ActivityScenario<WidgetTestHostActivity>, matches: (String) -> Boolean): Rect {
        val deadline = SystemClock.uptimeMillis() + 20_000
        var stableView: View? = null
        var stableBounds: Rect? = null
        var stableSince = 0L
        var recoveredLauncher = false
        while (SystemClock.uptimeMillis() < deadline) {
            var result: Rect? = null
            var focused = false
            instrumentation.waitForIdleSync()
            scenario.onActivity { host ->
                focused = host.hasWindowFocus()
                val semantics = descendants(host.widgetView).firstOrNull { matches(it.contentDescription?.toString().orEmpty()) }
                // API 29/30 can place semantics on a child of the emulated Button.
                val action = semantics?.let { generateSequence(it) { view -> view.parent as? View }
                    .takeWhile { view -> view !== host.widgetView }.firstOrNull { view -> view.hasOnClickListeners() } }
                val visible = Rect()
                if (focused && action != null && action.isShown && action.getLocalVisibleRect(visible) && !visible.isEmpty) {
                    val screen = IntArray(2).also(action::getLocationOnScreen)
                    visible.offset(screen[0], screen[1])
                    val now = SystemClock.uptimeMillis()
                    if (action !== stableView || visible != stableBounds) {
                        stableView = action; stableBounds = Rect(visible); stableSince = now
                    }
                    if (now - stableSince >= 500 && now - host.lastWidgetUpdateAt >= 500) result = visible
                } else { stableView = null; stableBounds = null }
            }
            result?.let { return it }
            if (!focused && !recoveredLauncher) recoveredLauncher = dismissLauncherAnrOnEmulator(instrumentation)
            Thread.sleep(50)
        }
        error("No stable widget action found")
    }

    private fun tapAction(scenario: ActivityScenario<WidgetTestHostActivity>, matches: (String) -> Boolean) {
        val target = tapTarget(scenario, matches)
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, target.exactCenterX(), target.exactCenterY(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            if (action == MotionEvent.ACTION_DOWN) Thread.sleep(50)
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun labels(view: View) = descendants(view).filterIsInstance<TextView>()
}
