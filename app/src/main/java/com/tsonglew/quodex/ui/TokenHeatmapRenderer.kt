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

    data class Geometry(val left: Float, val top: Float, val cell: Float, val gap: Float) {
        fun bounds(week: Int, row: Int) = RectF(left + week * (cell + gap), top + row * (cell + gap),
            left + week * (cell + gap) + cell, top + row * (cell + gap) + cell)
    }

    fun geometry(map: TokenHeatmap, width: Float, height: Float): Geometry {
        val label = min(width * .2f, height * .19f)
        val top = height * .12f
        val gap = height * .025f
        val cell = min((width - label - gap * map.weeks) / map.weeks, (height - top - gap * 6) / 7).coerceAtLeast(1f)
        val plot = cell * map.weeks + gap * (map.weeks - 1)
        return Geometry(label + (width - label - plot) / 2, top, cell, gap)
    }

    fun hit(map: TokenHeatmap, width: Float, height: Float, x: Float, y: Float): TokenTrendDay? {
        val g = geometry(map, width, height)
        for (week in 0 until map.weeks) for (row in 0..6) {
            if (g.bounds(week, row).contains(x, y)) return map.day(week, row)
        }
        return null
    }

    fun render(map: TokenHeatmap, width: Int, height: Int, dark: Boolean, locale: Locale,
        selected: String? = null): Bitmap {
        val bitmap = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val palette = colors(dark)
        val muted = if (dark) 0xffb7c4c7.toInt() else 0xff52605a.toInt()
        val g = geometry(map, width.toFloat(), height.toFloat())
        val labelSize = min(g.cell * .65f, height * .06f)
        paint.textSize = labelSize
        paint.color = muted
        paint.textAlign = Paint.Align.RIGHT
        for (row in listOf(0, 2, 4, 6)) {
            val bounds = g.bounds(0, row)
            canvas.drawText(DayOfWeek.of(row + 1).getDisplayName(TextStyle.SHORT, locale),
                g.left - g.gap * 1.8f, bounds.centerY() - (paint.ascent() + paint.descent()) / 2, paint)
        }
        paint.textAlign = Paint.Align.CENTER
        val dateFormat = DateTimeFormatter.ofPattern("M/d", locale)
        var previousLabelRight = Float.NEGATIVE_INFINITY
        for (week in 0 until map.weeks) {
            val first = (0..6).firstNotNullOfOrNull { map.day(week, it) } ?: continue
            paint.style = Paint.Style.FILL
            paint.color = muted
            // Skip crowded ticks in short 5–6 column heatmaps, including widgets.
            val label = first.date.format(dateFormat)
            val center = g.bounds(week, 0).centerX()
            val halfWidth = paint.measureText(label) / 2
            if (center - halfWidth >= previousLabelRight + labelSize * .7f) {
                canvas.drawText(label, center, g.top - g.gap, paint)
                previousLabelRight = center + halfWidth
            }
            for (row in 0..6) {
                val day = map.day(week, row) ?: continue
                val level = map.level(day)
                val bounds = g.bounds(week, row)
                val radius = g.cell * .18f
                paint.color = if (level < 0) muted else palette[level]
                paint.style = if (level < 0) Paint.Style.STROKE else Paint.Style.FILL
                paint.strokeWidth = (g.cell * .045f).coerceAtLeast(1f)
                canvas.drawRoundRect(bounds, radius, radius, paint)
                if (level < 0) canvas.drawLine(bounds.left + g.cell * .32f, bounds.centerY(), bounds.right - g.cell * .32f, bounds.centerY(), paint)
                if (day.date.toString() == selected) {
                    paint.color = muted
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = (g.cell * .07f).coerceAtLeast(1.5f)
                    val ring = RectF(bounds).apply { inset(-g.gap * .3f, -g.gap * .3f) }
                    canvas.drawRoundRect(ring, radius, radius, paint)
                }
            }
        }
        return bitmap
    }
}
