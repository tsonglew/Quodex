package com.tsonglew.quodex

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
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
        compose.openProvider("DEEPSEEK")
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
        compose.onAllNodesWithText("示例数据 · 非真实账号").onFirst().assertIsDisplayed()
        for ((provider, file) in listOf(
            SubscriptionProvider.DEEPSEEK to "subscriptions-deepseek",
            SubscriptionProvider.OPENCODE_GO to "subscriptions-opencode-go",
            SubscriptionProvider.OPENCODE_ZEN to "subscriptions-opencode-zen",
            SubscriptionProvider.CURSOR to "subscriptions-cursor")) {
            compose.openProvider(provider.name)
            compose.onNode(hasText("官方控制台") and hasAnyAncestor(hasTestTag("subscription-${provider.name}")))
                .performScrollToVisible(compose)
            compose.waitForIdle()
            saveDeviceScreenshot(InstrumentationRegistry.getInstrumentation().targetContext, file,
                compose.onRoot().captureToImage().asAndroidBitmap())
            compose.onNodeWithTag("navigate-back").performClick()
        }
        assertEquals(requests, app.subscriptionRequests.get())
    }

    @Test fun newProviderFiltersAndGlassCardsKeepTheirSourceMeaning() {
        val requests = app.subscriptionRequests.get()
        val previous = runBlocking { app.settings.read() }
        try {
            compose.onNodeWithTag("tab-subscriptions").performClick()
            compose.onNodeWithTag("subscription-examples").performClick()
            compose.onNodeWithTag("subscription-filter-1").performClick()
            compose.onNodeWithTag("account-KIMI").assertExists()
            compose.onNodeWithTag("account-KIMI_API").assertDoesNotExist()
            compose.onNodeWithTag("subscription-filter-2").performScrollToVisible(compose).performClick()
            compose.onNodeWithTag("account-KIMI").assertDoesNotExist()
            compose.onNodeWithTag("account-KIMI_API").assertExists()
            compose.onNodeWithTag("subscription-filter-0").performScrollToVisible(compose).performClick()
            for (theme in listOf("light", "dark")) {
                runBlocking { app.settings.theme(theme) }
                for (provider in listOf(SubscriptionProvider.KIMI, SubscriptionProvider.WORKBUDDY,
                    SubscriptionProvider.GROK, SubscriptionProvider.KIMI_API, SubscriptionProvider.GROK_API,
                    SubscriptionProvider.CURSOR_BILLING)) {
                    compose.openProvider(provider.name)
                    compose.onNode(hasText("官方控制台") and hasAnyAncestor(hasTestTag("subscription-${provider.name}")))
                        .performScrollToVisible(compose).assertIsDisplayed()
                    alignCardForScreenshot(provider)
                    saveDeviceScreenshot(app, "providers-${provider.name.lowercase()}-$theme",
                        requireNotNull(instrumentation.uiAutomation.takeScreenshot()))
                    compose.onNodeWithTag("navigate-back").performClick()
                }
            }
            assertEquals(requests, app.subscriptionRequests.get())
        } finally { runBlocking { app.settings.theme(previous.theme) } }
    }

    @Test fun workbuddySessionFormAndGrokImportAreSeparateFromApiKeys() {
        compose.onNodeWithTag("tab-subscriptions").performClick()
        compose.openProvider("WORKBUDDY")
        compose.onNodeWithTag("subscription-connect-WORKBUDDY").performScrollToVisible(compose).performClick()
        compose.onNodeWithTag("subscription-credential").performTextInput("fixture-session")
        compose.onNodeWithTag("subscription-cookie-chunk").performScrollToVisible(compose).performTextInput("fixture-chunk")
        compose.onNodeWithTag("subscription-browser-agent").performScrollToVisible(compose).performTextInput("Synthetic Browser/1.0")
        compose.onAllNodesWithText("连接").onLast().performClick()
        compose.waitUntil(5000) { app.subscriptions.state.value.getValue(SubscriptionProvider.WORKBUDDY).connected }
        val account = EncryptedSubscriptionStore(app).read().getValue(SubscriptionProvider.WORKBUDDY)
        assertEquals("Synthetic Browser/1.0", WorkBuddySession.parse(account.credential)?.userAgent)
        runBlocking { app.subscriptions.connect(SubscriptionProvider.KIMI_API, "fixture-kimi-api") }
        val requests = app.subscriptionRequests.get()
        compose.onNodeWithTag("navigate-back").performClick()
        compose.openProvider("GROK")
        compose.onNodeWithTag("subscription-import-GROK").performScrollToVisible(compose).performClick()
        compose.onNodeWithTag("subscription-import-json").performScrollToVisible(compose).performTextInput(
            """{"availability":"available","weeklyIncludedUsage":{"usedCents":37,"budgetCents":100}}""")
        compose.onAllNodesWithText("导入官网数据").onLast().performClick()
        compose.waitUntil(5000) { app.subscriptions.state.value.getValue(SubscriptionProvider.GROK).imported }
        assertEquals(requests, app.subscriptionRequests.get())
        compose.onNode(hasText("刷新") and hasAnyAncestor(hasTestTag("subscription-GROK"))).assertDoesNotExist()
        compose.onNodeWithText("从官网手动导入，数据可能早于导入时间，不会自动刷新。请核对控制台后重新导入。")
            .performScrollToVisible(compose).assertIsDisplayed()
        compose.onNodeWithText("打开此页时检查更新，也可手动刷新。桌面小组件目前仅显示 Codex。").assertDoesNotExist()
        alignCardForScreenshot(SubscriptionProvider.GROK)
        saveDeviceScreenshot(app, "providers-grok-manual-import", requireNotNull(instrumentation.uiAutomation.takeScreenshot()))
        val encoded = java.io.File(app.noBackupFilesDir, "subscriptions.enc").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(encoded.contains("fixture-session"))
        assertFalse(encoded.contains("fixture-chunk"))
        compose.onNodeWithTag("tab-quota").performClick()
        compose.onNodeWithTag("overview-WORKBUDDY").assertExists()
        compose.onNodeWithText("60 / 100 积分").performScrollToVisible(compose).assertIsDisplayed()
        compose.onNodeWithText("CNY 49.58894").performScrollToVisible(compose).assertIsDisplayed()
        compose.onNodeWithTag("overview-GROK").assertExists()
        compose.onNodeWithText("从官网手动导入，数据可能早于导入时间，不会自动刷新。请核对控制台后重新导入。")
            .performScrollToVisible(compose).assertIsDisplayed()
        assertEquals(requests, app.subscriptionRequests.get())
    }

    private fun alignCardForScreenshot(provider: SubscriptionProvider) {
        val card = compose.onNodeWithTag("subscription-${provider.name}").fetchSemanticsNode()
        val viewport = generateSequence(card.parent) { it.parent }
            .first { it.config.contains(SemanticsActions.ScrollBy) }
        val delta = card.positionInRoot.y - viewport.positionInRoot.y - 16f * compose.density.density
        compose.onNode(SemanticsMatcher("subscription scroll ancestor") { it.id == viewport.id })
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, delta) }
        compose.waitForIdle()
        // Let the finite material transition and platform compositor finish before full-screen capture.
        Thread.sleep(700)
        compose.waitForIdle()
    }

    @Test fun cancelFirstConnectionDoesNotSaveCredentials() {
        app.subscriptionDelayMillis = 60_000
        compose.onNodeWithTag("tab-subscriptions").performClick()
        compose.openProvider("DEEPSEEK")
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
                compose.openSettingsPage("language")
                compose.onNodeWithTag("language-$language").performScrollToVisible(compose).performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithText(heading).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("tab-subscriptions").performClick()
                if (compose.onAllNodesWithText(notice).fetchSemanticsNodes().isEmpty())
                    compose.onNodeWithTag("subscription-examples").performScrollToVisible(compose).performClick()
                compose.onAllNodesWithText(notice).onFirst().performScrollToVisible(compose).assertIsDisplayed()
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
