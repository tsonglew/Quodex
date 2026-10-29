package com.tsonglew.quodex.launcher

import com.tsonglew.quodex.i18n.AndroidTextResources
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.tsonglew.quodex.R

/** Keep the logo and text in the central safe area of an adaptive launcher icon. */
object QuotaIconRenderer {
    fun render(context: Context, content: DesktopIconContent, size: Int = 432): Bitmap {
        require(size > 0)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(size / 108f, size / 108f)
        val background = context.getColor(R.color.quodex_icon_background)
        canvas.drawColor(background)
        val logo = requireNotNull(context.getDrawable(
            if (content.showPercentage) R.drawable.ic_quodex else R.drawable.ic_quodex_foreground)).mutate()
        if (!content.showPercentage) {
            logo.setBounds(0, 0, 108, 108)
            logo.draw(canvas)
            return bitmap
        }
        logo.setBounds(30, 21, 78, 69)
        logo.draw(canvas)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        paint.color = Color.rgb(175, 197, 198)
        paint.textSize = 8f
        canvas.drawText(content.marker(AndroidTextResources(context)), 54f, 29f, paint)
        paint.color = when {
            content.status == DesktopIconStatus.CACHED -> Color.rgb(255, 194, 91)
            content.status == DesktopIconStatus.RESTRICTED || (content.percent ?: 100) <= 10 -> Color.rgb(255, 139, 139)
            content.percent == null -> Color.rgb(58, 65, 74)
            else -> context.getColor(R.color.quodex_icon_foreground)
        }
        canvas.drawRoundRect(RectF(25f, 57f, 83f, 80f), 11.5f, 11.5f, paint)
        paint.color = if (content.percent == null) Color.WHITE else background
        paint.textSize = 22f
        val text = content.percentageLabel
        val width = paint.measureText(text)
        if (width > 48f) paint.textSize *= 48f / width
        val baseline = 68.5f - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2
        canvas.drawText(text, 54f, baseline, paint)
        return bitmap
    }
}
