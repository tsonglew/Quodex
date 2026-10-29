package com.tsonglew.quodex.launcher

import com.tsonglew.quodex.i18n.*
import com.tsonglew.quodex.R
import com.tsonglew.quodex.data.displayName
import com.tsonglew.quodex.data.AppState
import com.tsonglew.quodex.data.PreferencesState
import java.time.Instant
import kotlin.math.roundToInt

enum class DesktopIconStatus { CURRENT, CACHED, DEMO, UNAVAILABLE, RESTRICTED, DISCONNECTED }

data class DesktopIconContent(
    val percent: Int?,
    val period: String,
    val status: DesktopIconStatus,
    val description: String,
    val showPercentage: Boolean = true,
) {
    val percentageLabel: String get() = percent?.let { "$it%" } ?: "—"
    fun marker(texts: TextResources): String = when (status) {
        DesktopIconStatus.CURRENT -> period
        DesktopIconStatus.CACHED -> texts(R.string.cached)
        DesktopIconStatus.DEMO -> texts(R.string.demo)
        DesktopIconStatus.UNAVAILABLE -> texts(R.string.n_a)
        DesktopIconStatus.RESTRICTED -> texts(R.string.restricted)
        DesktopIconStatus.DISCONNECTED -> texts(R.string.icon_disconnected)
    }
}

/** Only main Codex windows are eligible; an absent window or usage never means 100%. */
fun desktopIconContent(
    state: AppState,
    preferences: PreferencesState,
    texts: TextResources,
    now: Long = Instant.now().epochSecond,
): DesktopIconContent {
    if (!preferences.desktopIconEnabled) {
        return DesktopIconContent(null, "", DesktopIconStatus.UNAVAILABLE, "Quodex", showPercentage = false)
    }
    val snapshot = state.snapshot
    if (snapshot == null || (!state.connected && !state.demo)) {
        return DesktopIconContent(null, "", DesktopIconStatus.DISCONNECTED, texts(R.string.quodex_no_account_connected))
    }
    val mainWindows = snapshot.windows.filter { it.id.startsWith("codex:") || it.id.startsWith("demo:") }
    val weekly = preferences.desktopIconWindow == "weekly"
    val window = if (weekly) mainWindows.firstOrNull { it.durationSeconds == 604800L }
    else mainWindows.filter { (it.durationSeconds ?: 0) in 1L until 86400L }.minByOrNull { it.durationSeconds!! }
    val name = window?.displayName(texts) ?: if (weekly) texts(R.string.weekly_quota) else texts(R.string.short_period_quota)
    val period = if (weekly) texts(R.string.wk) else window?.durationSeconds?.let {
        if (it % 3600L == 0L) "${it / 3600}h" else texts(R.string.short_period)
    } ?: texts(R.string.short_period)
    if (snapshot.allowed == false || snapshot.limitReached == true) {
        return DesktopIconContent(null, period, DesktopIconStatus.RESTRICTED, texts(R.string.quodex_usage_restricted))
    }
    val percent = window?.usedPercent?.takeIf { it.isFinite() && it in 0.0..100.0 }
        ?.let { (100 - it).roundToInt() }
    val status = when {
        state.demo -> DesktopIconStatus.DEMO
        percent == null -> DesktopIconStatus.UNAVAILABLE
        state.failure != null || now - snapshot.fetchedAt > preferences.refreshMinutes * 120 ||
            window?.resetsAt?.let { it <= now } == true -> DesktopIconStatus.CACHED
        else -> DesktopIconStatus.CURRENT
    }
    val description = buildString {
        append("Quodex · ")
        if (state.demo) append(texts(R.string.demo_239))
        append(if (percent == null) texts(R.string.value_unavailable, name) else texts(R.string.value_remaining_value, name, percent))
        if (status == DesktopIconStatus.CACHED) append(texts(R.string.cached_data_please_refresh))
    }
    return DesktopIconContent(percent, period, status, description)
}
