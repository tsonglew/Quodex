package com.tsonglew.quodex

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tsonglew.quodex.data.FailureKind
import com.tsonglew.quodex.data.Session
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TokenUsageDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.quotaApp as TestQuotaApplication

    @Before fun startDisconnected() {
        app.tokenUsageStatusCode = 200
        runBlocking { app.repository.logout(); app.settings.theme("light") }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("connect-button").fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun clearSyntheticAccount() {
        app.tokenUsageStatusCode = 200
        runBlocking { app.repository.logout() }
    }

    @Test fun serviceDatesZeroAndMissingDaysRemainDistinctInBothPeriods() {
        connectWithTokens()
        compose.onNodeWithTag("token-trend-7").performScrollToVisible(compose).assertIsSelected()
        compose.onNodeWithTag("token-trend-total").assertTextEquals("57,000")
        compose.onNodeWithText("已返回日用量合计 · 3/7 天有数据").assertExists()
        compose.onNodeWithTag("token-trend-chart").performScrollToVisible(compose).assertContentDescriptionContains("2026-10-16 至 2026-10-22", substring = true)
        screenshot("token-trend-seven-days")
        compose.onNodeWithTag("token-trend-details").performScrollToVisible(compose).performClick()
        compose.onNodeWithText("0 tokens").assertExists()
        compose.onAllNodesWithText("未提供数据").onFirst().assertExists()
        saveDeviceScreenshot(compose.activity, "token-trend-day-details", compose.onNode(isDialog()).captureToImage().asAndroidBitmap())
        compose.onNodeWithTag("token-trend-details-close").performClick()
        compose.onNodeWithTag("token-trend-30").performScrollToVisible(compose).performClick().assertIsSelected()
        compose.onNodeWithTag("token-trend-total").assertTextEquals("57,000")
        compose.onNodeWithText("已返回日用量合计 · 3/30 天有数据").assertExists()
        compose.onNodeWithTag("token-trend-details").performScrollToVisible(compose).performClick()
        compose.onNodeWithTag("token-trend-day-list").performScrollToIndex(29)
        compose.onNode(hasText("2026-09-23") and hasAnyAncestor(hasTestTag("token-trend-day-list"))).assertIsDisplayed()
        compose.onNodeWithTag("token-trend-details-close").performClick()
    }

    @Test fun failedRefreshKeepsTrendAndQuotaThenLogoutClearsAccountData() {
        connectWithTokens()
        val quotaRequests = app.usageRequests.get()
        val quotaSnapshot = app.repository.state.value.snapshot
        val tokenRequests = app.tokenUsageRequests.get()
        app.tokenUsageStatusCode = 403
        compose.onNodeWithTag("token-trend-refresh").performScrollToVisible(compose).performClick()
        compose.waitUntil(10_000) {
            app.tokenUsageRequests.get() > tokenRequests && !app.repository.state.value.tokenLoading
        }
        assertEquals(FailureKind.FORBIDDEN, app.repository.state.value.tokenFailure)
        assertNull(app.repository.state.value.failure)
        assertEquals(quotaSnapshot, app.repository.state.value.snapshot)
        assertEquals(quotaRequests, app.usageRequests.get())
        compose.onNodeWithTag("token-trend-total").assertTextEquals("57,000")
        compose.onNodeWithText("仍显示上次获取的数据。", substring = true).performScrollToVisible(compose).assertIsDisplayed()
        screenshot("token-trend-cached-error")
        compose.onNodeWithTag("tab-settings").performClick()
        compose.onNodeWithTag("logout-button").performScrollToVisible(compose).performClick()
        compose.onNodeWithTag("confirm-logout").performClick()
        compose.onNodeWithTag("tab-quota").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("connect-button").fetchSemanticsNodes().isNotEmpty() }
        assertNull(app.repository.state.value.snapshot)
        runBlocking { app.repository.connect(Session("test-access", "test-refresh", "another-test-account", Long.MAX_VALUE)) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("token-usage-card").fetchSemanticsNodes().isNotEmpty() }
        assertNull(app.repository.state.value.snapshot?.tokenUsage)
        compose.onNodeWithTag("token-trend-total").assertDoesNotExist()
    }

    @Test fun demoTrendSwitchesPeriodsInDarkThemeWithoutRequestingAccountUsage() {
        val requests = app.tokenUsageRequests.get()
        compose.onNodeWithTag("demo-button").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("token-usage-card").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("tab-settings").performClick()
        compose.onNodeWithTag("theme-dark").performClick()
        compose.onNodeWithTag("tab-quota").performClick()
        compose.onNodeWithTag("token-trend-30").performScrollToVisible(compose).performClick().assertIsSelected()
        compose.onNodeWithText("已返回日用量合计 · 30/30 天有数据").assertExists()
        compose.onNodeWithTag("token-trend-chart").performScrollToVisible(compose).assertIsDisplayed()
        screenshot("token-trend-demo-dark")
        compose.onNodeWithTag("token-trend-refresh").assertDoesNotExist()
        assertEquals(requests, app.tokenUsageRequests.get())
        assertNull(app.repository.state.value.snapshot?.tokenUsage)
    }

    private fun connectWithTokens() {
        runBlocking {
            app.repository.connect(Session("test-access", "test-refresh", "test-account", Long.MAX_VALUE))
            app.repository.refreshTokenUsage(force = true)
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("token-trend-chart").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        saveDeviceScreenshot(compose.activity, name, compose.onRoot().captureToImage().asAndroidBitmap())
    }
}
