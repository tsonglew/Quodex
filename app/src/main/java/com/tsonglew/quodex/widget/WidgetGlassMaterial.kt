package com.tsonglew.quodex.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.LruCache
import androidx.compose.ui.graphics.toArgb
import com.tsonglew.quodex.ui.GlassTokens
import kotlin.math.min

/** Static launcher-safe material. It does not capture wallpaper or run a backdrop shader. */
internal object WidgetGlassMaterial {
    private data class Key(val width: Int, val height: Int, val density: Float, val radiusDp: Float,
        val tint: Int, val highlight: Float, val outline: Int)
    private val cache = object : LruCache<Key, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: Key, value: Bitmap) = value.allocationByteCount
    }

    @Synchronized
    fun bitmap(widthDp: Float, heightDp: Float, density: Float, radiusDp: Float, tokens: GlassTokens): Bitmap {
        // Bound bitmap memory even for large launcher spans; no per-frame widget updates.
        val scale = min(density, 1024f / maxOf(widthDp, heightDp, 1f))
        val key = Key((widthDp * scale).toInt().coerceAtLeast(1), (heightDp * scale).toInt().coerceAtLeast(1),
            scale, radiusDp, tokens.tint.toArgb(), tokens.highlight, tokens.outline.toArgb())
        cache[key]?.let { return it }
        val bitmap = Bitmap.createBitmap(key.width, key.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val bounds = RectF(0f, 0f, key.width.toFloat(), key.height.toFloat())
        val radius = min(radiusDp * scale, min(bounds.width(), bounds.height()) / 2)
        paint.color = key.tint
        canvas.drawRoundRect(bounds, radius, radius, paint)
        if (!tokens.solid) {
            paint.color = AndroidColor.WHITE
            paint.shader = RadialGradient(0f, 0f, maxOf(bounds.width(), bounds.height()) * 0.55f,
                AndroidColor.argb((tokens.highlight * 255).toInt(), 255, 255, 255), AndroidColor.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(bounds, radius, radius, paint)
            paint.shader = LinearGradient(0f, 0f, bounds.width(), bounds.height(),
                intArrayOf(AndroidColor.argb((tokens.highlight * 2.5f * 255).toInt(), 255, 255, 255),
                    AndroidColor.argb((tokens.highlight * 0.35f * 255).toInt(), 255, 255, 255), key.outline),
                null, Shader.TileMode.CLAMP)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f * scale
            bounds.inset(paint.strokeWidth / 2, paint.strokeWidth / 2)
            canvas.drawRoundRect(bounds, maxOf(0f, radius - paint.strokeWidth / 2), maxOf(0f, radius - paint.strokeWidth / 2), paint)
        }
        cache.put(key, bitmap)
        return bitmap
    }
}
