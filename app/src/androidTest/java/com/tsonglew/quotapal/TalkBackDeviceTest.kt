package com.tsonglew.quotapal

import android.Manifest
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.tsonglew.quotapal.data.Session
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit emulator probe: requires installed TalkBack and temporarily enables it. */
class TalkBackDeviceTest {
    @Test fun defaultFontQuotaTextKeepsItsOwnAccessibilityFocus() {
        assumeTrue("Run explicitly with -e talkbackProbe true",
            InstrumentationRegistry.getArguments().getString("talkbackProbe") == "true")
        withWidgetFontScale(1f) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            val injectExplorationEvent = accessibilityInputInjector(automation)
            val context = instrumentation.targetContext
            val app = context.quotaApp
            val createdWidgetIds = mutableSetOf<Int>()
            fun shell(command: String): String = automation.executeShellCommand(command).use {
                java.io.FileInputStream(it.fileDescriptor).bufferedReader().use { reader -> reader.readText().trim() }
            }
            val savedServices = shell("settings get secure enabled_accessibility_services")
            val savedEnabled = shell("settings get secure accessibility_enabled")
            val talkBackPackage = "com.google.android.marvin.talkback"
            val notificationsGranted = context.packageManager.checkPermission(Manifest.permission.POST_NOTIFICATIONS,
                talkBackPackage) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val accessibility = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            fun descendants(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
                listOf(node) + (0 until node.childCount).flatMap { node.getChild(it)?.let(::descendants).orEmpty() }
            fun await(message: String, condition: () -> Boolean) {
                val deadline = SystemClock.uptimeMillis() + 20_000
                while (SystemClock.uptimeMillis() < deadline) {
                    if (condition()) return
                    Thread.sleep(100)
                }
                automation.takeScreenshot()?.let { saveDeviceScreenshot(context, "talkback-default-font-failure", it) }
                val tree = automation.rootInActiveWindow?.let(::descendants).orEmpty().joinToString("\n") {
                    "${it.className}: text=${it.text}; description=${it.contentDescription}; focused=${it.isAccessibilityFocused}"
                }
                fail("$message\n$tree")
            }
            automation.adoptShellPermissionIdentity(Manifest.permission.BIND_APPWIDGET)
            try {
                if (android.os.Build.VERSION.SDK_INT >= 33 && !notificationsGranted)
                    shell("pm grant $talkBackPackage android.permission.POST_NOTIFICATIONS")
                shell("settings put secure enabled_accessibility_services com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService")
                shell("settings put secure accessibility_enabled 1")
                await("TalkBack must enable real touch exploration") { accessibility.isTouchExplorationEnabled }
                assertTrue("The actual TalkBack service must be bound", accessibility.getEnabledAccessibilityServiceList(
                    android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_SPOKEN).any {
                    it.resolveInfo.serviceInfo.packageName == talkBackPackage
                })
                for (slim in listOf(false, true)) {
                    runBlocking { app.repository.demo() }
                    ActivityScenario.launch<WidgetTestHostActivity>(Intent(context, WidgetTestHostActivity::class.java)
                        .putExtra("slim", slim).putExtra("width", 280).putExtra("height", if (slim) 70 else 150)).use { scenario ->
                        var widgetId = 0
                        scenario.onActivity { widgetId = it.widgetId; createdWidgetIds += widgetId }
                        runBlocking {
                            // The probe expects remaining quota on its own instance.
                            app.settings.saveWidget(widgetId, true, "system")
                            app.updateWidgets()
                        }
                        var quota: AccessibilityNodeInfo? = null
                        await("Default-font quota TextView must remain exposed beside the open-App action (slim=$slim)") {
                            val nodes = automation.rootInActiveWindow?.let(::descendants).orEmpty()
                            quota = nodes.firstOrNull { node -> node.text?.toString() == "62%" &&
                                node.className?.toString() == "android.widget.TextView" && node.isVisibleToUser }
                            quota != null && nodes.any { node -> node.contentDescription?.toString()?.endsWith("打开 QuotaPal") == true } &&
                                nodes.any { node -> node.contentDescription?.toString() == "刷新额度" }
                        }
                        val target = requireNotNull(quota)
                        val expectedBounds = Rect().also(target::getBoundsInScreen)
                        assertFalse("Quota text must have visible screen bounds", expectedBounds.isEmpty)
                        assertTrue("Quota TextView itself must accept accessibility focus (slim=$slim)",
                            target.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
                        await("Accessibility focus must stay on the quota TextView, not the transparent open-App action") {
                            val focused = automation.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                                ?: return@await false
                            focused.text?.toString() == "62%" && focused.className?.toString() == "android.widget.TextView" &&
                                Rect().also(focused::getBoundsInScreen) == expectedBounds
                        }
                        saveDeviceScreenshot(context, "talkback-default-${if (slim) "slim" else "standard"}-quota-focus",
                            requireNotNull(automation.takeScreenshot()))
                        // First move focus away so a no-op physical gesture cannot pass by
                        // leaving the programmatically focused, underlying TextView selected.
                        val refresh = requireNotNull(automation.rootInActiveWindow?.let(::descendants)?.firstOrNull {
                            node -> node.contentDescription?.toString() == "刷新额度"
                        })
                        assertTrue(refresh.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
                        await("Refresh must own focus before physical quota exploration") {
                            automation.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                                ?.contentDescription?.toString() == "刷新额度"
                        }
                        explore(automation, injectExplorationEvent, expectedBounds)
                        await("Physical exploration over visible 62% must speak quota, not only an open-App label (slim=$slim)") {
                            val focused = automation.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                            focused?.text?.toString()?.contains("62%") == true ||
                                focused?.contentDescription?.toString()?.contains("62%") == true
                        }
                        saveDeviceScreenshot(context, "talkback-default-${if (slim) "slim" else "standard"}-explored-quota",
                            requireNotNull(automation.takeScreenshot()))
                    }
                }
            } finally {
                for ((key, value) in listOf("enabled_accessibility_services" to savedServices, "accessibility_enabled" to savedEnabled))
                    shell(if (value == "null") "settings delete secure $key" else "settings put secure $key $value")
                if (android.os.Build.VERSION.SDK_INT >= 33 && !notificationsGranted)
                    shell("pm revoke $talkBackPackage android.permission.POST_NOTIFICATIONS")
                runBlocking {
                    createdWidgetIds.forEach { app.settings.deleteWidget(it) }
                    app.repository.logout(); app.reconcileSync(); app.updateWidgets()
                }
                automation.dropShellPermissionIdentity()
            }
        }
    }

    @Test fun talkBackFocusAndDoubleTapRefreshBothWidgetProviders() {
        assumeTrue("Run explicitly with -e talkbackProbe true",
            InstrumentationRegistry.getArguments().getString("talkbackProbe") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val injectExplorationEvent = accessibilityInputInjector(automation)
        val context = instrumentation.targetContext
        val app = context.quotaApp as TestQuotaApplication
        val createdWidgetIds = mutableSetOf<Int>()
        fun shell(command: String): String = automation.executeShellCommand(command).use {
            java.io.FileInputStream(it.fileDescriptor).bufferedReader().use { reader -> reader.readText().trim() }
        }
        val previousServices = shell("settings get secure enabled_accessibility_services")
        val previousEnabled = shell("settings get secure accessibility_enabled")
        val previousScale = shell("settings get system font_scale")
        val talkBackPackage = "com.google.android.marvin.talkback"
        val notificationsGranted = context.packageManager.checkPermission(Manifest.permission.POST_NOTIFICATIONS,
            talkBackPackage) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val accessibility = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        fun await(message: String, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 20_000
            while (SystemClock.uptimeMillis() < deadline) {
                if (condition()) return
                Thread.sleep(100)
            }
            automation.takeScreenshot()?.let { saveDeviceScreenshot(context, "talkback-failure", it) }
            fun describe(node: AccessibilityNodeInfo): String = buildString {
                append("${node.className}: text=${node.text}; description=${node.contentDescription}\n")
                for (index in 0 until node.childCount) node.getChild(index)?.let { append(describe(it)) }
            }
            android.util.Log.e("QuotaTalkBack", automation.rootInActiveWindow?.let(::describe) ?: "No active accessibility root")
            fail(message)
        }
        fun descendants(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
            listOf(node) + (0 until node.childCount).flatMap { node.getChild(it)?.let(::descendants).orEmpty() }
        fun doubleTap(bounds: Rect) {
            var previousUpTime = 0L
            repeat(2) {
                val start = SystemClock.uptimeMillis()
                if (previousUpTime != 0L)
                    assertTrue("Injected taps must stay within the platform double-tap timeout",
                        start - previousUpTime < ViewConfiguration.getDoubleTapTimeout())
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = fingerEvent(start, action, bounds.exactCenterX(), bounds.exactCenterY())
                    if (action == MotionEvent.ACTION_UP) previousUpTime = event.eventTime
                    try { injectExplorationEvent(event) } finally { event.recycle() }
                    Thread.sleep(50)
                }
            }
        }
        automation.adoptShellPermissionIdentity(Manifest.permission.BIND_APPWIDGET)
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33 && !notificationsGranted)
                shell("pm grant $talkBackPackage android.permission.POST_NOTIFICATIONS")
            shell("settings put system font_scale 2.0")
            shell("settings put secure enabled_accessibility_services com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService")
            shell("settings put secure accessibility_enabled 1")
            await("TalkBack must actually enable touch exploration") { accessibility.isTouchExplorationEnabled }
            assertTrue("The actual TalkBack service must be bound", accessibility.getEnabledAccessibilityServiceList(
                android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_SPOKEN).any {
                it.resolveInfo.serviceInfo.packageName == "com.google.android.marvin.talkback"
            })
            await("The platform must apply large fonts") { context.resources.configuration.fontScale >= 1.9f }
            for (slim in listOf(false, true)) {
                runBlocking {
                    app.repository.logout()
                    app.usedPercent = 38
                    app.repository.connect(Session("test-access", "test-refresh", "test-account", Long.MAX_VALUE))
                    app.settings.syncResult(null, lastAttemptAt = 0)
                }
                ActivityScenario.launch<WidgetTestHostActivity>(Intent(context, WidgetTestHostActivity::class.java)
                    .putExtra("slim", slim).putExtra("width", 280).putExtra("height", if (slim) 70 else 150)).use { scenario ->
                    var widgetId = 0
                    scenario.onActivity { widgetId = it.widgetId; createdWidgetIds += widgetId }
                    runBlocking {
                        // Do not inherit another test's per-instance display choice.
                        app.settings.saveWidget(widgetId, true, "system")
                        app.updateWidgets()
                    }
                    var refresh: AccessibilityNodeInfo? = null
                    await("TalkBack tree must expose quota, success time and refresh") {
                        val root = automation.rootInActiveWindow ?: return@await false
                        val nodes = descendants(root)
                        refresh = nodes.firstOrNull { node -> node.contentDescription?.toString() == "刷新额度" }
                        nodes.any { node -> node.contentDescription?.toString()?.let { description ->
                            description.contains("62%") && description.contains("最后成功更新于")
                        } == true } && refresh != null
                    }
                    var target = requireNotNull(refresh)
                    while (!target.isClickable && target.parent != null) target = target.parent
                    assertTrue("Refresh must have its own clickable accessibility target", target.isClickable)
                    val bounds = Rect().also(target::getBoundsInScreen)
                    assertFalse(bounds.isEmpty)
                    // Let TalkBack establish its own focus from exploration before
                    // double-tapping, rather than only setting framework focus remotely.
                    explore(automation, injectExplorationEvent, bounds)
                    await("TalkBack exploration must focus the refresh control") {
                        automation.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                            ?.contentDescription?.toString() == "刷新额度"
                    }
                    saveDeviceScreenshot(context, "talkback-${if (slim) "slim" else "standard"}-focus",
                        requireNotNull(automation.takeScreenshot()))
                    val before = app.usageRequests.get()
                    app.usedPercent = 17
                    // Keep this gesture separate from the exploration's final UP.
                    Thread.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 50)
                    automation.executeAndWaitForEvent({ doubleTap(bounds) }, { event ->
                        event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED &&
                            event.source?.contentDescription?.toString() == "刷新额度"
                    }, 5_000)
                    await("TalkBack double-tap must fetch a new quota") {
                        app.repository.state.value.snapshot?.windows?.singleOrNull()?.usedPercent == 17.0
                    }
                    await("Refreshed quota must reach the accessibility tree") {
                        automation.rootInActiveWindow?.let(::descendants)?.any { node ->
                            node.contentDescription?.toString()?.contains("83%") == true
                        } == true
                    }
                    assertEquals("One TalkBack activation shares one request", before + 1, app.usageRequests.get())
                    saveDeviceScreenshot(context, "talkback-${if (slim) "slim" else "standard"}-updated",
                        requireNotNull(automation.takeScreenshot()))
                }
            }
        } finally {
            for ((key, value) in listOf("enabled_accessibility_services" to previousServices, "accessibility_enabled" to previousEnabled)) {
                shell(if (value == "null") "settings delete secure $key" else "settings put secure $key $value")
            }
            shell(if (previousScale == "null") "settings delete system font_scale" else "settings put system font_scale $previousScale")
            if (android.os.Build.VERSION.SDK_INT >= 33 && !notificationsGranted)
                shell("pm revoke $talkBackPackage android.permission.POST_NOTIFICATIONS")
            runBlocking {
                createdWidgetIds.forEach { app.settings.deleteWidget(it) }
                app.repository.logout(); app.reconcileSync(); app.updateWidgets()
            }
            automation.dropShellPermissionIdentity()
        }
    }

    private fun accessibilityInputInjector(automation: UiAutomation): (MotionEvent) -> Unit {
        // This platform test API is absent from the public SDK. It enters the
        // accessibility filter; injectInputEvent deliberately bypasses that filter.
        // Fail instead of falling back to input that would give false positives.
        val method = try {
            UiAutomation::class.java.getMethod("injectInputEventToInputFilter", InputEvent::class.java)
        } catch (missing: NoSuchMethodException) {
            throw AssertionError("TalkBack probes require injectInputEventToInputFilter; run on the API37 lab with --no-hidden-api-checks", missing)
        }
        // Run only on the owned API37 emulator with am instrument
        // --no-hidden-api-checks -e talkbackProbe true. The service supplies the
        // PASS_TO_USER | INJECTED policy flags; do not invent event-policy bits.
        return { event ->
            android.util.Log.i("QuotaTalkBack", "Filter input ${MotionEvent.actionToString(event.actionMasked)} " +
                "at=${event.eventTime} down=${event.downTime} device=${event.deviceId} tool=${event.getToolType(0)}")
            method.invoke(automation, event)
            Unit
        }
    }

    private fun fingerEvent(downTime: Long, action: Int, x: Float, y: Float): MotionEvent = MotionEvent.obtain(
        downTime, SystemClock.uptimeMillis(), action, 1,
        arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }),
        arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = if (action == MotionEvent.ACTION_UP) 0f else 1f; size = 1f }),
        0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)

    private fun explore(automation: UiAutomation, inject: (MotionEvent) -> Unit, bounds: Rect) {
        // Ordinary injectInputEvent skips accessibility processing. The explicit
        // exploration-start event proves this gesture entered the real filter.
        automation.executeAndWaitForEvent({
            val downTime = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP)) {
                val x = bounds.exactCenterX() + if (action == MotionEvent.ACTION_DOWN) -2f else 0f
                val event = fingerEvent(downTime, action, x, bounds.exactCenterY())
                try { inject(event) } finally { event.recycle() }
                if (action != MotionEvent.ACTION_UP) Thread.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 50)
            }
        }, { event -> event.eventType == AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START }, 5_000)
    }
}
