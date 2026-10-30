package com.tsonglew.quodex

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import com.tsonglew.quodex.ui.InAppUpdateDialog
import com.tsonglew.quodex.ui.QuotaTheme
import androidx.test.platform.app.InstrumentationRegistry
import com.tsonglew.quodex.update.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Rule
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class InAppUpdateDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun downloadRetryShowsAttemptAndCanBeCancelledDuringBackoff() = runBlocking {
        val fixture = File(context.applicationInfo.sourceDir)
        val candidate = metadata(fixture, BuildConfig.VERSION_CODE + 1L)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val entered = CompletableDeferred<Unit>()
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(503).message("Transient fixture")
                .body("".toResponseBody()).build()
        }.build()
        val updater = InAppUpdate(context, scope, UpdateSource { candidate }, ApkDownloader(client,
            requestRetry = com.tsonglew.quodex.network.RequestRetry(pause = { entered.complete(Unit); awaitCancellation() })))
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.setContent { QuotaTheme { InAppUpdateDialog(updater) } } }
                updater.start(candidate)
                withTimeout(5000) { entered.await() }
                compose.onNodeWithTag("download-retry-message").assertIsDisplayed().assertTextContains("2/3", substring = true)
                saveDeviceScreenshot(context, "in-app-update-retry", compose.onNode(isDialog()).captureToImage().asAndroidBitmap())
                compose.onNodeWithTag("cancel-app-update").performClick()
                withTimeout(5000) { while (updater.state.value.busy) delay(20) }
                assertEquals(InstallStage.IDLE, updater.state.value.stage)
                compose.onNodeWithTag("download-retry-message").assertDoesNotExist()
                assertEquals(1, requests.get())
                assertTrue(File(context.cacheDir, "updates").listFiles().orEmpty().none { it.extension == "part" })
            }
        } finally { updater.cancel(); scope.cancel() }
        Unit
    }

    @Test fun providerSharesOnlyTheUpdateDirectoryAndVerifierRejectsExistingVersion() {
        val installed = File(context.applicationInfo.sourceDir)
        val copy = File(context.cacheDir, "updates/provider-test.apk").apply { parentFile!!.mkdirs(); installed.copyTo(this, true) }
        try {
            val intent = apkInstallIntent(context, copy)
            assertEquals("content", intent.data!!.scheme)
            assertEquals("${context.packageName}.updates", intent.data!!.authority)
            assertEquals("application/vnd.android.package-archive", intent.type)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            context.contentResolver.openInputStream(intent.data!!)!!.use { assertEquals(copy.length(), it.readBytes().size.toLong()) }
            try { apkInstallIntent(context, File(context.cacheDir, "unrelated.txt")); fail("Must not share unrelated cache files") }
            catch (_: IllegalArgumentException) { }
            try { validateDownloadedApk(context, copy, metadata(copy, BuildConfig.VERSION_CODE.toLong())); fail("Must reject same version") }
            catch (_: IOException) { }
            try { validateDownloadedApk(context, copy, metadata(copy, BuildConfig.VERSION_CODE + 1L)); fail("Must reject incorrect target version") }
            catch (_: IOException) { }
        } finally { copy.delete() }
    }

    /** Run with scripts/update_fixture.sh and -e updateFixture /sdcard/Android/data/.../candidate.apk. */
    @Test fun signedNewerApkDownloadsVerifiesAndOpensSystemInstaller() = runBlocking {
        val path = InstrumentationRegistry.getArguments().getString("updateFixture")
        assumeTrue("Explicit signed fixture required", path != null)
        val fixture = File(requireNotNull(path))
        val candidate = metadata(fixture, BuildConfig.VERSION_CODE + 1L)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            if (requests.incrementAndGet() == 1) return@addInterceptor Response.Builder().request(chain.request())
                .protocol(Protocol.HTTP_1_1).code(503).message("Transient fixture").body("".toResponseBody()).build()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body(fixture.readBytes().toResponseBody("application/vnd.android.package-archive".toMediaType())).build()
        }.build()
        val updater = InAppUpdate(context, scope, UpdateSource { candidate }, ApkDownloader(client))
        try {
            updater.start(candidate)
            updater.dismiss()
            withTimeout(10_000) { while (updater.state.value.busy) delay(20) }
            assertEquals(updater.state.value.message?.toString(), InstallStage.READY, updater.state.value.stage)
            assertEquals("Transient failure retries before APK verification", 2, requests.get())
            assertFalse(updater.state.value.visible)
            updater.start(candidate)
            assertTrue(updater.state.value.visible)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { QuotaTheme { InAppUpdateDialog(updater) } } }
                compose.onNodeWithTag("install-app-update").assertIsDisplayed()
                saveDeviceScreenshot(context, "in-app-update-ready", compose.onNode(isDialog()).captureToImage().asAndroidBitmap())
                assertFalse("Run this probe with install permission initially denied", context.packageManager.canRequestPackageInstalls())
                compose.onNodeWithTag("install-app-update").performClick()
                withTimeout(10_000) {
                    while (findText(instrumentation.uiAutomation.rootInActiveWindow, "Allow from this source") == null) delay(100)
                }
                instrumentation.uiAutomation.waitForIdle(500, 5_000)
                saveDeviceScreenshot(context, "in-app-update-permission", checkNotNull(instrumentation.uiAutomation.takeScreenshot()))
                val label = checkNotNull(findText(instrumentation.uiAutomation.rootInActiveWindow, "Allow from this source"))
                val toggle = generateSequence(label) { it.parent }.first { it.isClickable && it.isCheckable }
                assertTrue(toggle.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                withTimeout(5_000) { while (!context.packageManager.canRequestPackageInstalls()) delay(100) }
                instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                // Returning from permission settings must automatically continue to the system installer.
                // Require the system's update confirmation, not merely a package installer window starting.
                withTimeout(10_000) {
                    while (true) {
                        val root = instrumentation.uiAutomation.rootInActiveWindow
                        val packageName = root?.packageName?.toString().orEmpty()
                        if (packageName.contains("packageinstaller") &&
                            findText(root, "Update") != null) break
                        delay(100)
                    }
                }
                instrumentation.uiAutomation.waitForIdle(500, 5_000)
                saveDeviceScreenshot(context, "in-app-update-installer", checkNotNull(instrumentation.uiAutomation.takeScreenshot()))
                instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            }
        } finally {
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            scope.cancel()
        }
        Unit
    }

    private fun findText(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        node ?: return null
        if (node.text?.toString()?.contains(text, ignoreCase = true) == true) return node
        for (index in 0 until node.childCount) findText(node.getChild(index), text)?.let { return it }
        return null
    }

    private fun metadata(file: File, code: Long) = AppUpdate(code, "0.1.0-alpha$code",
        "https://github.com/tsonglew/Quodex/releases/tag/test-update", "a".repeat(40),
        "https://github.com/tsonglew/Quodex/releases/download/test-update/Quodex-0.1.0-alpha$code.apk",
        file.length(), MessageDigest.getInstance("SHA-256").digest(file.readBytes()).hex())
}
