package com.tsonglew.quodex.data

data class GlassPreferences(
    val lightTint: Float = 1f,
    val darkTint: Float = 1f,
    val reduceTransparency: Boolean = false,
) {
    fun tint(dark: Boolean): Float = sanitizeGlassTint(if (dark) darkTint else lightTint)
}

internal fun sanitizeGlassTint(value: Float): Float =
    if (value.isFinite()) value.coerceIn(0f, 1.5f) else 1f
