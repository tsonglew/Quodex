package com.tsonglew.quodex.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.tsonglew.quodex.data.GlassPreferences
import com.tsonglew.quodex.data.sanitizeGlassTint
import org.junit.Assert.*
import org.junit.Test

class GlassTokensTest {
    @Test fun sharedPageScenePreservesThemeInkAndTextContrastAtEveryTint() {
        for (dark in listOf(false, true)) for (amount in listOf(0f, 1f, 1.5f)) {
            val colors = glassSceneColors(dark)
            val stats = requireNotNull(GlassBackdropStats.from(List(15) { colors[it % colors.size] }))
            val tokens = resolveGlassTokens(dark, GlassPreferences(amount, amount), stats)
            assertEquals("All neighboring panels use the theme ink", dark, tokens.lightInk)
            for ((from, to) in colors.zipWithNext()) for (step in 0..20) {
                val color = androidx.compose.ui.graphics.lerp(from, to, step / 20f)
                val base = tokens.tint.compositeOver(processedGlassColor(color, tokens.saturation, tokens.brightness))
                for (ink in listOf(tokens.primary, tokens.secondary, quodexColorScheme(dark).primary, quodexColorScheme(dark).error)) {
                    assertTrue("dark=$dark amount=$amount gradient=$step", glassContrast(ink, base) >= 4.5f)
                }
            }
        }
    }

    @Test fun unknownWallpaperRemainsReadableAcrossBothThemesAndTintExtremes() {
        for (dark in listOf(false, true)) for (amount in listOf(0f, 0.5f, 1f, 1.5f)) {
            val tokens = resolveGlassTokens(dark, GlassPreferences(amount, amount))
            for (background in listOf(Color.Black, Color.White, Color.Red, Color.Blue, Color.Green)) {
                val composite = tokens.tint.compositeOver(background)
                for (ink in listOf(tokens.primary, tokens.secondary, quodexColorScheme(dark).primary, quodexColorScheme(dark).error))
                    assertTrue("dark=$dark tint=$amount contrast=${glassContrast(ink, composite)}", glassContrast(ink, composite) >= 4.5f)
            }
        }
    }

    @Test fun busyBackdropKeepsOutliersForContrastAndPreservesReadableInk() {
        val backgrounds = List(15) { listOf(Color.White, Color.Black, Color.Red, Color.Blue, Color.Green)[it % 5] }
        val stats = requireNotNull(GlassBackdropStats.from(backgrounds))
        assertEquals(15, stats.samples.size)
        for (dark in listOf(false, true)) {
            val tokens = resolveGlassTokens(dark, stats = stats)
            for (sample in backgrounds) {
                val background = tokens.tint.compositeOver(processedGlassColor(sample, tokens.saturation, tokens.brightness))
                assertTrue(glassContrast(tokens.secondary, background) >= 4.5f)
                assertTrue(glassContrast(tokens.primary, background) >= 4.5f)
            }
        }
    }

    @Test fun hysteresisDoesNotFlickerAndDarkThemeAlwaysUsesLightInk() {
        assertTrue(glassLightInk(false, 0.44f, false))
        assertTrue(glassLightInk(false, 0.54f, true))
        assertFalse(glassLightInk(false, 0.46f, false))
        assertFalse(glassLightInk(false, 0.56f, true))
        assertTrue(glassLightInk(true, 1f, false))
        assertFalse(glassLightInk(false, null, true))
        assertTrue(resolveGlassTokens(false, previousLightInk = true, forceSolid = true, restorePreviousInk = true).lightInk)
        assertFalse(resolveGlassTokens(false, previousLightInk = true, forceSolid = true).lightInk)
    }

    @Test fun missingOrTransparentCaptureCannotMasqueradeAsDarkContent() {
        assertNull(GlassBackdropStats.from(emptyList()))
        assertNull(GlassBackdropStats.from(List(15) { Color.Transparent }))
        val stats = requireNotNull(GlassBackdropStats.from(List(15) { if (it < 2) Color.White else Color.Black }))
        assertEquals(0f, stats.luminance, 0.001f)
        assertEquals(0f, stats.texture, 0.001f)
    }

    @Test fun pageTextBetweenGridPointsCannotDefeatTheReadableCore() {
        for (dark in listOf(false, true)) {
            val stats = requireNotNull(GlassBackdropStats.from(List(15) { if (dark) Color(0xFF141418) else Color.White }))
            val tokens = resolveGlassTokens(dark, stats = stats, protectUnsampledDetail = true)
            for (missed in listOf(Color.Black, Color.White)) {
                val composite = tokens.tint.compositeOver(missed)
                val lit = Color.White.copy(alpha = tokens.highlight).compositeOver(composite)
                assertTrue(glassContrast(tokens.secondary, composite) >= 4.5f)
                assertTrue(glassContrast(tokens.secondary, lit) >= 4.5f)
            }
        }
    }

    @Test fun accessibilityOverridesZeroTintAndInvalidSettingsAreSanitized() {
        for (dark in listOf(false, true)) {
            val tokens = resolveGlassTokens(dark, GlassPreferences(0f, 0f, true))
            assertTrue(tokens.solid)
            assertEquals(1f, tokens.tint.alpha, 0f)
            assertEquals(0f, tokens.highlight, 0f)
        }
        assertEquals(1f, sanitizeGlassTint(Float.NaN), 0f)
        assertEquals(1f, sanitizeGlassTint(Float.POSITIVE_INFINITY), 0f)
        assertEquals(1.5f, sanitizeGlassTint(2f), 0f)
        assertEquals(0f, sanitizeGlassTint(-1f), 0f)
    }
}
