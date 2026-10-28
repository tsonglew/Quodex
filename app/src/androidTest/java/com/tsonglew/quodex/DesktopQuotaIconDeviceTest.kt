package com.tsonglew.quodex

import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.tsonglew.quodex.data.FailureKind
import com.tsonglew.quodex.data.Session
import com.tsonglew.quodex.data.demoSnapshot
import com.tsonglew.quodex.launcher.DesktopIconContent
import com.tsonglew.quodex.launcher.DesktopIconStatus
import com.tsonglew.quodex.launcher.DesktopQuotaIcon
import com.tsonglew.quodex.launcher.QuotaIconRenderer
import com.tsonglew.quodex.sync.UsageSyncWorker
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.FileInputStream
import java.util.concurrent.TimeUnit

class DesktopQuotaIconDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    // Opening a real launcher shortcut may replace the original ActivityScenario instance.
    private val app get() = instrumentation.targetContext.applicationContext as TestQuotaApplication
    private val shortcuts get() = app.getSystemService(ShortcutManager::class.java)
    private var prepared = false
    private var resumedLauncher = false
    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        FileInputStream(it.fileDescriptor).bufferedReader().use { reader -> reader.readText().trim() }
    }

    @Before fun prepare() = runBlocking {
        org.junit.Assume.assumeTrue("Desktop pinning validation uses a disposable emulator", shell("getprop ro.kernel.qemu") == "1")
        prepared = true
        app.repository.logout()
        app.settings.desktopIconEnabled(true)
        app.settings.desktopIconWindow("short")
        app.usageStatusCode = 200
        app.usageTransportFailure = null
    }

    @After fun restore() = runBlocking {
        if (!prepared) return@runBlocking
        app.usageStatusCode = 200
        app.usageTransportFailure = null
        app.repository.logout()
        app.settings.desktopIconEnabled(true)
        app.settings.desktopIconWindow("short")
        app.desktopIcon.update()
        app.reconcileSync()
    }

    @Test fun settingsPreviewSelectsWindowAndCanRestoreOriginalLogo() {
        runBlocking { app.repository.demo(demoSnapshot(java.time.Instant.now().epochSecond)) }
        compose.onNodeWithTag("tab-settings").performClick()
        compose.onNodeWithTag("desktop-icon-preview").performScrollTo()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Quodex · 示例 · 5 小时额度 剩余 36%").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("desktop-icon-window-weekly").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Quodex · 示例 · 每周额度 剩余 62%").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("desktop-icon-settings").performScrollTo()
        saveDeviceScreenshot(app, "desktop-icon-settings", compose.onNodeWithTag("desktop-icon-settings").captureToImage().asAndroidBitmap())
        compose.onNodeWithTag("desktop-icon-toggle").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Quodex").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("desktop-icon-window-weekly").assertIsNotEnabled()
    }

    @Test fun pinnedIconUpdatesFromWorkerKeepsItsIdAndClearsOnLogout() = runBlocking {
        org.junit.Assume.assumeTrue(shortcuts.isRequestPinShortcutSupported)
        app.settings.desktopIconWindow("weekly")
        app.usedPercent = 38
        app.repository.connect(Session("test-access", "test-refresh", "test-account", Long.MAX_VALUE))
        compose.onNodeWithTag("tab-settings").performClick()
        compose.onNodeWithTag("add-desktop-icon").performScrollTo().performClick()
        if (shortcuts.pinnedShortcuts.none { it.id == DesktopQuotaIcon.SHORTCUT_ID }) confirmPinDialog()
        waitFor { shortcuts.pinnedShortcuts.any { it.id == DesktopQuotaIcon.SHORTCUT_ID } }
        app.desktopIcon.update()
        app.reconcileSync()
        fun pinned() = shortcuts.pinnedShortcuts.single { it.id == DesktopQuotaIcon.SHORTCUT_ID }
        assertEquals(62, pinned().extras?.getInt("remaining_percent"))
        assertEquals(1, shortcuts.pinnedShortcuts.count { it.id == DesktopQuotaIcon.SHORTCUT_ID })
        assertEquals(MainActivity::class.java.name, pinned().intent?.component?.className)
        val work = WorkManager.getInstance(app).getWorkInfosForUniqueWork("codex-periodic-sync").get(10, TimeUnit.SECONDS)
        assertEquals("A pinned quota icon must keep background polling active", 1, work.count { !it.state.isFinished })

        app.usedPercent = 71
        app.settings.syncResult(null, lastAttemptAt = 0)
        val worker = TestListenableWorkerBuilder<UsageSyncWorker>(app).setInputData(workDataOf("force" to true)).build()
        assertEquals(androidx.work.ListenableWorker.Result.success(), worker.doWork())
        assertEquals(29, pinned().extras?.getInt("remaining_percent"))
        assertTrue(pinned().longLabel.toString().contains("29%"))

        app.usageTransportFailure = java.io.IOException("Synthetic offline state")
        app.settings.syncResult(null, lastAttemptAt = 0)
        worker.doWork()
        assertEquals(FailureKind.NETWORK, app.repository.state.value.failure)
        assertEquals(29, pinned().extras?.getInt("remaining_percent"))
        assertEquals(DesktopIconStatus.CACHED.name, pinned().extras?.getString("quota_status"))
        assertTrue(app.desktopIcon.update())
        assertEquals(1, shortcuts.pinnedShortcuts.count { it.id == DesktopQuotaIcon.SHORTCUT_ID })
        shell("input keyevent 3")
        waitFor { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("launcher") == true }
        clickPinnedIcon(pinned().longLabel.toString())
        waitFor { instrumentation.uiAutomation.rootInActiveWindow?.packageName == app.packageName }

        app.repository.logout()
        app.desktopIcon.update()
        app.reconcileSync()
        assertEquals(-1, pinned().extras?.getInt("remaining_percent"))
        assertEquals(DesktopIconStatus.DISCONNECTED.name, pinned().extras?.getString("quota_status"))
        assertTrue(pinned().isEnabled)
    }

    @Test fun periodicWorkerDoesNotFetchWhenTheIconIsDisabledAndNoWidgetNeedsUpdates() = runBlocking {
        app.settings.desktopIconEnabled(false)
        app.repository.connect(Session("test-access", "test-refresh", "test-account", Long.MAX_VALUE))
        org.junit.Assume.assumeFalse("This regression requires no widget consumers", app.backgroundSyncRequired())
        app.settings.syncResult(null, lastAttemptAt = 0)
        val requests = app.usageRequests.get()
        val worker = TestListenableWorkerBuilder<UsageSyncWorker>(app)
            .setInputData(workDataOf("periodic" to true, "force" to true)).build()
        assertEquals(androidx.work.ListenableWorker.Result.success(), worker.doWork())
        assertEquals("Removing the last quota surface must not keep polling", requests, app.usageRequests.get())
    }

    @Test fun iconRasterFitsAdaptiveMasksForAllPercentWidthsAndStatuses() {
        val samples = listOf(0, 9, 62, 100).map {
            DesktopIconContent(it, "5h", DesktopIconStatus.CURRENT, "$it%")
        } + listOf(
            DesktopIconContent(62, "周", DesktopIconStatus.CACHED, "旧数据"),
            DesktopIconContent(36, "5h", DesktopIconStatus.DEMO, "示例"),
            DesktopIconContent(null, "周", DesktopIconStatus.UNAVAILABLE, "暂无"),
            DesktopIconContent(null, "", DesktopIconStatus.RESTRICTED, "受限"),
            DesktopIconContent(null, "", DesktopIconStatus.DISCONNECTED, "未连接"),
            DesktopIconContent(null, "", DesktopIconStatus.UNAVAILABLE, "Quodex", false),
        )
        val sheet = Bitmap.createBitmap(216 * 5, 216 * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.LTGRAY)
        samples.forEachIndexed { index, content ->
            val icon = QuotaIconRenderer.render(app, content, 216)
            canvas.save()
            val x = (index % 5) * 216f
            val y = (index / 5) * 216f
            canvas.clipPath(android.graphics.Path().apply { addCircle(x + 108, y + 108, 72f, android.graphics.Path.Direction.CW) })
            canvas.drawBitmap(icon, x, y, null)
            canvas.restore()
        }
        saveDeviceScreenshot(app, "desktop-icon-contact-sheet", sheet)
    }

    private fun confirmPinDialog() {
        waitFor {
            val root = instrumentation.uiAutomation.rootInActiveWindow ?: return@waitFor false
            if (root.packageName?.toString()?.contains("launcher") != true) return@waitFor false
            val target = descendants(root).asSequence().filter {
                it.text?.toString()?.lowercase() in listOf("add automatically", "add to home screen", "add")
            }.mapNotNull { button ->
                generateSequence(button) { it.parent }.firstOrNull { it.isClickable }
            }.firstOrNull() ?: return@waitFor false
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }

    private fun clickPinnedIcon(description: String) {
        Thread.sleep(500)
        repeat(5) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val screen = android.graphics.Rect().also { root?.getBoundsInScreen(it) }
            val icon = root?.let(::descendants)?.firstOrNull { node ->
                val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
                node.isVisibleToUser && screen.contains(bounds.centerX(), bounds.centerY()) &&
                    node.contentDescription?.toString() == description
            }
            if (icon != null) {
                instrumentation.uiAutomation.takeScreenshot()?.let { saveDeviceScreenshot(app, "desktop-icon-launcher", it) }
                val target = generateSequence(icon) { it.parent }.first { it.isClickable }
                val bounds = android.graphics.Rect().also { target.getBoundsInScreen(it) }
                shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
                return
            }
            shell("input swipe ${screen.width() * 4 / 5} ${screen.height() / 2} ${screen.width() / 5} ${screen.height() / 2} 250")
            Thread.sleep(500)
        }
        fail("The updated pinned quota icon was not visible on the launcher")
    }

    private fun descendants(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(node) +
        (0 until node.childCount).flatMap { node.getChild(it)?.let(::descendants).orEmpty() }

    private fun waitFor(predicate: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            // The known boot-time dialog may surface on pin confirmation or on returning Home.
            // Allow one recovery for the whole test; subsequent ANRs and App failures still fail.
            if (!resumedLauncher && dismissLauncherAnrOnEmulator(instrumentation, LauncherAnrResponse.WAIT)) {
                resumedLauncher = true
            }
            if (predicate()) return
            Thread.sleep(100)
        }
        instrumentation.uiAutomation.takeScreenshot()?.let { saveDeviceScreenshot(app, "desktop-icon-failure", it) }
        fail("Desktop icon condition was not reached; focused package=${instrumentation.uiAutomation.rootInActiveWindow?.packageName}")
    }
}
