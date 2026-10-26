package com.tsonglew.quodex

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun startDisconnected() {
        runBlocking { compose.activity.quotaApp.repository.logout() }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("connect-button").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun demoThemeWidgetAndLogoutFlow() {
        compose.onNodeWithTag("demo-button").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("dashboard").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("示例数据 · 非真实额度", substring = false).assertIsDisplayed()
        screenshot("quota-light")
        compose.onNodeWithTag("tab-widgets").performClick()
        compose.onNodeWithTag("add-widget-button").assertExists()
        compose.onNodeWithTag("add-slim-widget-button").assertExists()
        screenshot("widgets-light")
        compose.onNodeWithTag("tab-settings").performClick()
        compose.onNodeWithTag("theme-dark").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("tab-quota").performClick()
        compose.waitForIdle()
        screenshot("quota-dark")
        compose.onNodeWithTag("tab-settings").performClick()
        compose.onNodeWithTag("logout-button").performScrollTo().performClick()
        compose.onNodeWithTag("confirm-logout").performClick()
        compose.onNodeWithTag("tab-quota").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("connect-button").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("connect-button").assertExists()
        screenshot("welcome-dark")
    }
    @Test fun manualAppUpdateCheckWorksWithAutomaticChecksOffAndShowsDownloadEntry() {
        val app = compose.activity.quotaApp as TestQuotaApplication
        val quotaRequests = app.usageRequests.get()
        val originalEnabled = app.updates.state.value.saved.enabled
        runBlocking { app.updates.setEnabled(false) }
        app.availableUpdate = com.tsonglew.quodex.update.AppUpdate(BuildConfig.VERSION_CODE + 1L,
            "0.1.0-alpha22", "https://github.com/tsonglew/Quodex/releases/tag/v0.1.0-alpha22", "a".repeat(40))
        try {
            compose.onNodeWithTag("tab-settings").performClick()
            compose.onNodeWithTag("automatic-app-updates").performScrollTo().assertIsOff()
            val before = app.updateRequests.get()
            compose.onNodeWithTag("check-app-update").performScrollTo().performClick()
            compose.waitUntil(10_000) { app.updateRequests.get() > before && !app.updates.state.value.checking }
            compose.onNodeWithTag("open-app-update").performScrollTo().assertIsDisplayed()
            screenshot("app-update-settings")
            compose.onNodeWithTag("tab-quota").performClick()
            compose.onNodeWithTag("open-app-update").assertIsDisplayed()
            org.junit.Assert.assertEquals(quotaRequests, app.usageRequests.get())
        } finally {
            app.availableUpdate = null
            runBlocking { app.updates.check(force = true); app.updates.setEnabled(originalEnabled) }
        }
    }

    @Test fun backgroundGuideCanSwitchBrandsAndCollapse() {
        compose.onNodeWithTag("tab-settings").performClick()
        compose.onNodeWithTag("background-guide-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("background-brand-picker").performScrollTo().performClick()
        compose.onNodeWithText("小米 / Redmi / POCO").performClick()
        compose.onNodeWithText("1. 允许自启动：", substring = true).performScrollTo().assertIsDisplayed()
        screenshot("background-guide-xiaomi")
        compose.onNodeWithTag("background-brand-picker").performScrollTo().performClick()
        compose.onNodeWithText("Samsung").performClick()
        compose.onNodeWithText("1. 排除休眠：", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("background-app-settings").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("background-guide-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("background-brand-picker").assertDoesNotExist()
    }

    @Test fun privacyNoticeIsAvailableOfflineInSettingsAndBeforeLogin() {
        compose.onNodeWithTag("tab-settings").performClick()
        compose.onNodeWithTag("privacy-notice-button").performScrollTo().performClick()
        compose.onNodeWithTag("privacy-notice-text").assertTextContains("缓存本身不是加密数据库", substring = true)
        saveDeviceScreenshot(compose.activity, "privacy-notice", compose.onNode(isDialog()).captureToImage().asAndroidBitmap())
        compose.onNodeWithTag("privacy-notice-close").performClick()
        compose.onNodeWithTag("privacy-notice-text").assertDoesNotExist()
        compose.onNodeWithTag("tab-quota").performClick()
        compose.onNodeWithTag("connect-button").performClick()
        compose.onNodeWithTag("privacy-notice-button").performScrollTo().performClick()
        compose.onNodeWithTag("privacy-notice-text").assertTextContains("此操作不撤销远程授权", substring = true)
        compose.onNodeWithTag("privacy-notice-close").performClick()
        compose.onNodeWithText("取消", substring = false).performClick()
    }

    @Test fun diagnosticsCanPreviewAndClearWithoutUploading() {
        val app = compose.activity.quotaApp as TestQuotaApplication
        val diagnostics = app.diagnostics
        val requestsBefore = app.usageRequests.get()
        compose.onNodeWithTag("tab-settings").performClick()
        // Exercise asynchronous read/clear continuations across repeated dialog
        // creation; an IO completion previously recomposed on a worker thread.
        repeat(5) {
            diagnostics.clear()
            diagnostics.record(com.tsonglew.quodex.diagnostics.DiagnosticEvent.HTTP_START, 0)
            compose.onNodeWithTag("diagnostics-preview").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("diagnostics-report").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("diagnostics-report").assertTextContains("Hourly observations schema=1", substring = true)
            compose.onNodeWithTag("diagnostics-report").assertTextContains("HTTP_START 0", substring = true)
            compose.onNodeWithTag("diagnostics-close").performClick()
            compose.onNodeWithTag("diagnostics-clear").performScrollTo().performClick()
            compose.waitUntil(10_000) { !diagnostics.report().contains("HTTP_START 0") }
            compose.onNodeWithTag("diagnostics-preview").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("diagnostics-report").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("diagnostics-report").assertTextContains("Recent events", substring = true)
            compose.onNodeWithTag("diagnostics-close").performClick()
        }
        org.junit.Assert.assertEquals("Diagnostics must not fetch quota", requestsBefore, app.usageRequests.get())
    }

    @Test fun diagnosticsReflectActualBackgroundRestriction() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes().decodeToString().trim() }
        }
        org.junit.Assume.assumeTrue("Owned emulator only", shell("getprop ro.kernel.qemu") == "1")
        val packageName = compose.activity.packageName
        val original = shell("cmd appops get $packageName RUN_ANY_IN_BACKGROUND")
        org.junit.Assume.assumeTrue("Preserve existing restrictions", original.contains("No operations.") ||
            Regex("RUN_ANY_IN_BACKGROUND: allow\\b").containsMatchIn(original))
        val activity = compose.activity.getSystemService(android.app.ActivityManager::class.java)
        val app = compose.activity.quotaApp as TestQuotaApplication
        val before = app.usageRequests.get()
        compose.onNodeWithTag("tab-settings").performClick()
        try {
            listOf(false, true, false).forEach { restricted ->
                shell("cmd appops set $packageName RUN_ANY_IN_BACKGROUND ${if (restricted) "ignore" else "allow"}")
                compose.waitUntil(10_000) { activity.isBackgroundRestricted == restricted }
                compose.onNodeWithTag("diagnostics-preview").performScrollTo().performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("diagnostics-report").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("diagnostics-report").assertTextContains("backgroundRestricted=$restricted", substring = true)
                compose.onNodeWithTag("diagnostics-close").performClick()
            }
            org.junit.Assert.assertEquals("Diagnostics must not fetch quota", before, app.usageRequests.get())
        } finally {
            shell("cmd appops set $packageName RUN_ANY_IN_BACKGROUND allow")
            compose.waitUntil(10_000) { !activity.isBackgroundRestricted }
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        saveDeviceScreenshot(compose.activity, name, compose.onRoot().captureToImage().asAndroidBitmap())
    }
}
