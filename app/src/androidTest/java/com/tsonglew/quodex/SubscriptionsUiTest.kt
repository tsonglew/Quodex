package com.tsonglew.quodex

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.tsonglew.quodex.data.FailureKind
import com.tsonglew.quodex.subscriptions.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test

class SubscriptionsUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    // Cleanup must not resolve an Activity while a locale change is recreating it.
    private val app get() = instrumentation.targetContext.applicationContext as TestQuotaApplication

    @Before fun clearProviders() {
        app.subscriptionFailure = null
        app.subscriptionDelayMillis = 0L
        runBlocking {
            app.repository.logout()
            SubscriptionProvider.entries.forEach { app.subscriptions.disconnect(it) }
        }
    }

    @After fun removeFixtureCredentials() {
        app.subscriptionFailure = null
        app.subscriptionDelayMillis = 0L
        runBlocking { SubscriptionProvider.entries.forEach { app.subscriptions.disconnect(it) } }
    }

    @Test fun connectRefreshFailureAndDisconnectKeepOtherServicesIsolated() {
        compose.onNodeWithTag("tab-subscriptions").performClick()
        compose.onNodeWithTag("subscription-connect-DEEPSEEK").performScrollTo().performClick()
        compose.onNodeWithTag("subscription-credential").performTextInput("fixture-only-deepseek")
        compose.onAllNodesWithText("连接").onLast().performClick()
        compose.waitUntil(5000) { app.subscriptions.state.value.getValue(SubscriptionProvider.DEEPSEEK).connected }
        compose.onNodeWithText("CNY 110").performScrollTo().assertIsDisplayed()
        val file = java.io.File(app.noBackupFilesDir, "subscriptions.enc")
        assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("fixture-only-deepseek"))
        assertEquals("fixture-only-deepseek", EncryptedSubscriptionStore(app).read()[SubscriptionProvider.DEEPSEEK]?.credential)
        app.subscriptionFailure = FailureKind.AUTH
        runBlocking { app.subscriptions.refresh(SubscriptionProvider.DEEPSEEK, true) }
        compose.onNodeWithTag("subscription-error-DEEPSEEK").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("CNY 110").performScrollTo().assertIsDisplayed()
        app.subscriptionFailure = null
        runBlocking { app.subscriptions.connect(SubscriptionProvider.CURSOR, "fixture-only-cursor") }
        compose.onNode(hasText("断开连接") and hasAnyAncestor(hasTestTag("subscription-DEEPSEEK"))).performScrollToVisible(compose).performClick()
        compose.waitUntil(5000) { !app.subscriptions.state.value.getValue(SubscriptionProvider.DEEPSEEK).connected }
        assertTrue(app.subscriptions.state.value.getValue(SubscriptionProvider.CURSOR).connected)
    }

    @Test fun examplesShowAllProvidersWithoutNetworkAndCaptureNativeUi() {
        val requests = app.subscriptionRequests.get()
        compose.onNodeWithTag("tab-subscriptions").performClick()
        compose.onNodeWithTag("subscription-examples").performClick()
        compose.onNodeWithText("示例数据 · 非真实账号").assertIsDisplayed()
        for ((provider, file) in listOf(
            SubscriptionProvider.DEEPSEEK to "subscriptions-deepseek",
            SubscriptionProvider.OPENCODE_GO to "subscriptions-opencode-go",
            SubscriptionProvider.OPENCODE_ZEN to "subscriptions-opencode-zen",
            SubscriptionProvider.CURSOR to "subscriptions-cursor")) {
            if (provider == SubscriptionProvider.DEEPSEEK)
                compose.onNodeWithTag("subscription-${provider.name}").performScrollTo()
            else compose.onNode(hasText("官方控制台") and hasAnyAncestor(hasTestTag("subscription-${provider.name}")))
                .performScrollToVisible(compose)
            compose.waitForIdle()
            saveDeviceScreenshot(InstrumentationRegistry.getInstrumentation().targetContext, file,
                compose.onRoot().captureToImage().asAndroidBitmap())
        }
        assertEquals(requests, app.subscriptionRequests.get())
    }

    @Test fun cancelFirstConnectionDoesNotSaveCredentials() {
        app.subscriptionDelayMillis = 60_000
        compose.onNodeWithTag("tab-subscriptions").performClick()
        compose.onNodeWithTag("subscription-connect-DEEPSEEK").performScrollToVisible(compose).performClick()
        compose.onNodeWithTag("subscription-credential").performTextInput("fixture-only-cancel")
        compose.onAllNodesWithText("连接").onLast().performClick()
        compose.waitUntil(5000) { app.subscriptions.state.value.getValue(SubscriptionProvider.DEEPSEEK).busy }
        compose.onNodeWithText("取消").performScrollToVisible(compose).performClick()
        compose.waitUntil(5000) { !app.subscriptions.state.value.getValue(SubscriptionProvider.DEEPSEEK).busy }
        assertFalse(app.subscriptions.state.value.getValue(SubscriptionProvider.DEEPSEEK).connected)
        assertFalse(java.io.File(app.noBackupFilesDir, "subscriptions.enc").exists())
    }

    @Test fun englishAndJapaneseSubscriptionsSurviveLanguageRecreation() {
        var primaryFailure: Throwable? = null
        try {
            for ((language, heading, notice) in listOf(
                Triple("en", "App language", "Sample data · not your account"),
                Triple("ja", "アプリの言語", "サンプルデータ · 実際のアカウントではありません"))) {
                compose.onNodeWithTag("tab-settings").performClick()
                compose.onNodeWithTag("language-$language").performScrollToVisible(compose).performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithText(heading).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("tab-subscriptions").performClick()
                if (compose.onAllNodesWithText(notice).fetchSemanticsNodes().isEmpty())
                    compose.onNodeWithTag("subscription-examples").performScrollToVisible(compose).performClick()
                compose.onNodeWithText(notice).performScrollToVisible(compose).assertIsDisplayed()
                compose.onNodeWithTag("tab-subscriptions").assertIsSelected()
                saveDeviceScreenshot(app, "subscriptions-$language", compose.onRoot().captureToImage().asAndroidBitmap())
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                instrumentation.runOnMainSync {
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        app.getSystemService(android.app.LocaleManager::class.java).applicationLocales =
                            android.os.LocaleList.forLanguageTags("zh-Hans")
                    } else com.tsonglew.quodex.i18n.AppLanguages.select("zh-Hans")
                    app.languageChanged()
                }
                instrumentation.waitForIdleSync()
                assertEquals("zh-Hans", com.tsonglew.quodex.i18n.AppLanguages.selectedTag(app))
            } catch (cleanupFailure: Throwable) {
                val original = primaryFailure
                if (original == null) throw cleanupFailure
                original.addSuppressed(cleanupFailure)
            }
        }
    }
}
