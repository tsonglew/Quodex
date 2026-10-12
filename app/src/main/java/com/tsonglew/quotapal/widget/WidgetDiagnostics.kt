package com.tsonglew.quotapal.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context

/** Report only our widget types and numeric geometry, never arbitrary host extras. */
internal fun widgetDiagnosticReport(context: Context): String = buildString {
    val manager = AppWidgetManager.getInstance(context)
    listOf("standard" to QuotaWidgetReceiver::class.java, "slim-single" to SlimQuotaWidgetReceiver::class.java)
        .forEach { (kind, receiver) ->
            val ids = manager.getAppWidgetIds(ComponentName(context, receiver))
            append("widgets $kind count=${ids.size}\n")
            ids.forEachIndexed { index, id ->
                val options = manager.getAppWidgetOptions(id)
                append("  instance=$index min=${options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)}x${options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)}")
                append(" max=${options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)}x${options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)}\n")
            }
        }
}
