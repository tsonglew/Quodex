package com.tsonglew.quodex

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.tsonglew.quodex.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class ApiCreditsUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.quotaApp as TestQuotaApplication

    @Test fun benefitAndUnavailableBalanceRemainDistinctInBothThemes() {
        val previous = runBlocking { app.settings.read() }
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                if (android.os.Build.VERSION.SDK_INT >= 33) app.getSystemService(android.app.LocaleManager::class.java)
                    .applicationLocales = android.os.LocaleList.forLanguageTags("zh-Hans")
                else com.tsonglew.quodex.i18n.AppLanguages.select("zh-Hans")
                app.languageChanged()
            }
            runBlocking { app.repository.demo() }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                for (theme in listOf("light", "dark")) {
                    runBlocking { app.settings.theme(theme) }
                    scenario.recreate()
                    compose.onNodeWithTag("tab-quota").performClick()
                    if (compose.onAllNodesWithTag("api-credits-period").fetchSemanticsNodes().isEmpty())
                        compose.onNodeWithTag("api-credits-toggle").performScrollToVisible(compose).performClick()
                    compose.onNodeWithText("每月 US$100").performScrollToVisible(compose).assertIsDisplayed()
                    compose.onNodeWithText("本周期可领取：US$75").assertExists()
                    compose.onNodeWithText("剩余余额：接口未提供。福利金额未扣除 API 消费，请在 API Platform 账单中核对余额。")
                        .performScrollToVisible(compose).assertIsDisplayed()
                    compose.onNodeWithText("仅查询。Quodex 不会绑定组织、领取额度、取消福利或修改账单。")
                        .performScrollToVisible(compose).assertIsDisplayed()
                    compose.onNodeWithTag("api-credits-refresh").assertDoesNotExist()
                    compose.waitForIdle()
                    saveDeviceScreenshot(app, "api-credits-$theme",
                        requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()))
                }
            }
        } finally { runBlocking { app.settings.theme(previous.theme); app.repository.logout() } }
    }
}
