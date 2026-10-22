package com.tsonglew.quotapal

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.tsonglew.quotapal.data.PreferencesState
import com.tsonglew.quotapal.ui.QuotaPalApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class UiExperienceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TestQuotaApplication
    private lateinit var previousPreferences: PreferencesState

    @Before fun prepareLocalDemo() = runBlocking {
        previousPreferences = app.settings.flow.first()
        app.repository.logout()
        app.settings.theme("light")
        app.settings.showRemaining(true)
    }

    @After fun restorePreferences() = runBlocking {
        app.repository.logout()
        app.settings.theme(previousPreferences.theme)
        app.settings.showRemaining(previousPreferences.showRemaining)
    }

    @Test fun tabSwitchPreservesScrollAndTrendPeriodWhileRefreshStaysReachable() {
        ActivityScenario.launch(MainActivity::class.java).use {
            enterDemo()
            compose.onNodeWithTag("token-trend-30").performScrollTo().performClick().assertIsSelected()
            compose.onNodeWithTag("token-trend-chart").performScrollTo()
            val quotaScroll = scrollPosition("quota")
            assertTrue("The regression must leave the first viewport", quotaScroll > 0f)
            compose.onNodeWithTag("refresh-button").assertIsDisplayed()

            compose.onNodeWithTag("tab-settings").performClick()
            compose.onNodeWithTag("diagnostics-preview").performScrollTo()
            val settingsScroll = scrollPosition("settings")
            compose.onNodeWithTag("tab-quota").performClick()
            compose.onNodeWithTag("token-trend-30").assertIsSelected()
            assertEquals("Quota scroll survives a tab change", quotaScroll, scrollPosition("quota"), 1f)
            compose.onNodeWithTag("refresh-button").assertIsDisplayed()
            screenshot("ux-trend-restored")

            compose.onNodeWithTag("tab-settings").performClick()
            assertEquals("Each destination retains its own position", settingsScroll, scrollPosition("settings"), 1f)
        }
    }

    @Test fun displayPreferenceHasOneLabeledSwitchAndTogglesFromItsText() {
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitWelcome()
            compose.onNodeWithTag("tab-settings").performClick()
            val toggle = compose.onNodeWithTag("show-remaining-toggle").performScrollTo()
            toggle.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
                .assertHasClickAction().assertIsOn().assertHeightIsAtLeast(48.dp)
                .assertTextContains("显示剩余额度")
                .assertTextContains("同步更新所有桌面小组件。")
            compose.onAllNodes(hasTestTag("show-remaining-toggle") and isToggleable()).assertCountEquals(1)

            // Hit the label, not the visual switch: this catches a callback accidentally
            // left only on the trailing control instead of the complete preference row.
            val label = compose.onNodeWithText("显示剩余额度", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val row = toggle.fetchSemanticsNode().boundsInRoot
            toggle.performTouchInput { click(label.center - row.topLeft) }
            compose.waitUntil(5_000) { !runBlocking { app.settings.flow.first() }.showRemaining }
            toggle.assertIsOff()
            toggle.performClick()
            compose.waitUntil(5_000) { runBlocking { app.settings.flow.first() }.showRemaining }
            toggle.assertIsOn()
            screenshot("ux-settings-light")
        }
    }

    @Test fun narrowLayoutSupportsPlatformDoubleFontSizeAndAllPrimaryActions() {
        // This applies Android's real font configuration, including its text metrics.
        // The 320dp constraint exercises the full app at a narrow available width;
        // it does not change the emulator's global display size.
        withWidgetFontScale(2f) {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertEquals(2f, activity.resources.configuration.fontScale, 0.01f)
                    val model = ViewModelProvider(activity)[MainViewModel::class.java]
                    activity.setContent {
                        Box(Modifier.width(320.dp).fillMaxHeight().testTag("narrow-app")) {
                            QuotaPalApp(model)
                        }
                    }
                }
                enterDemo()
                compose.onNodeWithTag("narrow-app").assertWidthIsEqualTo(320.dp)
                assertTextFits()
                screenshot("ux-narrow-large-font-quota")

                compose.onNodeWithTag("token-trend-30").performScrollTo().performClick().assertIsSelected()
                assertTextFits()
                compose.onNodeWithTag("token-trend-chart").performScrollTo().assertIsDisplayed()
                screenshot("ux-narrow-large-font-trend")
                compose.onNodeWithTag("token-trend-details").performScrollTo().performClick()
                compose.onNodeWithTag("token-trend-day-list").assertExists()
                compose.onNodeWithTag("token-trend-details-close").performClick()

                compose.onNodeWithTag("tab-settings").performClick()
                compose.onNodeWithTag("show-remaining-toggle").performScrollTo().assertIsOn()
                assertTextFits()
                screenshot("ux-narrow-large-font-settings")
                compose.onNodeWithTag("theme-dark").performScrollTo().performClick()
                compose.waitUntil(5_000) { runBlocking { app.settings.flow.first() }.theme == "dark" }
                compose.onNodeWithTag("theme-dark").assertIsSelected()
                compose.onNodeWithTag("automatic-app-updates").performScrollTo().assertHasClickAction()
                assertTextFits()
                screenshot("ux-narrow-large-font-updates-dark")

                compose.onNodeWithTag("tab-widgets").performClick()
                compose.onNodeWithTag("add-slim-widget-button").performScrollTo().assertIsDisplayed()
                compose.onNodeWithTag("add-widget-button").performScrollTo().assertIsDisplayed()
                assertTextFits()
                screenshot("ux-narrow-large-font-widgets-dark")
            }
        }
    }

    @Test fun wideWindowUsesSideNavigationAndKeepsContentWidthReadable() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var sufficientlyWide = false
            scenario.onActivity { activity ->
                val config = activity.resources.configuration
                sufficientlyWide = config.screenWidthDp >= 600 && config.screenHeightDp >= 480
            }
            assumeTrue("Run this case in an actual window at least 600dp × 480dp", sufficientlyWide)
            enterDemo()
            assertTrue("Content width is capped at 640dp", compose.onNodeWithTag("page-quota").fetchSemanticsNode().size.width <= 640f * app.resources.displayMetrics.density)
            val page = compose.onNodeWithTag("page-quota").fetchSemanticsNode().boundsInRoot
            val navigation = listOf("quota", "widgets", "settings").map { tab ->
                compose.onNodeWithTag("tab-$tab").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            }
            navigation.forEach { item ->
                assertTrue("Wide navigation belongs to the left of the content", item.right <= page.left)
            }
            assertTrue("Navigation destinations form a vertical rail", navigation.zipWithNext().all { (first, second) ->
                first.center.y < second.center.y && kotlin.math.abs(first.center.x - second.center.x) < 1f
            })
            assertTextFits()
            screenshot("ux-wide-quota")
            compose.onNodeWithTag("tab-settings").performClick()
            assertTrue("Content width is capped at 640dp", compose.onNodeWithTag("page-settings").fetchSemanticsNode().size.width <= 640f * app.resources.displayMetrics.density)
            screenshot("ux-wide-settings")
        }
    }

    private fun awaitWelcome() {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("demo-button").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun enterDemo() {
        awaitWelcome()
        compose.onNodeWithTag("demo-button").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("token-usage-card").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun scrollPosition(tab: String): Float {
        compose.waitForIdle()
        return compose.onNodeWithTag("page-$tab").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    }

    private fun assertTextFits() {
        waitForRenderedFrames()
        val firstFailures = textOverflowDiagnostics()
        // A new screen can have composed semantics before Android submits its
        // updated text layer. Verify a second settled layout before failing.
        val failures = if (firstFailures.isEmpty()) firstFailures else {
            waitForRenderedFrames()
            textOverflowDiagnostics()
        }
        if (failures.isNotEmpty()) screenshot("ux-text-overflow")
        assertTrue("Text must wrap without clipping. Initial layout:\n${firstFailures.joinToString("\n")}\n" +
            "Settled layout:\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    private fun textOverflowDiagnostics(): List<String> {
        compose.waitForIdle()
        val nodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertTrue("The layout must contain inspectable native text", nodes.isNotEmpty())
        val observations = compose.runOnIdle {
            nodes.flatMap { node ->
                val results = mutableListOf<TextLayoutResult>()
                node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
                results.map { result ->
                    val input = result.layoutInput
                    // Compose 1.8.2 SimpleText semantics recreate MultiParagraph with
                    // prevConstraints.copyMaxDimensions(), while retaining layoutSize.
                    // Its paragraph can therefore be wider than the measured Text even
                    // when all glyphs fit. didOverflowWidth compares these two widths.
                    // Inspect each actual line advance instead; subtracting lineLeft
                    // also removes the rebuilt paragraph's center/end alignment offset.
                    // Official source: foundation-android 1.8.2 sources JAR, Google Maven,
                    // ParagraphLayoutCache.slowCreateTextLayoutResultOrNull (line 328):
                    // https://dl.google.com/dl/android/maven2/androidx/compose/foundation/foundation-android/1.8.2/foundation-android-1.8.2-sources.jar
                    val lineAdvances = (0 until result.lineCount).map { line ->
                        result.getLineRight(line) - result.getLineLeft(line)
                    }
                    val ellipsizedLines = (0 until result.lineCount).filter(result::isLineEllipsized)
                    val actualWidthOverflow = lineAdvances.any { it > result.size.width }
                    val failed = actualWidthOverflow || result.didOverflowHeight ||
                        result.multiParagraph.didExceedMaxLines || ellipsizedLines.isNotEmpty()
                    val diagnostic = "text=${input.text.text}; constraints=${input.constraints}; size=${result.size}; " +
                        "paragraph=${result.multiParagraph.width}×${result.multiParagraph.height}; " +
                        "didOverflowWidth=${result.didOverflowWidth}; didOverflowHeight=${result.didOverflowHeight}; " +
                        "lineAdvances=$lineAdvances; actualWidthOverflow=$actualWidthOverflow; " +
                        "exceededMaxLines=${result.multiParagraph.didExceedMaxLines}; ellipsizedLines=$ellipsizedLines; " +
                        "lineCount=${result.lineCount}; density=${input.density.density}; fontScale=${input.density.fontScale}; " +
                        "fontSize=${input.style.fontSize}; lineHeight=${input.style.lineHeight}; bounds=${node.boundsInRoot}"
                    failed to diagnostic
                }
            }
        }
        // Preserve all observations, including framework-width false positives,
        // so a passing assertion remains independently reviewable after the run.
        java.io.File(app.getExternalFilesDir(null), "ui-layout-diagnostics.txt").appendText(
            "\nInspection ${java.time.Instant.now()}\n" + observations.joinToString("\n") { (failed, diagnostic) ->
                "${if (failed) "FAIL" else "PASS"} $diagnostic"
            } + "\n"
        )
        return observations.filter { it.first }.map { it.second }
    }

    private fun waitForRenderedFrames() {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val rendered = CountDownLatch(1)
        var hasResumedActivity = false
        instrumentation.runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .firstOrNull { it is MainActivity }
            hasResumedActivity = activity != null
            activity?.window?.decorView?.let { decor ->
                decor.postOnAnimation {
                    decor.postOnAnimation {
                        decor.postOnAnimation { rendered.countDown() }
                    }
                }
            }
        }
        assertTrue("A resumed MainActivity must render the UI", hasResumedActivity)
        assertTrue("Android must submit three frames before inspection", rendered.await(5, TimeUnit.SECONDS))
        compose.waitForIdle()
    }

    private fun screenshot(name: String) {
        waitForRenderedFrames()
        saveDeviceScreenshot(app, name, compose.onRoot().captureToImage().asAndroidBitmap())
    }
}
