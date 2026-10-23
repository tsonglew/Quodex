package com.tsonglew.quodex

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.tsonglew.quodex.data.UsageParser
import com.tsonglew.quodex.ui.QuotaStatusNotice
import com.tsonglew.quodex.ui.QuotaTheme
import com.tsonglew.quodex.ui.QuotaWindowCard
import org.junit.Rule
import org.junit.Test

class QuotaStatusUiTest {
    @get:Rule val compose = createComposeRule()

    private fun render(body: String) {
        val snapshot = UsageParser.parse(body, "demo", 1000)
        compose.setContent {
            QuotaTheme("light") {
                Surface {
                    Column(Modifier.padding(24.dp)) {
                        QuotaStatusNotice(snapshot)
                        snapshot.windows.forEach { QuotaWindowCard(it, remaining = true, now = 1000) }
                    }
                }
            }
        }
    }

    @Test fun weeklyOnlyQuotaExplainsMissingShortCycle() {
        render("""{"plan_type":"pro","rate_limit":{"primary_window":null,"secondary_window":{"used_percent":38,"limit_window_seconds":604800}}}""")
        compose.onNodeWithText("未提供短周期额度").assertIsDisplayed()
        compose.onNodeWithText("每周额度").assertIsDisplayed()
        compose.onNodeWithText("62").assertIsDisplayed()
        compose.onNodeWithText("5 小时额度").assertDoesNotExist()
        capture("quota-weekly-only")
    }

    @Test fun windowlessAccountShowsAvailableWithoutFakePercentage() {
        render("""{"plan_type":"pro","rate_limit":{"allowed":true,"limit_reached":false}}""")
        compose.onNodeWithText("当前可用").assertIsDisplayed()
        compose.onNodeWithText("账号未提供周期额度，无需显示百分比。实际使用仍以账号权益为准。").assertIsDisplayed()
        compose.onNodeWithText("%", substring = true).assertDoesNotExist()
        capture("quota-no-windows")
    }

    @Test fun unknownUsageHasReadableExplanationWithoutEmptyProgressBar() {
        render("""{"rate_limit":{"primary_window":{"limit_window_seconds":18000}}}""")
        compose.onNodeWithText("暂不可用").assertIsDisplayed()
        compose.onNodeWithText("服务暂未提供有效的用量数据，请稍后刷新。").assertIsDisplayed()
        compose.onNodeWithContentDescription("5 小时额度，剩余", substring = true).assertDoesNotExist()
        capture("quota-unknown-usage")
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        saveDeviceScreenshot(InstrumentationRegistry.getInstrumentation().targetContext, name,
            compose.onRoot().captureToImage().asAndroidBitmap())
    }
}
