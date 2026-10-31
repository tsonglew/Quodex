package com.tsonglew.quodex.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.tsonglew.quodex.data.TokenHeatmap
import com.tsonglew.quodex.data.TokenTrendDay
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.min

/** Shared by Compose and RemoteViews so the same date always gets the same visual treatment. */
internal object TokenHeatmapRenderer {
    fun colors(dark: Boolean): IntArray = if (dark) intArrayOf(
        0xff293139.toInt(), 0xff0e4429.toInt(), 0xff006d32.toInt(), 0xff26a641.toInt(), 0xff39d353.toInt()
    ) else intArrayOf(0xffe6ebef.toInt(), 0xff9be9a8.toInt(), 0xff40c463.toInt(), 0xff30a14e.toInt(), 0xff216e39.toInt())

    data class Geometry(val left: Float, val top: Float, val cellWidth: Float, val cellHeight: Float, val gap: Float) {
        fun bounds(week: Int, weekday: Int) = RectF(
            left + weekday * (cellWidth + gap), top + week * (cellHeight + gap),
            left + weekday * (cellWidth + gap) + cellWidth, top + week * (cellHeight + gap) + cellHeight)
    }

    fun geometry(map: TokenHeatmap, width: Float, height: Float, showLabels: Boolean = true): Geometry {
        val gap = min(width * .012f, height / (map.weeks * 5 + 2))
        val left = if (showLabels) width * .12f else gap
        val top = if (showLabels) min(width * .08f, height * .4f) else gap
        // Seven weekday columns use the available width even in a short 2×2 widget.
        // Rectangular cells avoid shrinking the entire chart to fit its height.
        return Geometry(left, top, ((width - left - gap * 7) / 7).coerceAtLeast(1f),
            ((height - top - gap * map.weeks) / map.weeks).coerceAtLeast(1f), gap)
    }

    fun hit(map: TokenHeatmap, width: Float, height: Float, x: Float, y: Float): TokenTrendDay? {
        val g = geometry(map, width, height)
        for (week in 0 until map.weeks) for (row in 0..6) {
            if (g.bounds(week, row).contains(x, y)) return map.day(week, row)
        }
        return null
    }

    fun render(map: TokenHeatmap, width: Int, height: Int, dark: Boolean, locale: Locale,
        selected: String? = null, showLabels: Boolean = true): Bitmap {
        val bitmap = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val palette = colors(dark)
        val muted = if (dark) 0xffb7c4c7.toInt() else 0xff52605a.toInt()
        val g = geometry(map, width.toFloat(), height.toFloat(), showLabels)
        val cell = min(g.cellWidth, g.cellHeight)
        val labelSize = min(g.cellWidth * .4f, min(g.cellHeight * .55f, g.top * .65f))
        if (showLabels) {
            paint.textSize = labelSize
            paint.color = muted
            paint.textAlign = Paint.Align.CENTER
            for (weekday in 0..6) {
                val label = DayOfWeek.of(weekday + 1).getDisplayName(TextStyle.SHORT, locale)
                paint.textSize = labelSize
                paint.textSize *= min(1f, g.cellWidth / paint.measureText(label))
                canvas.drawText(label, g.bounds(0, weekday).centerX(),
                    (g.top - g.gap) / 2 - (paint.ascent() + paint.descent()) / 2, paint)
            }
        }
        val dateFormat = DateTimeFormatter.ofPattern("M/d", locale)
        for (week in 0 until map.weeks) {
            val first = (0..6).firstNotNullOfOrNull { map.day(week, it) } ?: continue
            paint.style = Paint.Style.FILL
            paint.color = muted
            if (showLabels) {
                val label = first.date.format(dateFormat)
                paint.textAlign = Paint.Align.RIGHT
                paint.textSize = labelSize
                paint.textSize *= min(1f, (g.left - g.gap * 2) / paint.measureText(label))
                canvas.drawText(label, g.left - g.gap * 1.5f,
                    g.bounds(week, 0).centerY() - (paint.ascent() + paint.descent()) / 2, paint)
            }
            for (row in 0..6) {
                val day = map.day(week, row) ?: continue
                val level = map.level(day)
                val bounds = g.bounds(week, row)
                val radius = cell * .18f
                paint.color = if (level < 0) muted else palette[level]
                paint.style = if (level < 0) Paint.Style.STROKE else Paint.Style.FILL
                paint.strokeWidth = (cell * .045f).coerceAtLeast(1f)
                canvas.drawRoundRect(bounds, radius, radius, paint)
                if (level < 0) canvas.drawLine(bounds.left + g.cellWidth * .32f, bounds.centerY(), bounds.right - g.cellWidth * .32f, bounds.centerY(), paint)
                if (day.date.toString() == selected) {
                    paint.color = muted
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = (cell * .07f).coerceAtLeast(1.5f)
                    val ring = RectF(bounds).apply { inset(-g.gap * .3f, -g.gap * .3f) }
                    canvas.drawRoundRect(ring, radius, radius, paint)
                }
            }
        }
        return bitmap
    }
}
