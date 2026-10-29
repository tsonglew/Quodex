package com.tsonglew.quodex

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.tsonglew.quodex.data.demoSnapshot
import com.tsonglew.quodex.i18n.AndroidTextResources
import com.tsonglew.quodex.i18n.AppLanguages
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LocalizationDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as TestQuotaApplication

    @Test fun languagePickerUpdatesCachedQuotaAndBackgroundTextAcrossRecreation() {
        runBlocking { app.repository.demo(demoSnapshot(java.time.Instant.now().epochSecond)) }
        val requests = app.usageRequests.get()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                compose.onNodeWithTag("tab-settings").performClick()
                select("en", "App language")
                scenario.onActivity { assertEquals("en", AppLanguages.selectedTag(it)) }
                scenario.recreate()
                waitForText("App language")
                assertEquals("Weekly quota", AndroidTextResources(app)(R.string.weekly_quota))
                save("language-settings-en")

                select("ja", "アプリの言語")
                assertEquals("週間使用枠", AndroidTextResources(app)(R.string.weekly_quota))
                save("language-settings-ja")
                compose.onNodeWithTag("tab-quota").performClick()
                waitForText("週間使用枠")
                compose.onNodeWithText("サンプルデータ · 実際の使用枠ではありません").assertExists()
                save("language-quota-ja")

                compose.onNodeWithTag("tab-settings").performClick()
                select("zh-Hans", "应用语言")
                assertEquals("每周额度", AndroidTextResources(app)(R.string.weekly_quota))
                save("language-settings-zh")
                compose.onNodeWithTag("tab-quota").performClick()
                waitForText("每周额度")
                assertTrue(app.repository.state.value.demo)
                assertEquals("Switching the language of cached demo data must not fetch account quota", requests, app.usageRequests.get())
                compose.onNodeWithTag("tab-settings").performClick()
                select("system", "App language")
                scenario.onActivity { assertEquals("", AppLanguages.selectedTag(it)) }
                scenario.recreate()
                waitForText("App language")
                assertEquals("Weekly quota", AndroidTextResources(app)(R.string.weekly_quota))
            } finally {
                restoreTestLanguage()
            }
        }
    }

    @Test fun languagePickerRepaintsAllExistingWidgetInstances() {
        val context = instrumentation.targetContext
        val ids = mutableListOf<Int>()
        instrumentation.uiAutomation.adoptShellPermissionIdentity(android.Manifest.permission.BIND_APPWIDGET)
        try {
            runBlocking { app.repository.demo(demoSnapshot(java.time.Instant.now().epochSecond)); app.settings.showRemaining(true) }
            ActivityScenario.launch<WidgetTestHostActivity>(android.content.Intent(context, WidgetTestHostActivity::class.java)).use { host ->
                host.onActivity { ids += it.widgetId; ids += it.addStandardWidget(); ids += it.addThirdWidget() }
                runBlocking { app.updateWidgets() }
                val requests = app.usageRequests.get()
                for ((tag, heading, label) in listOf(Triple("en", "App language", "Remaining"),
                    Triple("ja", "アプリの言語", "残り"), Triple("zh-Hans", "应用语言", "剩余"))) {
                    ActivityScenario.launch<MainActivity>(android.content.Intent(context, MainActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK)).use {
                        compose.onNodeWithTag("tab-settings").performClick()
                        select(tag, heading)
                    }
                    val deadline = android.os.SystemClock.uptimeMillis() + 20_000
                    var matched = false
                    var focused = false
                    var requestedFocus = false
                    var recoveredSystemDialog = false
                    var actual = emptyList<String>()
                    while (!matched && android.os.SystemClock.uptimeMillis() < deadline) {
                        host.onActivity { activity ->
                            // Request foreground once, then allow the window focus transition to settle.
                            if (!requestedFocus) {
                                activity.getSystemService(android.app.ActivityManager::class.java).appTasks
                                    .firstOrNull { it.taskInfo.taskId == activity.taskId }?.let {
                                        it.moveToFront()
                                        requestedFocus = true
                                    }
                            }
                            assertEquals("The original host must survive app language changes", ids.first(), activity.widgetId)
                            actual = listOf(activity.widgetView, checkNotNull(activity.secondaryWidgetView), checkNotNull(activity.thirdWidgetView))
                                .map { labels(it).joinToString(" | ") }
                            focused = activity.widgetView.hasWindowFocus()
                            matched = focused && actual.all { label in it && "62%" in it }
                        }
                        if (!focused && !recoveredSystemDialog) {
                            recoveredSystemDialog = dismissLauncherAnrOnEmulator(instrumentation)
                            if (recoveredSystemDialog) requestedFocus = false
                        }
                        if (!matched) Thread.sleep(100)
                    }
                    if (!matched) instrumentation.uiAutomation.takeScreenshot()?.let {
                        saveDeviceScreenshot(context, "language-widget-timeout", it)
                    }
                    assertTrue("Every existing widget must use $tag without a test-triggered repaint; " +
                        "focused=$focused, recoveredSystemDialog=$recoveredSystemDialog: $actual", matched)
                    assertEquals(requests, app.usageRequests.get())
                    instrumentation.waitForIdleSync()
                    saveDeviceScreenshot(context, "language-widgets-$tag", checkNotNull(instrumentation.uiAutomation.takeScreenshot()))
                }
            }
        } finally {
            restoreTestLanguage()
            runBlocking { ids.forEach { app.settings.deleteWidget(it) } }
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    private fun restoreTestLanguage() {
        instrumentation.runOnMainSync {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                // AppCompat cannot resolve a LocaleManager after its last Activity is gone.
                app.getSystemService(android.app.LocaleManager::class.java).applicationLocales =
                    android.os.LocaleList.forLanguageTags("zh-Hans")
            } else AppLanguages.select("zh-Hans")
            app.languageChanged()
        }
        instrumentation.waitForIdleSync()
        assertEquals("zh-Hans", AppLanguages.selectedTag(app))
    }

    private fun labels(view: android.view.View): List<String> = when (view) {
        is android.widget.TextView -> listOf(view.text.toString())
        is android.view.ViewGroup -> (0 until view.childCount).flatMap { labels(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun select(tag: String, heading: String) {
        compose.onNodeWithTag("language-$tag").performScrollToVisible(compose).performClick()
        waitForText(heading)
    }
    private fun waitForText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun save(name: String) {
        saveDeviceScreenshot(app, name, compose.onRoot().captureToImage().asAndroidBitmap())
    }
}
