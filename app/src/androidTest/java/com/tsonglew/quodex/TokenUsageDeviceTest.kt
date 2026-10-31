package com.tsonglew.quodex

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tsonglew.quodex.data.FailureKind
import com.tsonglew.quodex.data.Session
import com.tsonglew.quodex.data.TokenChartStyle
import com.tsonglew.quodex.data.SettingsStore
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
    private var previousStyle: TokenChartStyle? = null

    @Before fun startDisconnected() {
        app.tokenUsageStatusCode = 200
        runBlocking {
            previousStyle = app.settings.read().tokenChartStyle
            app.settings.tokenChartStyle(null)
            app.repository.logout(); app.settings.theme("light")
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("connect-button").fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun clearSyntheticAccount() {
        app.tokenUsageStatusCode = 200
        runBlocking { app.repository.logout(); app.settings.tokenChartStyle(previousStyle) }
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
        compose.onNodeWithTag("token-trend-chart").performScrollToVisible(compose)
        val heatmap = com.tsonglew.quodex.data.TokenHeatmap(com.tsonglew.quodex.data.tokenTrend(app.repository.state.value.snapshot!!.tokenUsage!!.dailyBuckets, 30)!!)
        fun selectDay(index: Int) {
            val slot = heatmap.leadingDays + index
            compose.onNodeWithTag("token-heatmap", useUnmergedTree = true).performTouchInput {
                val cell = com.tsonglew.quodex.ui.TokenHeatmapRenderer.geometry(heatmap, width.toFloat(), height.toFloat()).bounds(slot / 7, slot % 7)
                click(androidx.compose.ui.geometry.Offset(cell.centerX(), cell.centerY()))
            }
        }
        selectDay(28)
        compose.onNodeWithTag("heatmap-selected-day").assertTextEquals("2026-10-21 · 0 tokens")
        selectDay(0)
        compose.onNodeWithTag("heatmap-selected-day").assertTextEquals("2026-09-23 · 未提供数据")
        screenshot("token-heatmap-missing-and-zero")
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
        compose.onNodeWithTag("heatmap-selected-day").performScrollToVisible(compose)
        screenshot("token-trend-demo-dark")
        compose.onNodeWithTag("token-trend-refresh").assertDoesNotExist()
        assertEquals(requests, app.tokenUsageRequests.get())
        assertNull(app.repository.state.value.snapshot?.tokenUsage)
    }

    @Test fun calendarUsesCardWidthAndAdaptsHeightToTheSelectedPeriod() {
        compose.onNodeWithTag("demo-button").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("token-usage-card").fetchSemanticsNodes().isNotEmpty() }
        chooseChart("heatmap")
        var sevenDayHeight = 0
        for (days in listOf(7, 30)) {
            compose.onNodeWithTag("token-trend-$days").performScrollToVisible(compose).performClick()
            compose.onNodeWithTag("token-trend-chart").performScrollToVisible(compose)
            val bitmap = compose.onNodeWithTag("token-heatmap", useUnmergedTree = true).captureToImage().asAndroidBitmap()
            val greens = com.tsonglew.quodex.ui.TokenHeatmapRenderer.colors(false).drop(1).toSet()
            val occupiedColumns = (0 until bitmap.width).filter { x ->
                (0 until bitmap.height).any { y -> bitmap.getPixel(x, y) in greens }
            }
            assertTrue("Every period should fill the chart width", occupiedColumns.isNotEmpty() &&
                occupiedColumns.last() - occupiedColumns.first() > bitmap.width * .80f)
            if (days == 7) sevenDayHeight = bitmap.height else assertTrue(bitmap.height > sevenDayHeight * 1.5)
            compose.onNodeWithTag("heatmap-selected-day").performScrollToVisible(compose)
            screenshot("token-calendar-$days-demo-light")
        }
        chooseChart("bars")
        compose.onNodeWithTag("token-trend-chart").performScrollToVisible(compose)
        screenshot("token-calendar-30-bars-demo-light")
    }

    @Test fun heatmapWidgetIntentReturnsFromSettingsAndShowsThirtyDays() {
        connectWithTokens()
        chooseChart("bars")
        compose.onNodeWithTag("tab-settings").performClick()
        compose.activity.startActivity(com.tsonglew.quodex.widget.widgetLaunchIntent(compose.activity)
            .putExtra(com.tsonglew.quodex.widget.EXTRA_TOKEN_HEATMAP_LAUNCH, true))
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("token-trend-30") and isSelected()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("token-trend-chart").assertIsDisplayed()
        compose.onNodeWithTag("token-chart-heatmap").assertIsSelected()
        assertEquals(TokenChartStyle.BARS, runBlocking { app.settings.read().tokenChartStyle })
        chooseChart("bars")
        compose.onNodeWithTag("token-trend-30").assertIsSelected()
        // A later tab change must not be overridden by the consumed launch request.
        compose.onNodeWithTag("tab-settings").performClick()
        compose.onNodeWithTag("logout-button").assertExists()
    }

    @Test fun bothPeriodsSwitchChartTypesWithoutRequestsAndChoiceSurvivesRecreation() {
        connectWithTokens()
        val quotaRequests = app.usageRequests.get()
        val tokenRequests = app.tokenUsageRequests.get()
        for (days in listOf(7, 30)) {
            compose.onNodeWithTag("token-trend-$days").performScrollToVisible(compose).performClick()
            for (style in listOf("heatmap", "bars")) {
                chooseChart(style)
                compose.onNodeWithTag("token-trend-$days").assertIsSelected()
                compose.onNodeWithTag("token-trend-total").assertTextEquals("57,000")
                compose.onNodeWithTag("token-trend-chart").performScrollToVisible(compose).assertIsDisplayed()
                if (style == "heatmap") {
                    compose.onNodeWithTag("token-heatmap", useUnmergedTree = true).assertExists()
                    compose.onNodeWithTag("token-bars").assertDoesNotExist()
                } else {
                    compose.onNodeWithTag("token-bars").assertExists()
                    compose.onNodeWithTag("token-heatmap", useUnmergedTree = true).assertDoesNotExist()
                }
                screenshot("token-$days-$style-switch")
            }
        }
        assertEquals(quotaRequests, app.usageRequests.get())
        assertEquals(tokenRequests, app.tokenUsageRequests.get())
        assertEquals(TokenChartStyle.BARS, runBlocking { SettingsStore(compose.activity).read().tokenChartStyle })
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("token-chart-bars") and isSelected()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("token-trend-30").assertIsSelected()
        compose.onNodeWithTag("token-bars").assertExists()
    }

    private fun chooseChart(style: String) {
        compose.onNodeWithTag("token-chart-$style").performScrollToVisible(compose).performClick()
        compose.waitUntil(10_000) {
            runBlocking { app.settings.read().tokenChartStyle?.name?.lowercase(java.util.Locale.ROOT) == style } &&
                compose.onAllNodes(hasTestTag("token-chart-$style") and isSelected()).fetchSemanticsNodes().isNotEmpty()
        }
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
