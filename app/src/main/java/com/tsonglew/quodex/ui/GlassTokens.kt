package com.tsonglew.quodex.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.tsonglew.quodex.data.GlassPreferences
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Shared by the live Compose material and the static RemoteViews background. */
@Immutable
internal data class GlassTokens(
    val lightInk: Boolean,
    val tint: Color,
    val edgeAlpha: Float,
    val primary: Color,
    val secondary: Color,
    val saturation: Float,
    val brightness: Float,
    val highlight: Float,
    val outline: Color,
    val solid: Boolean,
) {
    companion object {
        const val BlurDp = 2f
        const val RimDp = 44f
        const val MinLensSideDp = 32f
    }
}

internal data class GlassBackdropStats(
    val luminance: Float,
    val texture: Float,
    val chroma: Float,
    // Keep the extremes for contrast checks even though adaptation trims outliers.
    val samples: List<Color>,
) {
    companion object {
        fun from(samples: List<Color>): GlassBackdropStats? {
            if (samples.size != 15 || samples.any { it.alpha < 0.99f }) return null
            val sorted = samples.sortedBy(::glassLuma)
            val retained = sorted.subList(2, 13)
            return GlassBackdropStats(
                retained.map(::glassLuma).average().toFloat(),
                glassLuma(retained.last()) - glassLuma(retained.first()),
                retained.map { max(it.red, max(it.green, it.blue)) - min(it.red, min(it.green, it.blue)) }
                    .average().toFloat(), samples,
            )
        }
    }
}

internal fun glassLuma(color: Color): Float = 0.299f * color.red + 0.587f * color.green + 0.114f * color.blue

internal fun glassLightInk(dark: Boolean, luminance: Float?, previous: Boolean): Boolean = when {
    dark -> true
    luminance == null -> false
    luminance < 0.45f -> true
    luminance > 0.55f -> false
    else -> previous
}

internal fun glassContrast(foreground: Color, background: Color): Float {
    val ink = foreground.compositeOver(background).luminance()
    val base = background.luminance()
    return (max(ink, base) + 0.05f) / (min(ink, base) + 0.05f)
}

/** Dense/opaque fallback is intentional: a 22% tint cannot guarantee readable secondary text. */
internal fun resolveGlassTokens(
    dark: Boolean,
    preferences: GlassPreferences = GlassPreferences(),
    stats: GlassBackdropStats? = null,
    previousLightInk: Boolean = dark,
    forceSolid: Boolean = false,
    restorePreviousInk: Boolean = false,
    protectUnsampledDetail: Boolean = false,
): GlassTokens {
    val lightInk = if (stats == null && restorePreviousInk) dark || previousLightInk
        else glassLightInk(dark, stats?.luminance, previousLightInk)
    val solid = forceSolid || preferences.reduceTransparency
    val amount = preferences.tint(dark)
    val base = when { dark -> Color(0xFF141418); lightInk -> Color(0xFF101216); else -> Color.White }
    val primary = if (lightInk) Color.White.copy(alpha = 0.94f) else Color.Black.copy(alpha = 0.86f)
    val secondary = if (lightInk) Color(0xFFEBEBF5).copy(alpha = 0.64f) else Color(0xFF3C3C43).copy(alpha = 0.7f)
    val density = stats?.let {
        (0.55f * (1f - abs(it.luminance - if (lightInk) 1f else 0f)) +
            0.7f * it.texture + 0.3f * it.chroma).coerceIn(0f, 1f)
    } ?: 1f
    val thin = when { dark -> 0.16f; lightInk -> 0.10f; else -> 0.06f }
    val dense = when { dark -> 0.38f; lightInk -> 0.30f; else -> 0.22f }
    val edgeAlpha = if (solid) 1f else ((thin + (dense - thin) * density) * amount).coerceIn(0f, 1f)
    var alpha = edgeAlpha
    val brightness = 1f + ((when { dark -> 0.8f; lightInk -> 0.85f; else -> 1.06f }) - 1f) * min(amount, 1f)
    val saturation = (when { dark -> 1.6f; lightInk -> 1.7f; else -> 1.8f }) *
        max(0.45f, 1f - 0.75f * (stats?.chroma ?: 0f))
    val scheme = quodexColorScheme(lightInk)
    val highlight = if (solid) 0f else if (lightInk) 0.10f else 0.30f
    val foregrounds = listOf(primary, secondary, scheme.primary, scheme.error)
    // Unknown wallpaper is checked against both black and white, including at tint = 0.
    val backgrounds = stats?.samples?.map { processedGlassColor(it, saturation, brightness) }
        ?.let { if (protectUnsampledDetail) it + listOf(Color.Black, Color.White) else it }
        ?: listOf(Color.Black, Color.White)
    while (alpha < 1f && backgrounds.any { behind ->
        val composite = base.copy(alpha = alpha).compositeOver(behind)
        val lit = Color.White.copy(alpha = highlight).compositeOver(composite)
        foregrounds.any { glassContrast(it, composite) < 4.5f || glassContrast(it, lit) < 4.5f }
    }) alpha = min(1f, alpha + 0.01f)
    return GlassTokens(lightInk, base.copy(alpha = alpha), edgeAlpha, primary, secondary,
        saturation, brightness, highlight,
        Color.Black.copy(alpha = if (dark || lightInk) 0.28f else 0.06f), solid)
}

internal fun processedGlassColor(color: Color, saturation: Float, brightness: Float): Color {
    val gray = glassLuma(color)
    fun channel(value: Float) = ((gray + (value - gray) * saturation) * brightness).coerceIn(0f, 1f)
    return Color(channel(color.red), channel(color.green), channel(color.blue))
}
