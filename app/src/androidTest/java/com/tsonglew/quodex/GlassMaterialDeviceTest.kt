package com.tsonglew.quodex

import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.tsonglew.quodex.data.GlassPreferences
import com.tsonglew.quodex.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GlassMaterialDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.quotaApp as TestQuotaApplication

    @Test fun appearanceSettingsPersistIndependentlyAndReducedTransparencyKeepsControlsAccessible() {
        val previous = runBlocking { app.settings.read() }
        try {
            runBlocking {
                app.settings.theme("light")
                app.settings.glassTint(false, 1f); app.settings.glassTint(true, 1f)
                app.settings.reduceTransparency(false)
                app.repository.demo()
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.openSettingsPage("appearance")
                compose.onNodeWithTag("glass-settings").performScrollToVisible(compose).performClick()
                compose.onNodeWithTag("glass-light-tint").performSemanticsAction(SemanticsActions.SetProgress) { it(0.4f) }
                compose.waitUntil(5_000) { runBlocking { app.settings.read().glass.lightTint == 0.4f } }
                compose.onNodeWithTag("glass-dark-tint").performSemanticsAction(SemanticsActions.SetProgress) { it(1.3f) }
                compose.waitUntil(5_000) { runBlocking { app.settings.read().glass.darkTint == 1.3f } }
                screenshot("glass-settings-light")
                compose.onNodeWithTag("reduce-transparency").performScrollTo().performClick().assertIsOn()
                compose.waitUntil(5_000) { runBlocking { app.settings.read().glass.reduceTransparency } }
                compose.onNodeWithTag("glass-light-tint").assertIsNotEnabled()
                screenshot("glass-settings-reduced")
                compose.onNodeWithTag("glass-settings-close").performClick()
                compose.onNodeWithTag("theme-dark").performScrollToVisible(compose).performClick()
                scenario.recreate()
                compose.openSettingsPage("appearance")
                compose.onNodeWithTag("glass-settings").performScrollToVisible(compose).performClick()
                compose.onNodeWithTag("reduce-transparency").assertIsOn().performClick()
                compose.onNodeWithTag("glass-dark-tint").assertIsEnabled()
                screenshot("glass-settings-dark")
                assertEquals(0.4f, runBlocking { app.settings.read().glass.lightTint }, 0f)
                assertEquals(1.3f, runBlocking { app.settings.read().glass.darkTint }, 0f)
            }
        } finally {
            runBlocking {
                app.settings.theme(previous.theme)
                app.settings.glassTint(false, previous.glass.lightTint); app.settings.glassTint(true, previous.glass.darkTint)
                app.settings.reduceTransparency(previous.glass.reduceTransparency)
                app.repository.logout()
            }
        }
    }

    @Test fun realBackdropChangesMaterialWithoutMovingOrBlurringForegroundAndSolidFallbackWorks() {
        var reduced by mutableStateOf(false)
        var dark by mutableStateOf(false)
        var shift by mutableFloatStateOf(0f)
        var resolved by mutableStateOf<GlassTokens?>(null)
        var samples by mutableStateOf<GlassBackdropStats?>(null)
        var backdrop: GlassBackdrop? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    QuotaTheme(if (dark) "dark" else "light", GlassPreferences(reduceTransparency = reduced)) {
                        val recorded = LocalGlassBackdrop.current
                        SideEffect { backdrop = recorded }
                        // Synthetic, readable fixture lives only in androidTest; the product adds no wallpaper.
                        Box(Modifier.fillMaxSize().captureGlassBackdrop()) {
                            Canvas(Modifier.fillMaxSize()) {
                                drawRect(if (dark) Color(0xFF12161C) else Color(0xFFEAEFF5))
                                for (i in 0..20) drawRect(if (dark) Color(0xFF253E50) else Color(0xFFB8D8EB),
                                    Offset(i * 60f + shift, 0f), Size(25f, size.height))
                            }
                        }
                        val material = rememberGlassSurface("device-fixture", RoundedCornerShape(28.dp))
                        SideEffect { resolved = material.tokens; samples = material.stats }
                        // Use the production dialog as well as a probe for asynchronous readiness.
                        GlassAlertDialog("device-dialog", {}, {
                            TextButton(onClick = {}, modifier = Modifier.testTag("fixture-action")) { Text("保持清晰") }
                        }, modifier = Modifier.testTag("fixture-dialog"), title = { Text("玻璃材质") }, text = {
                            Text("文字与图标独立绘制，背景变化不改变布局。", Modifier.testTag("fixture-text"))
                        })
                        // The probe is an existing-shape surface with identical capture coordinates.
                        Box(Modifier.offset(20.dp, 100.dp).size(120.dp).then(material.modifier))
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= 31) compose.waitUntil(10_000) { resolved?.solid == false }
            else compose.waitUntil(5_000) { resolved?.solid == true }
            settle()
            android.util.Log.i("GlassValidation", "Fixture material: $resolved; samples: $samples")
            val geometry = compose.onNodeWithTag("fixture-text").getUnclippedBoundsInRoot()
            val before = compose.onNodeWithTag("fixture-dialog").captureToImage()
            saveDeviceScreenshot(app, "glass-dialog-before", before.asAndroidBitmap())
            val generationBefore = backdrop?.generation
            screenshot("glass-fixture-light")
            compose.runOnIdle { shift = 30f }
            settle()
            val after = compose.onNodeWithTag("fixture-dialog").captureToImage()
            saveDeviceScreenshot(app, "glass-dialog-after", after.asAndroidBitmap())
            screenshot("glass-fixture-moved")
            android.util.Log.i("GlassValidation", "Backdrop revision $generationBefore -> ${backdrop?.generation}")
            assertEquals(geometry, compose.onNodeWithTag("fixture-text").getUnclippedBoundsInRoot())
            if (Build.VERSION.SDK_INT >= 31) {
                val a = before.toPixelMap(); val b = after.toPixelMap()
                var different = 0
                for (y in 16 until before.height - 16 step 20) for (x in 30 until before.width - 30 step 10)
                    if (a[x, y] != b[x, y]) different++
                assertTrue("Recorded backdrop must actually reach the dialog, not just tint a solid window", different > 2)
            }
            compose.runOnIdle { reduced = true }
            settle()
            screenshot("glass-fixture-solid")
            assertEquals(geometry, compose.onNodeWithTag("fixture-text").getUnclippedBoundsInRoot())
            compose.onNodeWithTag("fixture-action").assertHasClickAction().performClick()
            compose.runOnIdle { reduced = false; dark = true }
            if (Build.VERSION.SDK_INT >= 31) compose.waitUntil(10_000) { resolved?.solid == false && resolved?.lightInk == true }
            settle()
            screenshot("glass-fixture-dark")
        }
    }

    private fun settle() {
        compose.waitForIdle()
        // Sampling is deliberately coalesced; wait for its finite debounce and material transition.
        Thread.sleep(700)
        compose.waitForIdle()
    }

    private fun screenshot(name: String) {
        settle()
        saveDeviceScreenshot(app, name, requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()))
    }
}
