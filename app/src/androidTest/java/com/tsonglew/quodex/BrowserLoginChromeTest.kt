package com.tsonglew.quodex

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.tsonglew.quodex.i18n.AndroidTextResources
import com.tsonglew.quodex.subscriptions.browser.*
import com.tsonglew.quodex.ui.QuotaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class BrowserLoginChromeTest {
    @get:Rule val compose = createQuodexAndroidComposeRule<AutofillTestHostActivity>()

    @Test fun googleFailuresKeepRecoveryAndLargeTextKeepsOperationalStatus() {
        var status by mutableStateOf(BrowserReadStatus.LOGIN)
        var compact by mutableStateOf(false)
        var choices = 0
        var recoveries = 0
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, if (compact) 2f else 1f)) {
                QuotaTheme("light") {
                    BrowserLoginFooter(BrowserPageState(host = "accounts.google.com", loading = false, status = status),
                        { choices++ }, compact) { recoveries++ }
                }
            }
        }
        val texts = AndroidTextResources(compose.activity)
        compose.onNodeWithText(texts(R.string.subscription_browser_google_choose)).performClick()
        compose.runOnIdle { assertEquals(1, choices) }
        for (small in listOf(false, true)) {
            compose.runOnIdle { compact = small }
            for ((state, message) in listOf(
                BrowserReadStatus.RETRY to R.string.subscription_browser_retry,
                BrowserReadStatus.BLOCKED to R.string.subscription_browser_blocked,
                BrowserReadStatus.LIMITED to R.string.subscription_browser_limited,
                BrowserReadStatus.READING to R.string.subscription_browser_reading,
            )) {
                compose.runOnIdle { status = state }
                compose.onNodeWithText(texts(message)).assertIsDisplayed()
                compose.onNodeWithText(texts(R.string.subscription_browser_google_choose)).assertDoesNotExist()
                if (state == BrowserReadStatus.RETRY || state == BrowserReadStatus.BLOCKED) {
                    compose.onNodeWithText(texts(R.string.subscription_browser_read)).performClick()
                }
            }
        }
        compose.runOnIdle { assertEquals(4, recoveries); status = BrowserReadStatus.LOGIN }
        compose.onNodeWithText(texts(R.string.subscription_browser_google_choose)).assertIsDisplayed()
    }
}
