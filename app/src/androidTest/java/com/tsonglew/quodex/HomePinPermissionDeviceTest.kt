package com.tsonglew.quodex

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tsonglew.quodex.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomePinPermissionDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TestQuotaApplication

    @Before fun prepare() = runBlocking { app.repository.logout() }

    @Test fun widgetAddsAndNewQuotaIconsOfferSettingsWhileExistingIconsUpdateDirectly() {
        val settings = SettingsRecorder(compose.activity)
        lateinit var model: MainViewModel
        compose.runOnUiThread { model = ViewModelProvider(compose.activity)[MainViewModel::class.java] }
        compose.setContent {
            CompositionLocalProvider(LocalHomePinGuideRequired provides true, LocalContext provides settings) {
                QuodexApp(model)
            }
        }
        compose.onNodeWithTag("tab-widgets").performClick()
        for (tag in listOf("add-widget-button", "add-slim-widget-button")) {
            compose.onNodeWithTag(tag).performScrollToVisible(compose).performClick()
            checkSettingsAndDismiss(settings)
        }
        compose.onNodeWithTag("tab-settings").performClick()
        compose.waitUntil(5_000) { model.desktopIconAvailability.value.supported }
        val alreadyPinned = model.desktopIconAvailability.value.pinned
        compose.onNodeWithTag("add-desktop-icon").performScrollToVisible(compose).performClick()
        if (alreadyPinned) compose.onNodeWithTag("home-pin-permission-dialog").assertDoesNotExist()
        else checkSettingsAndDismiss(settings)
        assertEquals(if (alreadyPinned) 2 else 3, settings.opened.size)
    }

    @Test fun settingsReturnAndSavedStateKeepTheChosenWidgetUntilExplicitContinuation() {
        val requests = mutableListOf<HomePinTarget>()
        val settings = SettingsRecorder(compose.activity)
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalHomePinGuideRequired provides true, LocalContext provides settings) {
                MaterialTheme {
                    val requestPin = rememberHomePinRequest { requests += it }
                    TextButton(onClick = { requestPin(HomePinTarget.COMPACT_WIDGET) }) { Text("Request compact widget") }
                }
            }
        }
        compose.onNodeWithText("Request compact widget").performClick()
        compose.onNodeWithTag("home-pin-open-settings").performScrollTo().performClick()
        compose.runOnIdle { assertTrue("Opening settings does not grant permission or add anything", requests.isEmpty()) }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("home-pin-permission-dialog").assertIsDisplayed()
        compose.onNodeWithTag("home-pin-continue").performClick()
        compose.runOnIdle { assertEquals(listOf(HomePinTarget.COMPACT_WIDGET), requests) }
        compose.onNodeWithTag("home-pin-permission-dialog").assertDoesNotExist()

        // A previous settings visit must not permanently suppress guidance: permission may
        // still be denied, or the user may revoke it later.
        compose.onNodeWithText("Request compact widget").performClick()
        compose.onNodeWithTag("home-pin-permission-dialog").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(1, requests.size) }
    }

    @Test fun otherDevicesContinueDirectlyForEachPinTarget() {
        val requests = mutableListOf<HomePinTarget>()
        compose.setContent {
            CompositionLocalProvider(LocalHomePinGuideRequired provides false) {
                MaterialTheme {
                    val requestPin = rememberHomePinRequest { requests += it }
                    Column {
                        HomePinTarget.entries.forEach { target ->
                            TextButton(onClick = { requestPin(target) }) { Text(target.name) }
                        }
                    }
                }
            }
        }
        HomePinTarget.entries.forEach { compose.onNodeWithText(it.name).performClick() }
        compose.runOnIdle { assertEquals(HomePinTarget.entries.toList(), requests) }
        compose.onNodeWithTag("home-pin-permission-dialog").assertDoesNotExist()
    }

    private fun checkSettingsAndDismiss(settings: SettingsRecorder) {
        compose.onNodeWithTag("home-pin-permission-dialog").assertIsDisplayed()
        compose.onNodeWithTag("home-pin-open-settings").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, settings.opened.last().action)
            assertEquals("package:${app.packageName}", settings.opened.last().dataString)
        }
        compose.onNodeWithTag("home-pin-permission-dialog").assertIsDisplayed()
        saveDeviceScreenshot(app, "home-pin-permission-guide", checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()))
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("home-pin-permission-dialog").assertDoesNotExist()
    }

    private class SettingsRecorder(context: Context) : ContextWrapper(context) {
        val opened = mutableListOf<Intent>()
        override fun startActivity(intent: Intent) { opened += Intent(intent) }
    }
}
