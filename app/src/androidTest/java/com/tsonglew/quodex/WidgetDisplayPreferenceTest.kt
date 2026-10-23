package com.tsonglew.quodex

import android.Manifest
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WidgetDisplayPreferenceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun appSwitchUpdatesExistingStandardAndSlimWidgetsWithoutFetchingUsage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.quotaApp as TestQuotaApplication
        val original = runBlocking { app.settings.read().showRemaining }
        val ids = mutableListOf<Int>()
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.BIND_APPWIDGET)
        try {
            runBlocking {
                app.repository.demo()
                app.settings.showRemaining(true)
            }
            ActivityScenario.launch<WidgetTestHostActivity>(Intent(context, WidgetTestHostActivity::class.java)).use { host ->
                host.onActivity {
                    ids += it.widgetId
                    ids += it.addStandardWidget()
                    ids += it.addThirdWidget()
                }
                val themes = listOf("light", "dark", "light")
                runBlocking {
                    // Saved instance values used to hide changes made by the global switch.
                    ids.forEachIndexed { index, id -> app.settings.saveWidget(id, true, themes[index]) }
                    app.updateWidgets()
                }
                awaitWidgets(host, remaining = true)
                val quotaRequests = app.usageRequests.get()
                val tokenRequests = app.tokenUsageRequests.get()
                for (remaining in listOf(false, true)) {
                    // Keep the simulated launcher in its own task when ActivityScenario clears the App task.
                    ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)).use {
                        compose.onNodeWithTag("tab-settings").performClick()
                        val toggle = compose.onNodeWithTag("show-remaining-toggle").performScrollTo()
                        if (remaining) toggle.assertIsOff() else toggle.assertIsOn()
                        toggle.performClick()
                        compose.waitUntil(10_000) { runBlocking { app.settings.read().showRemaining == remaining } }
                        if (remaining) toggle.assertIsOn() else toggle.assertIsOff()
                    }
                    host.onActivity { activity ->
                        activity.getSystemService(android.app.ActivityManager::class.java).appTasks
                            .first { it.taskInfo.taskId == activity.taskId }.moveToFront()
                    }
                    // No test-triggered updateAll here: the real settings action must repaint every instance.
                    awaitWidgets(host, remaining)
                    ids.forEachIndexed { index, id ->
                        assertEquals(remaining to themes[index], runBlocking { app.settings.widgetSettings(id) })
                    }
                    assertEquals(quotaRequests, app.usageRequests.get())
                    assertEquals(tokenRequests, app.tokenUsageRequests.get())
                    // Text properties change before the window compositor presents the updated bars.
                    val rendered = java.util.concurrent.CountDownLatch(1)
                    host.onActivity { activity ->
                        val view = activity.window.decorView
                        view.postOnAnimation { view.postOnAnimation { view.postOnAnimation { rendered.countDown() } } }
                    }
                    assertTrue("Widget frames must be presented", rendered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    instrumentation.waitForIdleSync()
                    val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                    saveDeviceScreenshot(context, "widgets-global-${if (remaining) "remaining" else "used"}", screenshot)
                }
            }
        } finally {
            runBlocking {
                ids.forEach { app.settings.deleteWidget(it) }
                app.settings.showRemaining(original)
                app.repository.logout()
                app.reconcileSync()
            }
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    private fun awaitWidgets(host: ActivityScenario<WidgetTestHostActivity>, remaining: Boolean) {
        val expected = if (remaining) "62%" else "38%"
        val previous = if (remaining) "38%" else "62%"
        val label = if (remaining) "剩余" else "已用"
        var actual = emptyList<String>()
        var visible = false
        val deadline = android.os.SystemClock.uptimeMillis() + 20_000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            host.onActivity { activity ->
                visible = activity.widgetView.hasWindowFocus()
                actual = listOf(activity.widgetView, requireNotNull(activity.secondaryWidgetView), requireNotNull(activity.thirdWidgetView))
                    .map { labels(it).joinToString(" | ") }
            }
            if (visible && actual.size == 3 && actual.all { expected in it && previous !in it && label in it }) return
            Thread.sleep(100)
        }
        fail("All three real widgets must show $label $expected; actual=$actual")
    }

    private fun labels(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { labels(view.getChildAt(it)) }
        else -> emptyList()
    }
}
