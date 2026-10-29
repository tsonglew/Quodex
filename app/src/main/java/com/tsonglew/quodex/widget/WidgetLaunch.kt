package com.tsonglew.quodex.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.tsonglew.quodex.MainActivity

internal const val EXTRA_WIDGET_LAUNCH = "com.tsonglew.quodex.WIDGET_LAUNCH"
internal const val EXTRA_TOKEN_HEATMAP_LAUNCH = "com.tsonglew.quodex.TOKEN_HEATMAP_LAUNCH"

/** Use the same main entry point as the app icon, with an explicit task boundary. */
internal fun widgetLaunchIntent(context: Context): Intent =
    Intent.makeMainActivity(ComponentName(context, MainActivity::class.java))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra(EXTRA_WIDGET_LAUNCH, true)
