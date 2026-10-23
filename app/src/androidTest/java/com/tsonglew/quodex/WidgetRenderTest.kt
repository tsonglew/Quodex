package com.tsonglew.quodex

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.tsonglew.quodex.data.UsageParser
import com.tsonglew.quodex.data.absoluteTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WidgetRenderTest {
    @Test fun largeFontKeepsQuotaTimestampAndRefreshVisible() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.quotaApp
        fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText().trim() }
        }
        val originalScale = shell("settings get system font_scale").toFloatOrNull() ?: 1f
        instrumentation.uiAutomation.adoptShellPermissionIdentity(android.Manifest.permission.BIND_APPWIDGET)
        try {
            shell("settings put system font_scale 2.0")
            val deadline = System.currentTimeMillis() + 10_000
            while (context.resources.configuration.fontScale < 1.9f && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertTrue("The platform must apply the large font setting", context.resources.configuration.fontScale >= 1.9f)
            val snapshot = com.tsonglew.quodex.data.demoSnapshot(1000)
            runBlocking { app.repository.demo(snapshot) }
            for ((width, height) in listOf(130 to 50, 130 to 70, 280 to 70, 140 to 150, 280 to 150, 140 to 230)) {
                ActivityScenario.launch<WidgetTestHostActivity>(Intent(context, WidgetTestHostActivity::class.java)
                    .putExtra("width", width).putExtra("height", height).putExtra("slim", height < 100)).use { scenario ->
                    runBlocking { app.updateWidgets() }
                    fun verify(expected: String, expectedTime: String) {
                        val until = System.currentTimeMillis() + 20_000
                        var matched = false
                        while (!matched && System.currentTimeMillis() < until) {
                            scenario.onActivity {
                                val actual = texts(it.widgetView).map { label -> label.text.toString() }
                                matched = expected in actual && expectedTime in actual
                            }
                            if (!matched) Thread.sleep(100)
                        }
                        assertTrue("Large font content missing at ${width}x$height", matched)
                        withLaidOutWidget(scenario) { activity ->
                            val labels = texts(activity.widgetView).filter { it.text.isNotEmpty() }
                            assertTrue("Expected quota must still be rendered", labels.any { it.text.toString() == expected })
                            assertTrue("Expected timestamp must still be rendered", labels.any { it.text.toString() == expectedTime })
                            assertTrue("Refresh glyph must remain visible", labels.any { it.text.toString() == "↻" })
                            for (label in labels) {
                                val visible = Rect()
                                assertTrue("Large font text outside widget: ${label.text}", label.getLocalVisibleRect(visible))
                                if (label.height != visible.height()) {
                                    val bitmap = Bitmap.createBitmap(activity.widgetView.width, activity.widgetView.height, Bitmap.Config.ARGB_8888)
                                    activity.widgetView.draw(android.graphics.Canvas(bitmap))
                                    saveDeviceScreenshot(context, "large-font-clipped-${width}x$height", bitmap)
                                    android.util.Log.e("QuotaWidgetLayout", geometry(activity.widgetView))
                                }
                                assertEquals("Large font text clipped at ${width}x$height: ${label.text}", label.height, visible.height())
                                val layout = requireNotNull(label.layout)
                                assertTrue("Large font text ellipsized at ${width}x$height: ${label.text}; content=${text(activity.widgetView)}",
                                    (0 until layout.lineCount).all { layout.getEllipsisCount(it) == 0 })
                            }
                        }
                    }
                    verify("62%", absoluteTime(snapshot.fetchedAt, true))
                    if (width >= 280 && height >= 100 || height >= 230) scenario.onActivity {
                        assertTrue("Wide or tall large-font widget keeps the second quota", text(it.widgetView).contains("36%"))
                    }
                    if ((width == 130 && height == 70) || (width == 140 && height == 150)) {
                        val fixtures = listOf(
                            """{"rate_limit":{"primary_window":{"used_percent":0,"limit_window_seconds":604800}}}""" to "100%",
                            """{"rate_limit":{"primary_window":{"used_percent":100,"limit_window_seconds":604800}}}""" to "0%",
                            """{"rate_limit":{"primary_window":{"limit_window_seconds":18000}}}""" to "暂不可用",
                            """{"rate_limit":{"allowed":true}}""" to "当前可用",
                            """{"plan_type":"pro","rate_limit":null}""" to if (height < 100) "无周期" else "未提供周期额度",
                            """{"rate_limit":{"allowed":false,"primary_window":{"used_percent":20}}}""" to "受限",
                        )
                        for ((body, expected) in fixtures) {
                            runBlocking { app.repository.demo(UsageParser.parse(body, "demo", 1000)); app.updateWidgets() }
                            verify(expected, absoluteTime(1000, true))
                        }
                        runBlocking { app.repository.logout(); app.updateWidgets() }
                        verify("未连接", "轻点连接")
                        runBlocking { app.repository.demo(snapshot) }
                    }
                }
            }
        } finally {
            shell("settings put system font_scale $originalScale")
            runBlocking { app.repository.logout(); app.reconcileSync() }
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    @Test fun resizeDeleteAndReaddUseRealHostAndClearInstancePreferences() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.quotaApp
        instrumentation.uiAutomation.adoptShellPermissionIdentity(android.Manifest.permission.BIND_APPWIDGET)
        var removedId = 0
        try {
            runBlocking { app.repository.demo() }
            ActivityScenario.launch<WidgetTestHostActivity>(Intent(context, WidgetTestHostActivity::class.java)
                .putExtra("width", 280).putExtra("height", 150)).use { scenario ->
                scenario.onActivity { removedId = it.widgetId }
                runBlocking { app.settings.saveWidget(removedId, false, "dark"); app.updateWidgets() }
                fun awaitQuota(expected: String, secondVisible: Boolean) {
                    val deadline = System.currentTimeMillis() + 20_000
                    var matched = false
                    while (!matched && System.currentTimeMillis() < deadline) {
                        scenario.onActivity {
                            val actual = text(it.widgetView)
                            matched = actual.contains(expected) && actual.contains("64%") == secondVisible
                        }
                        if (!matched) Thread.sleep(100)
                    }
                    var actual = ""
                    scenario.onActivity { actual = text(it.widgetView) }
                    val options = android.appwidget.AppWidgetManager.getInstance(context).getAppWidgetOptions(removedId)
                    assertTrue("Resized RemoteViews expected $expected, second=$secondVisible; actual=$actual; options=$options", matched)
                }
                awaitQuota("38%", true)
                fun resize(width: Int, height: Int) {
                    scenario.onActivity { it.resizePrimaryWidget(width, height) }
                    val options = android.appwidget.AppWidgetManager.getInstance(context).getAppWidgetOptions(removedId)
                    assertEquals("Provider must receive the requested content width", width,
                        options.getInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH))
                    assertEquals("Provider must receive the requested content height", height,
                        options.getInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT))
                }
                resize(140, 230)
                awaitQuota("38%", true)
                resize(140, 150)
                awaitQuota("38%", false)
            }
            val deadline = System.currentTimeMillis() + 20_000
            while (runBlocking { app.settings.widgetSettings(removedId) } != (true to "system") &&
                System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertEquals("Deleting a bound widget must clear its stored settings", true to "system",
                runBlocking { app.settings.widgetSettings(removedId) })
            ActivityScenario.launch<WidgetTestHostActivity>(Intent(context, WidgetTestHostActivity::class.java)).use { scenario ->
                var replacementId = 0
                scenario.onActivity { replacementId = it.widgetId }
                assertNotEquals("Readding must allocate a fresh platform instance", removedId, replacementId)
                assertEquals(true to "system", runBlocking { app.settings.widgetSettings(replacementId) })
            }
        } finally {
            runBlocking { app.settings.deleteWidget(removedId); app.repository.logout(); app.reconcileSync() }
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    @Test fun slimWidgetsRenderOneRowQuotaStatesAndIndependentSettings() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.quotaApp
        fun shell(command: String) { instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        } }
        shell("appwidget grantbind --package ${context.packageName} --user 0")
        instrumentation.uiAutomation.adoptShellPermissionIdentity(android.Manifest.permission.BIND_APPWIDGET)
        try {
            runBlocking { app.repository.demo() }
            val cases = listOf(
                Triple(130, 70, "light"), Triple(140, 70, "dark"), Triple(280, 70, "light"), Triple(260, 50, "dark"),
            )
            cases.forEach { (width, height, theme) ->
                ActivityScenario.launch<WidgetTestHostActivity>(Intent(context, WidgetTestHostActivity::class.java)
                    .putExtra("width", width).putExtra("height", height).putExtra("slim", true)).use { scenario ->
                    var id = 0
                    scenario.onActivity { activity ->
                        id = activity.widgetId
                        val info = android.appwidget.AppWidgetManager.getInstance(context).getAppWidgetInfo(id)
                        if (android.os.Build.VERSION.SDK_INT >= 31) {
                            assertEquals(2, info.targetCellWidth)
                            assertEquals(1, info.targetCellHeight)
                        }
                    }
                    runBlocking { app.settings.saveWidget(id, true, theme); app.updateWidgets() }
                    fun awaitText(expected: String) {
                        val deadline = System.currentTimeMillis() + 20_000
                        var matched = false
                        while (!matched && System.currentTimeMillis() < deadline) {
                            instrumentation.waitForIdleSync()
                            scenario.onActivity { activity ->
                                matched = text(activity.widgetView).contains(expected) &&
                                    texts(activity.widgetView).filter { it.text.isNotEmpty() }.all { it.layout != null }
                            }
                            if (!matched) Thread.sleep(100)
                        }
                        var actual = ""
                        scenario.onActivity { actual = text(it.widgetView) }
                        assertTrue("Slim ${width}x$height missing $expected; actual $actual", matched)
                    }
                    fun capture(name: String) {
                        lateinit var bitmap: Bitmap
                        val copied = CountDownLatch(1)
                        var copyResult = -1
                        withLaidOutWidget(scenario) { activity ->
                            val view = activity.widgetView
                            for (label in texts(view).filter { it.text.isNotEmpty() }) {
                                val visible = Rect()
                                assertTrue("Slim text must be visible: ${label.text}", label.getLocalVisibleRect(visible))
                                assertEquals("Slim text clipped at ${width}x$height: ${label.text}", label.height, visible.height())
                                val layout = requireNotNull(label.layout)
                                assertTrue("Slim text ellipsized: ${label.text}", (0 until layout.lineCount).all { layout.getEllipsisCount(it) == 0 })
                            }
                            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                            val location = IntArray(2)
                            view.getLocationInWindow(location)
                            PixelCopy.request(activity.window, Rect(location[0], location[1], location[0] + view.width, location[1] + view.height),
                                bitmap, { result -> copyResult = result; copied.countDown() }, Handler(Looper.getMainLooper()))
                        }
                        assertTrue(copied.await(5, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, copyResult)
                        saveDeviceScreenshot(context, name, bitmap)
                    }
                    awaitText("62%")
                    awaitText("示例")
                    scenario.onActivity { assertFalse("Only the primary quota should be shown", text(it.widgetView).contains("36%")) }
                    capture("widget-slim-${width}x$height-$theme")
                    runBlocking { app.settings.saveWidget(id, false, theme); app.updateWidgets() }
                    awaitText("38%")
                    awaitText("已用")
                    // A second provider instance must retain its own theme and percentage preference.
                    run {
                        var standardId = 0
                        scenario.onActivity { standardId = it.addStandardWidget() }
                        runBlocking {
                            app.settings.saveWidget(standardId, true, "light"); app.updateWidgets()
                            assertEquals(false to theme, app.settings.widgetSettings(id))
                        }
                        val deadline = System.currentTimeMillis() + 20_000
                        var standardMatched = false
                        while (!standardMatched && System.currentTimeMillis() < deadline) {
                            instrumentation.waitForIdleSync()
                            scenario.onActivity { standardMatched = text(requireNotNull(it.secondaryWidgetView)).contains("62%") }
                            if (!standardMatched) Thread.sleep(100)
                        }
                        assertTrue("Both widget providers must receive shared snapshot updates", standardMatched)
                        scenario.onActivity { it.removeStandardWidget() }
                        runBlocking { app.settings.deleteWidget(standardId) }
                    }
                    awaitText("38%")
                    val states = listOf(
                        "available" to """{"rate_limit":{"allowed":true}}""",
                        "unreported" to """{"plan_type":"pro","rate_limit":null}""",
                        "review-only" to """{"rate_limit":null,"additional_rate_limits":[{"metered_feature":"review","rate_limit":{"primary_window":{"used_percent":20}}}]}""",
                        "unknown" to """{"rate_limit":{"primary_window":{"limit_window_seconds":18000}}}""",
                        "restricted" to """{"rate_limit":{"allowed":false,"primary_window":{"used_percent":20}}}""",
                    )
                    val expected = listOf("当前可用", "未提供周期额度", "未提供周期额度", "暂不可用", "使用受限")
                    states.forEachIndexed { index, (name, body) ->
                        val fetchedAt = 1000L + index * 60
                        runBlocking { app.repository.demo(UsageParser.parse(body, "demo", fetchedAt)); app.updateWidgets() }
                        awaitText(absoluteTime(fetchedAt, true))
                        awaitText(expected[index])
                        scenario.onActivity { assertFalse("Do not show a percentage for $name", text(it.widgetView).contains("%")) }
                        capture("widget-slim-$name-${width}x$height")
                    }
                    runBlocking { app.repository.logout(); app.updateWidgets() }
                    awaitText("未连接")
                    awaitText("轻点连接账号")
                    capture("widget-slim-logged-out-${width}x$height")
                    runBlocking { app.settings.deleteWidget(id); app.repository.demo() }
                }
            }
        } finally {
            runBlocking { app.repository.logout(); app.reconcileSync() }
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            shell("appwidget revokebind --package ${context.packageName} --user 0")
        }
    }

    @Test fun missingQuotaStatesRenderAcrossAllWidgetSizes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.quotaApp
        fun shell(command: String) { instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        } }
        val fixtures = listOf(
            Triple("no-windows", """{"plan_type":"pro","rate_limit":{"allowed":true,"limit_reached":false}}""", "当前可用"),
            Triple("unreported", """{"plan_type":"pro","rate_limit":null}""", "未提供周期额度"),
            Triple("weekly-only", """{"plan_type":"pro","rate_limit":{"secondary_window":{"used_percent":38,"limit_window_seconds":604800,"reset_at":2000}}}""", "无短周期数据"),
            Triple("unknown-usage", """{"rate_limit":{"primary_window":{"limit_window_seconds":18000}}}""", "暂不可用"),
            Triple("restricted", """{"rate_limit":{"allowed":false,"limit_reached":true}}""", "当前使用受限"),
        )
        shell("appwidget grantbind --package ${context.packageName} --user 0")
        instrumentation.uiAutomation.adoptShellPermissionIdentity(android.Manifest.permission.BIND_APPWIDGET)
        try {
            for ((name, body, expected) in fixtures) {
                runBlocking { app.repository.demo(UsageParser.parse(body, "demo", 1000)) }
                for ((width, height) in listOf(140 to 150, 280 to 150, 140 to 230)) {
                    ActivityScenario.launch<WidgetTestHostActivity>(Intent(context, WidgetTestHostActivity::class.java)
                        .putExtra("width", width).putExtra("height", height)).use { scenario ->
                        var id = 0
                        scenario.onActivity { id = it.widgetId }
                        runBlocking { app.settings.saveWidget(id, true, "light"); app.updateWidgets() }
                        val deadline = System.currentTimeMillis() + 20_000
                        var matched = false
                        while (!matched && System.currentTimeMillis() < deadline) {
                            instrumentation.waitForIdleSync()
                            scenario.onActivity { activity ->
                                matched = text(activity.widgetView).contains(expected) && text(activity.widgetView).contains("示例数据") &&
                                    texts(activity.widgetView).filter { it.text.isNotEmpty() }.all { it.layout != null }
                            }
                            if (!matched) Thread.sleep(100)
                        }
                        assertTrue("$name widget ${width}x$height missing $expected", matched)
                        lateinit var bitmap: Bitmap
                        val copied = CountDownLatch(1)
                        var copyResult = -1
                        withLaidOutWidget(scenario) { activity ->
                            val view = activity.widgetView
                            val labels = texts(view)
                            for (label in labels.filter { it.text.contains(expected) || it.text.startsWith("示例数据") }) {
                                val visible = Rect()
                                assertTrue(label.getLocalVisibleRect(visible))
                                assertEquals("$name label must not be clipped at ${width}x$height", label.height, visible.height())
                                val layout = requireNotNull(label.layout)
                                assertTrue("$name text must not be ellipsized", (0 until layout.lineCount).all { layout.getEllipsisCount(it) == 0 })
                            }
                            val visible = text(view)
                            if (name == "weekly-only") assertTrue(visible.contains("62%"))
                            else assertFalse("Do not invent a percentage", visible.contains("%"))
                            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                            val location = IntArray(2)
                            view.getLocationInWindow(location)
                            PixelCopy.request(activity.window, Rect(location[0], location[1], location[0] + view.width, location[1] + view.height),
                                bitmap, { result -> copyResult = result; copied.countDown() }, Handler(Looper.getMainLooper()))
                        }
                        assertTrue(copied.await(5, TimeUnit.SECONDS))
                        assertEquals(PixelCopy.SUCCESS, copyResult)
                        saveDeviceScreenshot(context, "widget-$name-${width}x$height", bitmap)
                        runBlocking { app.settings.deleteWidget(id) }
                    }
                }
            }
        } finally {
            runBlocking { app.repository.logout(); app.reconcileSync() }
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            shell("appwidget revokebind --package ${context.packageName} --user 0")
        }
    }

    @Test fun realWidgetsRenderCompactWideTallAndClearOnLogout() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.quotaApp
        fun shell(command: String) { instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        } }
        shell("appwidget grantbind --package ${context.packageName} --user 0")
        instrumentation.uiAutomation.adoptShellPermissionIdentity(android.Manifest.permission.BIND_APPWIDGET)
        try {
            runBlocking { app.repository.demo() }
            listOf(Triple(140, 150, "light"), Triple(280, 150, "dark"), Triple(140, 230, "light")).forEach { (width, height, theme) ->
                ActivityScenario.launch<WidgetTestHostActivity>(Intent(context, WidgetTestHostActivity::class.java)
                    .putExtra("width", width).putExtra("height", height)).use { scenario ->
                    var id = 0
                    scenario.onActivity { id = it.widgetId }
                    runBlocking { app.settings.saveWidget(id, true, theme); app.updateWidgets() }
                    fun awaitText(expected: String) {
                        val deadline = System.currentTimeMillis() + 20_000
                        var matched = false
                        while (!matched && System.currentTimeMillis() < deadline) {
                            instrumentation.waitForIdleSync()
                            scenario.onActivity { activity ->
                                matched = text(activity.widgetView).contains(expected) &&
                                    texts(activity.widgetView).filter { it.text.isNotEmpty() }.all { it.layout != null }
                            }
                            if (!matched) Thread.sleep(100)
                        }
                        var actual = ""
                        scenario.onActivity { actual = "${it.widgetView.width}×${it.widgetView.height}: ${text(it.widgetView)}" }
                        assertTrue("Widget ${width}x$height missing $expected; actual $actual", matched)
                    }
                    awaitText("示例数据")
                    if (width >= 280 || height >= 230) awaitText("36%")
                    lateinit var bitmap: Bitmap
                    val copied = CountDownLatch(1)
                    var copyResult = -1
                    withLaidOutWidget(scenario) { activity ->
                        val visible = text(activity.widgetView)
                        val view = activity.widgetView
                        assertTrue("Missing primary quota: $visible", visible.contains("62%"))
                        if (width >= 280 || height >= 230) assertTrue("Missing secondary quota: $visible", visible.contains("36%"))
                        val timestamp = texts(view).single { it.text.startsWith("示例数据") }
                        val visibleTimestamp = Rect()
                        assertTrue(timestamp.getLocalVisibleRect(visibleTimestamp))
                        assertEquals("Timestamp must not be clipped at ${width}x$height", timestamp.height, visibleTimestamp.height())
                        bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                        val location = IntArray(2)
                        view.getLocationInWindow(location)
                        PixelCopy.request(activity.window, Rect(location[0], location[1], location[0] + view.width, location[1] + view.height),
                            bitmap, { result -> copyResult = result; copied.countDown() }, Handler(Looper.getMainLooper()))
                    }
                    assertTrue("Native widget capture timed out", copied.await(5, TimeUnit.SECONDS))
                    assertEquals(PixelCopy.SUCCESS, copyResult)
                    saveDeviceScreenshot(context, "widget-${width}x$height-$theme", bitmap)
                    runBlocking { app.repository.logout(); app.updateWidgets() }
                    awaitText("连接你的 Codex")
                    runBlocking { app.settings.deleteWidget(id); app.repository.demo() }
                }
            }
        } finally {
            runBlocking { app.repository.logout(); app.reconcileSync() }
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            shell("appwidget revokebind --package ${context.packageName} --user 0")
        }
    }

    private fun withLaidOutWidget(scenario: ActivityScenario<WidgetTestHostActivity>, capture: (WidgetTestHostActivity) -> Unit) {
        val deadline = System.currentTimeMillis() + 10_000
        var captured = false
        while (!captured && System.currentTimeMillis() < deadline) {
            // A new RemoteViews update can replace children between an earlier text check
            // and this callback. Check geometry and capture in the same UI-thread turn.
            scenario.onActivity { activity ->
                val view = activity.widgetView
                val labels = texts(view).filter { it.text.isNotEmpty() }
                if (!view.isLayoutRequested && view.width > 0 && view.height > 0 && labels.isNotEmpty() &&
                    labels.all { it.layout != null && !it.isLayoutRequested && it.width > 0 && it.height > 0 }) {
                    capture(activity)
                    captured = true
                }
            }
            if (!captured) Thread.sleep(100)
        }
        assertTrue("Widget text must finish layout before visual assertions and PixelCopy", captured)
    }

    private fun text(view: View): String = when (view) {
        is TextView -> view.text.toString()
        is ViewGroup -> (0 until view.childCount).joinToString(" ") { text(view.getChildAt(it)) }
        else -> ""
    }
    private fun geometry(view: View, depth: Int = 0): String = buildString {
        append(" ".repeat(depth))
        append("${view.javaClass.simpleName} ${view.width}x${view.height} at ${view.left},${view.top}")
        append(" padding=${view.paddingLeft},${view.paddingTop},${view.paddingRight},${view.paddingBottom}")
        if (view is TextView) append(" text=${view.text} fontPx=${view.textSize} minHeight=${view.minHeight}")
        append('\n')
        if (view is ViewGroup) for (index in 0 until view.childCount) append(geometry(view.getChildAt(index), depth + 1))
    }
    private fun texts(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }
}
