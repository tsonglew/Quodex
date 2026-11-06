package com.tsonglew.quodex

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import com.tsonglew.quodex.subscriptions.SubscriptionProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class NavigationStructureTest {
    @get:Rule val compose = createQuodexAndroidComposeRule<MainActivity>()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.quotaApp as TestQuotaApplication
    private lateinit var previousTheme: String

    @Before fun prepare() = runBlocking {
        previousTheme = app.settings.read().theme
        app.settings.theme("light")
        app.repository.logout()
        SubscriptionProvider.entries.forEach { app.subscriptions.disconnect(it) }
    }

    @After fun restore() = runBlocking {
        app.repository.logout()
        app.settings.theme(previousTheme)
        SubscriptionProvider.entries.forEach { app.subscriptions.disconnect(it) }
    }

    @Test fun overviewIncludesConnectedServiceWithoutRequiringCodex() {
        runBlocking { app.subscriptions.connect(SubscriptionProvider.DEEPSEEK, "fixture-navigation") }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("overview-DEEPSEEK").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("CNY 110").assertIsDisplayed()
        compose.onNodeWithTag("overview-codex").assertDoesNotExist()
        compose.onNodeWithTag("overview-CURSOR").assertDoesNotExist()
        compose.onNodeWithTag("subscription-connect-DEEPSEEK").assertDoesNotExist()
        compose.onNodeWithTag("overview-DEEPSEEK-open").performClick()
        compose.onNodeWithTag("subscription-DEEPSEEK").assertExists()
        compose.onNodeWithTag("navigate-back").performClick()
        compose.onNodeWithTag("overview-DEEPSEEK").assertIsDisplayed()
    }

    @Test fun threeDestinationsSeparateOverviewAccountsAndCompactSettings() {
        compose.onNodeWithTag("demo-button").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("overview-codex").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("tab-widgets").assertDoesNotExist()
        compose.onNodeWithTag("overview-DEEPSEEK").assertExists()
        compose.onNodeWithTag("overview-CURSOR").assertExists()
        screenshot("navigation-overview-light")
        compose.onNodeWithTag("overview-CURSOR-open").performScrollToVisible(compose).performClick()
        compose.onNodeWithTag("subscription-CURSOR").assertExists()
        compose.onNodeWithTag("subscription-connect-CURSOR").assertDoesNotExist()
        compose.onNodeWithTag("navigate-back").performClick()
        compose.onNodeWithTag("tab-subscriptions").performClick()
        compose.onNodeWithTag("account-codex").assertIsDisplayed()
        SubscriptionProvider.entries.forEach { compose.onNodeWithTag("account-${it.name}").assertExists() }
        compose.onNodeWithTag("subscription-CURSOR").assertDoesNotExist()
        screenshot("navigation-accounts-light")
        compose.onNodeWithTag("tab-settings").performClick()
        compose.onNodeWithTag("logout-button").assertDoesNotExist()
        compose.onNodeWithTag("desktop-icon-settings").assertDoesNotExist()
        compose.onNodeWithTag("theme-dark").assertDoesNotExist()
        compose.onNodeWithTag("settings-support").assertIsDisplayed()
        screenshot("navigation-settings-light")
        compose.openSettingsPage("appearance")
        compose.onNodeWithTag("theme-dark").performClick()
        compose.onNodeWithTag("navigate-back").performClick()
        screenshot("navigation-settings-dark")
    }

    @Test fun desktopSettingsShareOneDestinationAndBackRestoresSettings() {
        compose.openSettingsPage("desktop")
        compose.onNodeWithTag("add-widget-button").assertExists()
        compose.onNodeWithTag("add-desktop-icon").performScrollToVisible(compose).assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("add-desktop-icon").assertExists()
        compose.onNodeWithTag("navigate-back").performClick()
        compose.onNodeWithTag("settings-desktop").assertIsDisplayed()
        compose.onNodeWithTag("add-desktop-icon").assertDoesNotExist()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        saveDeviceScreenshot(app, name, compose.onRoot().captureToImage().asAndroidBitmap())
    }
}
