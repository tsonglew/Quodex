package com.tsonglew.quodex

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.tsonglew.quodex.data.AppState
import com.tsonglew.quodex.data.FailureKind
import com.tsonglew.quodex.data.PreferencesState
import com.tsonglew.quodex.data.UsageParser
import com.tsonglew.quodex.data.UsageSnapshot
import com.tsonglew.quodex.data.absoluteTime
import com.tsonglew.quodex.data.demoSnapshot
import com.tsonglew.quodex.ui.QuotaOverview
import com.tsonglew.quodex.ui.QuotaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercise the same quota region as Dashboard, including optional account data. */
class QuotaOverviewUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val state = mutableStateOf(AppState())
    private val preferences = mutableStateOf(PreferencesState())
    private var host: MainActivity? = null
    private var reconnects = 0

    @Test fun windowlessAndRestrictedAccountsKeepTheirDistinctMeaning() {
        withOverview(parse("""{"plan_type":"pro","rate_limit":{"allowed":true}}""")) {
            compose.onNodeWithText("当前可用").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("账号未提供周期额度，无需显示百分比。实际使用仍以账号权益为准。")
                .performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("quota-primary").assertDoesNotExist()
            compose.onAllNodesWithText("%", substring = true).assertCountEquals(0)

            replaceSnapshot("""{"plan_type":"pro","rate_limit":null}""")
            compose.onNodeWithText("未提供周期额度").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("当前可用").assertDoesNotExist()
            compose.onNodeWithTag("quota-primary").assertDoesNotExist()
            compose.onAllNodesWithText("%", substring = true).assertCountEquals(0)

            replaceSnapshot("""{"rate_limit":{"allowed":false,"primary_window":{"used_percent":20,"limit_window_seconds":18000}}}""")
            compose.onNodeWithText("当前使用受限").performScrollTo().assertIsDisplayed()
            // A restriction does not erase a valid cached quota or turn it into zero.
            compose.onNodeWithTag("quota-primary-value").assertTextEquals("80")
            compose.onNodeWithTag("quota-meter-codex:primary_window").assertExists()
        }
    }

    @Test fun weeklyOnlyAccountKeepsItsQuotaWithoutInventingAShortWindow() {
        withOverview(parse("""{"plan_type":"pro","rate_limit":{"secondary_window":{"used_percent":38,"limit_window_seconds":604800}}}""")) {
            compose.onNodeWithText("未提供短周期额度").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("当前账号未提供按小时计算的额度；已返回的长期额度仍正常显示。")
                .performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("每周额度").assertExists()
            compose.onNodeWithTag("quota-primary-value").assertTextEquals("62")
            compose.onNodeWithText("5 小时额度").assertDoesNotExist()
            compose.onNodeWithTag("quota-meter-codex:secondary_window").assertExists()
        }
    }

    @Test fun reviewOnlyAccountShowsEveryExtraWithoutPromotingOneToPrimaryQuota() {
        val snapshot = parse("""{"plan_type":"pro","rate_limit":null,"additional_rate_limits":[
            {"metered_feature":"review","limit_name":"Review","rate_limit":{
                "primary_window":{"used_percent":20,"limit_window_seconds":18000},
                "secondary_window":{"used_percent":40,"limit_window_seconds":604800}}},
            {"metered_feature":"research","limit_name":"Research","rate_limit":{
                "primary_window":{"used_percent":60,"limit_window_seconds":86400}}}
        ]}""")
        withOverview(snapshot) {
            compose.onNodeWithText("未提供周期额度").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("quota-primary").assertDoesNotExist()
            compose.onNodeWithTag("quota-primary-value").assertDoesNotExist()
            for (window in snapshot.windows) {
                compose.onNodeWithText(window.name).performScrollTo().assertIsDisplayed()
                compose.onNodeWithTag("quota-meter-${window.id}").performScrollTo().assertIsDisplayed()
            }
            capture("arc-review-only")
        }
    }

    @Test fun unknownUsageHasAnExplanationWhileARealZeroStillHasAMeter() {
        withOverview(parse("""{"rate_limit":{"primary_window":{"limit_window_seconds":18000}}}""")) {
            compose.onNodeWithText("暂不可用").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("服务暂未提供有效的用量数据，请稍后刷新。")
                .performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("quota-meter-codex:primary_window").assertDoesNotExist()
            compose.onAllNodesWithText("%", substring = true).assertCountEquals(0)

            replaceSnapshot("""{"rate_limit":{"primary_window":{"used_percent":100,"limit_window_seconds":18000}}}""")
            compose.onNodeWithTag("quota-primary-value").assertTextEquals("0")
            compose.onNodeWithTag("quota-meter-codex:primary_window").assertExists()
                .assertContentDescriptionContains("剩余 0%", substring = true)
            compose.onNodeWithText("暂不可用").assertDoesNotExist()
        }
    }

    @Test fun remainingPreferenceChangesTheNumberAndAccessibleMeaningTogether() {
        val sample = parse("""{"rate_limit":{"primary_window":{"used_percent":38,"limit_window_seconds":604800}}}""")
            .copy(resetCredits = 2)
        withOverview(sample, demo = true) {
            compose.onNodeWithText("示例数据 · 非真实额度").assertExists()
            compose.onNodeWithText("示例更新于 ${absoluteTime(NOW, true)}").assertExists()
            compose.onNodeWithText("2 次重置可用").assertExists()
            val meter = compose.onNodeWithTag("quota-meter-codex:primary_window")
            compose.onNodeWithTag("quota-primary-value").assertTextEquals("62")
            compose.onNodeWithText("剩余额度").assertExists()
            meter.assertContentDescriptionContains("每周额度", substring = true)
                .assertContentDescriptionContains("剩余 62%", substring = true)

            compose.runOnIdle { preferences.value = preferences.value.copy(showRemaining = false) }
            compose.onNodeWithTag("quota-primary-value").assertTextEquals("38")
            compose.onNodeWithText("已用额度").assertExists()
            compose.onNodeWithText("剩余额度").assertDoesNotExist()
            meter.assertContentDescriptionContains("已用 38%", substring = true)
            compose.onAllNodesWithContentDescription("剩余 62%", substring = true).assertCountEquals(0)
        }
    }

    @Test fun credentialFailuresOfferReconnectWithoutDiscardingCachedQuota() {
        val cached = parse("""{"rate_limit":{"primary_window":{"used_percent":38,"limit_window_seconds":604800,"reset_at":1700003600}}}""")
        withOverview(cached, failure = FailureKind.AUTH) {
            for (failure in listOf(FailureKind.AUTH, FailureKind.STORAGE)) {
                compose.runOnIdle { state.value = state.value.copy(failure = failure) }
                compose.onNodeWithText(if (failure == FailureKind.AUTH) "连接已失效，请重新登录"
                    else "无法读取安全凭据，请重新连接账号").performScrollTo().assertIsDisplayed()
                compose.onNodeWithTag("quota-reconnect").performScrollTo().assertHasClickAction().performClick()
                compose.onNodeWithTag("quota-primary-value").assertTextEquals("62")
                compose.onNodeWithText("更新于 ${absoluteTime(NOW, true)}").assertExists()
            }
            compose.runOnIdle {
                assertEquals("Both credential errors must call the supplied reconnect action", 2, reconnects)
                assertEquals("Reconnecting must not mutate the displayed cached snapshot", cached, state.value.snapshot)
            }
            compose.onNodeWithTag("quota-update-info").performScrollTo().performClick()
            compose.onNodeWithText("额度更新说明").assertIsDisplayed()
            compose.onNodeWithText("右上角可手动刷新。更新失败时保留最后成功的数据，超过重置时间的额度需刷新确认。")
                .performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("知道了").performClick()
            compose.onNode(isDialog()).assertDoesNotExist()
        }
    }

    @Test fun narrowDoubleFontOverviewKeepsTextInsideTheViewportAndActionsReachable() {
        withWidgetFontScale(2f) {
            withOverview(demoSnapshot(NOW), failure = FailureKind.AUTH, width = 320.dp) {
                assertEquals(2f, requireNotNull(host).resources.configuration.fontScale, 0.01f)
                compose.onNodeWithTag(VIEWPORT).assertWidthIsEqualTo(320.dp)
                compose.onNodeWithText("额度概览").performScrollTo().assertIsDisplayed()
                assertTextFits()
                capture("arc-narrow-double-font-top")

                for (window in requireNotNull(state.value.snapshot).windows) {
                    compose.onNodeWithTag("quota-meter-${window.id}").performScrollTo().assertIsDisplayed()
                    assertTextFits()
                }
                compose.onNodeWithText("更新于 ${absoluteTime(NOW, true)}")
                    .performScrollTo().assertIsDisplayed()
                compose.onNodeWithTag("quota-update-info").performScrollTo().assertIsDisplayed().performClick()
                compose.onNodeWithText("额度更新说明").assertIsDisplayed()
                compose.onNodeWithText("知道了").assertIsDisplayed().performClick()
                compose.onNodeWithTag("quota-reconnect").performScrollTo().assertIsDisplayed()
                capture("arc-narrow-double-font-reconnect-idle")
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                saveDeviceScreenshot(instrumentation.targetContext, "arc-narrow-double-font-reconnect-screen",
                    requireNotNull(instrumentation.uiAutomation.takeScreenshot()))
                compose.onNodeWithTag("quota-reconnect").performClick()
                compose.runOnIdle { assertEquals(1, reconnects) }
                assertTrue("The narrow layout must remain vertically scrollable",
                    compose.onNodeWithTag(VIEWPORT).fetchSemanticsNode()
                        .config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f)
                assertTextFits()
                capture("arc-narrow-double-font-bottom")
            }
        }
    }

    private fun withOverview(
        snapshot: UsageSnapshot,
        demo: Boolean = false,
        failure: FailureKind? = null,
        width: Dp = 360.dp,
        block: () -> Unit,
    ) {
        state.value = AppState(initialized = true, connected = !demo, snapshot = snapshot, failure = failure, demo = demo)
        preferences.value = PreferencesState(showRemaining = true, theme = "light")
        reconnects = 0
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                host = activity
                activity.setContent {
                    QuotaTheme(preferences.value.theme) {
                        Surface(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                            Column(Modifier.width(width).fillMaxHeight().testTag(VIEWPORT)
                                .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
                                QuotaOverview(state.value, preferences.value, NOW) { reconnects++ }
                            }
                        }
                    }
                }
            }
            try {
                compose.onNodeWithTag("quota-overview").assertExists()
                block()
            } finally { host = null }
        }
    }

    private fun replaceSnapshot(body: String) {
        compose.runOnIdle { state.value = state.value.copy(snapshot = parse(body)) }
    }

    private fun parse(body: String): UsageSnapshot = UsageParser.parse(body, "synthetic-account", NOW)

    private fun assertTextFits() {
        waitForRenderedFrames()
        val textNodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
            useUnmergedTree = true)
        val nodes = textNodes.fetchSemanticsNodes()
        assertTrue("The overview must expose inspectable text", nodes.isNotEmpty())
        val viewport = compose.onNodeWithTag(VIEWPORT).getUnclippedBoundsInRoot()
        nodes.indices.forEach { index ->
            val bounds = textNodes[index].getUnclippedBoundsInRoot()
            assertTrue("Quota text must stay horizontally inside the viewport: $bounds / $viewport",
                bounds.left >= viewport.left - 1.dp && bounds.right <= viewport.right + 1.dp)
        }
        val failures = compose.runOnIdle {
            nodes.flatMap { node ->
                val results = mutableListOf<TextLayoutResult>()
                node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
                results.filter { result ->
                    // Match UiExperienceTest: SimpleText can reconstruct a wider paragraph,
                    // so inspect actual line advances instead of didOverflowWidth alone.
                    (0 until result.lineCount).any { line ->
                        result.getLineRight(line) - result.getLineLeft(line) > result.size.width + 1f ||
                            result.isLineEllipsized(line)
                    } || result.didOverflowHeight || result.multiParagraph.didExceedMaxLines
                }.map { "${it.layoutInput.text.text}: measured=${it.size}, lines=${it.lineCount}" }
            }
        }
        assertTrue("Quota text must wrap without horizontal overflow or clipping: ${failures.joinToString()}", failures.isEmpty())
    }

    private fun waitForRenderedFrames() {
        compose.waitForIdle()
        val rendered = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            requireNotNull(host).window.decorView.let { decor ->
                decor.postOnAnimation { decor.postOnAnimation { decor.postOnAnimation { rendered.countDown() } } }
            }
        }
        assertTrue("The overview must submit rendered frames", rendered.await(5, TimeUnit.SECONDS))
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        waitForRenderedFrames()
        saveDeviceScreenshot(InstrumentationRegistry.getInstrumentation().targetContext, name,
            compose.onNodeWithTag(VIEWPORT).captureToImage().asAndroidBitmap())
    }

    private companion object {
        const val NOW = 1_700_000_000L
        const val VIEWPORT = "quota-overview-test-viewport"
    }
}
