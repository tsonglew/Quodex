package com.tsonglew.quodex.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.tsonglew.quodex.quotaApp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Reformat cached timestamps after system time changes without fetching new quota. */
class WidgetTimeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) return
        val pending = goAsync()
        val app = context.quotaApp
        app.wallClockChanged()
        app.scope.launch {
            try {
                withTimeoutOrNull(8_000) { app.repository.initialize(); app.updateWidgets() }
            } catch (_: Exception) {
                Log.w("QuotaWidget", "Could not update cached widget time")
            } finally {
                pending.finish()
            }
        }
    }
}
