package com.tsonglew.quodex

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.tsonglew.quodex.i18n.UiText
import com.tsonglew.quodex.ui.AppUpdatesCard
import com.tsonglew.quodex.ui.QuotaTheme
import com.tsonglew.quodex.update.SavedUpdates
import com.tsonglew.quodex.update.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class UpdateCooldownUiTest {
    @get:Rule val compose = createQuodexComposeRule()

    @Test fun deadlineExplainsDisabledButtonAndExpiryAllowsRetry() {
        val time = mutableLongStateOf(1_793_635_200L)
        val deadline = time.longValue + 120
        var checks = 0
        compose.setContent {
            QuotaTheme("light") {
                Surface {
                    Column(Modifier.padding(20.dp)) {
                        AppUpdatesCard(UpdateState(SavedUpdates(enabled = false, retryAt = deadline),
                            initialized = true, error = UiText(R.string.network_retry_after)),
                            onCheck = { checks++ }, onEnabled = {}, onPermissionChanged = {}, now = time.longValue)
                    }
                }
            }
        }
        compose.onNodeWithTag("update-retry-deadline").assertIsDisplayed()
            .assertTextContains("更新服务要求等待，请在", substring = true)
        compose.onNodeWithTag("check-app-update").assertIsNotEnabled()
        saveDeviceScreenshot(InstrumentationRegistry.getInstrumentation().targetContext, "update-cooldown-alpha33",
            compose.onRoot().captureToImage().asAndroidBitmap())
        compose.runOnIdle { time.longValue = deadline }
        compose.onNodeWithTag("update-retry-deadline").assertDoesNotExist()
        compose.onNodeWithTag("update-retry-ready").assertIsDisplayed()
        compose.onNodeWithTag("check-app-update").assertIsEnabled().performClick()
        assertEquals(1, checks)
    }

    @Test fun fallbackCooldownStillAllowsCheckingPrimary() {
        var checks = 0
        compose.setContent {
            QuotaTheme("light") {
                AppUpdatesCard(UpdateState(SavedUpdates(enabled = false, fallbackRetryAt = 1_793_635_320L),
                    initialized = true, error = UiText(R.string.update_fallback_wait)),
                    onCheck = { checks++ }, onEnabled = {}, onPermissionChanged = {}, now = 1_793_635_200L)
            }
        }
        compose.onNodeWithTag("update-fallback-deadline").assertIsDisplayed()
            .assertTextContains("可重试主来源", substring = true)
        compose.onNodeWithTag("check-app-update").assertIsEnabled().performClick()
        assertEquals(1, checks)
    }
}
