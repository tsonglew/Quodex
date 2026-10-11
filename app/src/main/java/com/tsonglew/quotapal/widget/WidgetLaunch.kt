package com.tsonglew.quotapal.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.tsonglew.quotapal.MainActivity

internal const val EXTRA_WIDGET_LAUNCH = "com.tsonglew.quotapal.WIDGET_LAUNCH"

/** Use the same main entry point as the app icon, with an explicit task boundary. */
internal fun widgetLaunchIntent(context: Context): Intent =
    Intent.makeMainActivity(ComponentName(context, MainActivity::class.java))
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra(EXTRA_WIDGET_LAUNCH, true)
