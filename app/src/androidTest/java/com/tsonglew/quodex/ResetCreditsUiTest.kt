package com.tsonglew.quodex

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.tsonglew.quodex.data.*
import com.tsonglew.quodex.i18n.AppLanguages
import com.tsonglew.quodex.ui.QuotaTheme
import com.tsonglew.quodex.ui.ResetCreditsCard
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ResetCreditsUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val state = mutableStateOf(AppState())
    private val loads = mutableListOf<Boolean>()
    private var host: MainActivity? = null
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as TestQuotaApplication

    @Test fun countOnlyUnknownZeroAndFailedDetailsKeepTheirMeaning() {
        withCard(demoSnapshot(NOW).copy(resetCredits = null, resetCreditDetails = null), demo = false) {
            compose.onNodeWithTag("reset-credits-count").assertTextEquals("可用数量暂不可用")
            assertTrue(loads.isEmpty())
            compose.onNodeWithTag("reset-credits-toggle").performClick()
            compose.waitForIdle()
            assertEquals(listOf(false), loads)
            compose.onNodeWithText("尚未获取卡片明细。").assertExists()
            compose.runOnIdle { state.value = state.value.copy(snapshot = state.value.snapshot!!.copy(resetCredits = 0)) }
            compose.onNodeWithTag("reset-credits-count").assertTextEquals("暂无可用重置卡")
            compose.runOnIdle { state.value = state.value.copy(snapshot = state.value.snapshot!!.copy(resetCredits = 3),
                resetCreditsFailure = FailureKind.LIMITED, resetCreditsRetryAt = NOW + 120) }
            compose.onNodeWithTag("reset-credits-count").assertTextEquals("3 张可用")
            compose.onNodeWithText("重置卡明细暂时无法获取，请稍后重试。").assertExists()
            compose.onNodeWithTag("reset-credits-refresh").performScrollToVisible(compose).assertIsNotEnabled()
            compose.runOnIdle { state.value = state.value.copy(resetCreditsRetryAt = 0) }
            compose.onNodeWithTag("reset-credits-refresh").performClick()
            assertEquals(listOf(false, true), loads)
        }
    }

    @Test fun cachedPartialAndExpiredCardsDoNotChangeServerCount() {
        val details = ResetCreditsSnapshot(NOW - 60, 4, listOf(
            ResetCredit("sample", "codex", "available", NOW - 86400, NOW - 1, "示例赠卡", "示例说明")))
        withCard(demoSnapshot(NOW).copy(resetCredits = 3, resetCreditDetails = details), demo = false) {
            compose.onNodeWithTag("reset-credits-toggle").performClick()
            compose.onNodeWithTag("reset-credits-count").assertTextEquals("3 张可用")
            compose.onNodeWithText("以下为此前获取的明细，卡片状态可能已变化。").assertExists()
            compose.onNodeWithText("已到到期时间 · 请刷新确认").performScrollToVisible(compose).assertIsDisplayed()
            compose.onNodeWithText("已返回 1 张明细，服务端报告 4 张可用。").performScrollToVisible(compose).assertIsDisplayed()
            compose.onNodeWithText("使用重置卡请前往 Codex；Quodex 仅展示卡片信息。").performScrollToVisible(compose).assertIsDisplayed()
        }
    }

    @Test fun threeLanguagesAndBothThemesRenderNativeSampleCardsWithoutNetwork() {
        val before = app.resetCreditRequests.get()
        try {
            for ((language, title) in listOf("zh-Hans" to "Codex 重置卡", "en" to "Codex reset cards", "ja" to "Codex リセットカード")) {
                selectLanguage(language)
                for (theme in listOf("light", "dark")) withCard(demoSnapshot(NOW), theme = theme) {
                    compose.onNodeWithText(title).assertIsDisplayed()
                    capture("reset-cards-$language-$theme")
                    compose.onNodeWithTag("reset-credits-toggle").performClick()
                    assertTrue(loads.isEmpty())
                    compose.onNodeWithTag("reset-credits-refresh").assertDoesNotExist()
                    capture("reset-details-$language-$theme")
                    assertTextFits()
                }
            }
            assertEquals(before, app.resetCreditRequests.get())
        } finally { selectLanguage("zh-Hans") }
    }

    @Test fun dashboardExpansionReadsDetailsOnceAndKeepsTheQuota() {
        runBlocking { app.repository.logout() }
        val before = app.resetCreditRequests.get()
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                runBlocking { app.repository.connect(Session("fixture-reset-access", "fixture-reset-refresh", "fixture-reset-account", Long.MAX_VALUE)) }
                compose.onNodeWithTag("reset-credits-toggle").performScrollToVisible(compose).performClick()
                compose.waitUntil(10_000) { app.repository.state.value.snapshot?.resetCreditDetails != null }
                compose.onNodeWithTag("reset-credits-count").assertTextEquals("2 张可用")
                assertEquals(before + 1, app.resetCreditRequests.get())
                assertEquals(38.0, app.repository.state.value.snapshot!!.windows.single().usedPercent!!, 0.0)
                compose.onNodeWithTag("reset-credits-toggle").performClick().performClick()
                compose.waitForIdle()
                assertEquals(before + 1, app.resetCreditRequests.get())
            }
        } finally { runBlocking { app.repository.logout() } }
    }

    @Test fun narrowDoubleFontKeepsDetailsAndActionsReachable() {
        withWidgetFontScale(2f) {
            withCard(demoSnapshot(NOW), width = 320) {
                compose.onNodeWithTag("reset-credits-toggle").performScrollToVisible(compose).performClick()
                compose.onNodeWithText("使用重置卡请前往 Codex；Quodex 仅展示卡片信息。").performScrollToVisible(compose).assertIsDisplayed()
                assertTextFits()
            }
        }
    }

    private fun withCard(snapshot: UsageSnapshot, demo: Boolean = true, theme: String = "light", width: Int = 360, block: () -> Unit) {
        runBlocking { app.repository.demo() }
        state.value = AppState(initialized = true, connected = !demo, snapshot = snapshot, demo = demo)
        loads.clear()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                host = activity
                activity.setContent {
                    QuotaTheme(theme) {
                        Surface(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                            Column(Modifier.width(width.dp).fillMaxHeight().testTag("reset-viewport")
                                .verticalScroll(rememberScrollState()).padding(16.dp)) {
                                ResetCreditsCard(state.value, NOW) { loads += it }
                            }
                        }
                    }
                }
            }
            try { compose.onNodeWithTag("reset-credits-card").assertExists(); block() } finally { host = null }
        }
    }
    private fun selectLanguage(tag: String) {
        instrumentation.runOnMainSync {
            if (android.os.Build.VERSION.SDK_INT >= 33) app.getSystemService(android.app.LocaleManager::class.java)
                .applicationLocales = android.os.LocaleList.forLanguageTags(tag)
            else AppLanguages.select(tag)
            app.languageChanged()
        }
        instrumentation.waitForIdleSync()
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val rendered = CountDownLatch(1)
        instrumentation.runOnMainSync { requireNotNull(host).window.decorView.let { decor ->
            decor.postOnAnimation { decor.postOnAnimation { decor.postOnAnimation { rendered.countDown() } } }
        } }
        assertTrue(rendered.await(5, TimeUnit.SECONDS))
        saveDeviceScreenshot(app, name, compose.onNodeWithTag("reset-viewport").captureToImage().asAndroidBitmap())
    }
    private fun assertTextFits() {
        val nodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).fetchSemanticsNodes()
        val overflow = compose.runOnIdle { nodes.flatMap { node ->
            val results = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
            results.filter { result -> result.didOverflowHeight || result.multiParagraph.didExceedMaxLines ||
                (0 until result.lineCount).any { result.getLineRight(it) - result.getLineLeft(it) > result.size.width + 1f || result.isLineEllipsized(it) }
            }.map { it.layoutInput.text.text }
        } }
        assertTrue("Reset card text must wrap: $overflow", overflow.isEmpty())
    }
    companion object { private const val NOW = 1793750400L }
}
