package com.tsonglew.quodex

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.rememberGraphicsLayer
import com.tsonglew.quodex.ui.NavigationBackdrop
import com.tsonglew.quodex.data.GlassPreferences
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.tsonglew.quodex.ui.FloatingNavigation
import com.tsonglew.quodex.ui.QuotaTheme
import com.tsonglew.quodex.ui.TokenPeriodSelector
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

@OptIn(ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LiquidSelectionDeviceTest {
    // Pin animation tests to 1x even when CI disables device animations. The zero-scale
    // case below overrides this provider before composing its fixture.
    private val motionScale = object : MotionDurationScale {
        override var scaleFactor by mutableFloatStateOf(1f)
    }
    @get:Rule val compose = kotlinx.coroutines.test.StandardTestDispatcher().let { dispatcher ->
        createEmptyComposeRule(effectContext = dispatcher + motionScale).drainQueuedEffects(dispatcher.scheduler)
    }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After fun restoreClock() { compose.mainClock.autoAdvance = true }

    @Test fun navigationRendersIntermediateFramesAndRedirectsWithoutMovingTargets() {
        var selected by mutableStateOf("quota")
        var ink = Color.Unspecified
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                  SelectionTestTheme("light") {
                    ink = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f).compositeOver(MaterialTheme.colorScheme.surface)
                    FloatingNavigation(listOf(Triple("quota", "概要", Icons.Outlined.Dashboard),
                        Triple("widgets", "アカウント", Icons.Outlined.Widgets),
                        Triple("settings", "設定", Icons.Outlined.Settings)), selected, { selected = it },
                        NavigationBackdrop(rememberGraphicsLayer()))
                  }
                }
            } }
            val group = compose.onNodeWithTag("floating-navigation")
            val quota = compose.onNodeWithTag("tab-quota")
            val settings = compose.onNodeWithTag("tab-settings")
            val targetBounds = settings.fetchSemanticsNode().boundsInRoot
            val accountBounds = compose.onNodeWithTag("tab-widgets").fetchSemanticsNode().boundsInRoot
            assertEquals("Wrapped labels must retain the same capsule height", targetBounds.height, accountBounds.height, 0.5f)
            assertEquals(targetBounds.center.y, accountBounds.center.y, 0.5f)
            val start = centerOfInk(group, ink)
            compose.mainClock.autoAdvance = false
            settings.performClick()
            compose.mainClock.advanceTimeBy(96)
            settings.assertIsSelected()
            val moving = centerOfInk(group, ink)
            saveDeviceScreenshot(context, "liquid-dock-moving", group.captureToImage().asAndroidBitmap())
            assertTrue("The surface has left its old location", moving.x > start.x + 5f)
            assertTrue("The surface has not jumped to its destination",
                moving.x < targetBounds.center.x - group.fetchSemanticsNode().boundsInRoot.left - 5f)
            quota.performClick()
            compose.mainClock.advanceTimeBy(64)
            compose.onNodeWithTag("tab-widgets").performClick()
            compose.mainClock.advanceTimeBy(1_000)
            assertOnItem(group, "tab-widgets", ink)
            assertEquals("Touch targets must stay fixed", targetBounds, settings.fetchSemanticsNode().boundsInRoot)
            saveDeviceScreenshot(context, "liquid-dock-settled", group.captureToImage().asAndroidBitmap())
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun periodsResizeAndReverseInDarkRtlLayout() {
        var selected by mutableIntStateOf(7)
        var ink = Color.Unspecified
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    SelectionTestTheme("dark") {
                        ink = MaterialTheme.colorScheme.primaryContainer
                        Box(Modifier.width(320.dp).background(MaterialTheme.colorScheme.surface)) {
                            TokenPeriodSelector(selected, { selected = it })
                        }
                    }
                }
            } }
            val group = compose.onNodeWithTag("token-period-selector")
            val seven = compose.onNodeWithTag("token-trend-7")
            val thirty = compose.onNodeWithTag("token-trend-30")
            val thirtyBounds = thirty.fetchSemanticsNode().boundsInRoot
            assertTrue(seven.fetchSemanticsNode().boundsInRoot.center.x > thirtyBounds.center.x)
            val start = centerOfInk(group, ink)
            compose.mainClock.autoAdvance = false
            thirty.performClick().assertHeightIsAtLeast(48.dp)
            compose.mainClock.advanceTimeBy(96)
            thirty.assertIsSelected()
            val moving = centerOfInk(group, ink)
            assertTrue(moving.x < start.x - 5f)
            assertTrue(moving.x > thirtyBounds.center.x - group.fetchSemanticsNode().boundsInRoot.left + 5f)
            saveDeviceScreenshot(context, "liquid-period-rtl-moving", group.captureToImage().asAndroidBitmap())
            seven.performClick()
            compose.mainClock.advanceTimeBy(64)
            thirty.performClick()
            compose.mainClock.advanceTimeBy(1_000)
            assertOnItem(group, "token-trend-30", ink)
            assertEquals(thirtyBounds, thirty.fetchSemanticsNode().boundsInRoot)
            saveDeviceScreenshot(context, "liquid-period-rtl-settled", group.captureToImage().asAndroidBitmap())
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun wrappedLargeTextSelectionSnapsToItsOwnRow() {
        var selected by mutableIntStateOf(7)
        var width by mutableStateOf(320.dp)
        var ink = Color.Unspecified
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    SelectionTestTheme("light") {
                        ink = MaterialTheme.colorScheme.primaryContainer
                        Box(Modifier.width(width).background(MaterialTheme.colorScheme.surface)) {
                            TokenPeriodSelector(selected, { selected = it })
                        }
                    }
                }
            } }
            val seven = compose.onNodeWithTag("token-trend-7")
            val thirty = compose.onNodeWithTag("token-trend-30").assertExists()
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            thirty.performClick()
            compose.mainClock.advanceTimeBy(64)
            compose.runOnIdle { width = 200.dp }
            compose.mainClock.advanceTimeBy(32)
            assertTrue(thirty.fetchSemanticsNode().boundsInRoot.top >= seven.fetchSemanticsNode().boundsInRoot.bottom)
            assertOnItem(compose.onNodeWithTag("token-period-selector"), "token-trend-30", ink)
            seven.performClick()
            compose.mainClock.advanceTimeBy(32)
            seven.assertIsSelected()
            assertOnItem(compose.onNodeWithTag("token-period-selector"), "token-trend-7", ink)
            saveDeviceScreenshot(context, "liquid-period-wrapped", compose.onRoot().captureToImage().asAndroidBitmap())
            compose.mainClock.autoAdvance = true
        }
    }

    /** Verify zero duration independently of the CI emulator's global settings. */
    @Test fun selectionRemainsUsableAndSettlesAtItsTarget() {
        motionScale.scaleFactor = 0f
        var selected by mutableIntStateOf(7)
        var ink = Color.Unspecified
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                SelectionTestTheme("light") {
                    ink = MaterialTheme.colorScheme.primaryContainer
                    TokenPeriodSelector(selected, { selected = it })
                }
            } }
            compose.onNodeWithTag("token-trend-30").assertExists()
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag("token-trend-30").performClick()
            compose.mainClock.advanceTimeBy(64)
            compose.onNodeWithTag("token-trend-30").assertIsSelected()
            assertOnItem(compose.onNodeWithTag("token-period-selector"), "token-trend-30", ink)
            compose.onNodeWithTag("token-trend-7").performClick()
            compose.mainClock.advanceTimeBy(64)
            compose.onNodeWithTag("token-trend-7").assertIsSelected()
            assertOnItem(compose.onNodeWithTag("token-period-selector"), "token-trend-7", ink)
        }
    }

    private fun assertOnItem(group: SemanticsNodeInteraction, item: String, color: Color) {
        val expected = compose.onNodeWithTag(item).fetchSemanticsNode().boundsInRoot.center - group.fetchSemanticsNode().boundsInRoot.topLeft
        val actual = centerOfInk(group, color)
        // Text/icons leave small asymmetric holes in the colored surface.
        assertEquals(expected.x, actual.x, 5f)
        assertEquals(expected.y, actual.y, 5f)
    }

    private fun centerOfInk(node: SemanticsNodeInteraction, color: Color): Offset {
        compose.waitForIdle()
        val frames = java.util.concurrent.CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).first()
            activity.window.decorView.let { decor ->
                decor.postOnAnimation { decor.postOnAnimation { decor.postOnAnimation { frames.countDown() } } }
            }
        }
        assertTrue(frames.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val bitmap = node.captureToImage()
        val image = bitmap.toPixelMap()
        var count = 0; var xSum = 0f; var ySum = 0f
        for (y in 0 until image.height step 2) for (x in 0 until image.width step 2) {
            val pixel = image[x, y]
            if (abs(pixel.red - color.red) < 0.005f && abs(pixel.green - color.green) < 0.005f && abs(pixel.blue - color.blue) < 0.005f) {
                count++; xSum += x; ySum += y
            }
        }
        if (count <= 100) saveDeviceScreenshot(context, "liquid-missing-surface", bitmap.asAndroidBitmap())
        assertTrue("The selected surface must actually be drawn ($color, matches=$count)", count > 100)
        return Offset(xSum / count, ySum / count)
    }
}

// Android's native touch ripple uses wall time rather than the paused Compose test
// clock. Isolate the moving surface for pixel assertions; app-flow tests keep ripples.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTestTheme(mode: String, content: @Composable () -> Unit) {
    QuotaTheme(mode, GlassPreferences(reduceTransparency = true)) {
        CompositionLocalProvider(LocalRippleConfiguration provides null, content = content)
    }
}
