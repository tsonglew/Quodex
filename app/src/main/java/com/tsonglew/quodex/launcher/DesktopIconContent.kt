package com.tsonglew.quodex.launcher

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
    val marker: String get() = when (status) {
        DesktopIconStatus.CURRENT -> period
        DesktopIconStatus.CACHED -> "旧数据"
        DesktopIconStatus.DEMO -> "示例"
        DesktopIconStatus.UNAVAILABLE -> "暂无"
        DesktopIconStatus.RESTRICTED -> "受限"
        DesktopIconStatus.DISCONNECTED -> "未连接"
    }
}

/** Only main Codex windows are eligible; an absent window or usage never means 100%. */
fun desktopIconContent(
    state: AppState,
    preferences: PreferencesState,
    now: Long = Instant.now().epochSecond,
): DesktopIconContent {
    if (!preferences.desktopIconEnabled) {
        return DesktopIconContent(null, "", DesktopIconStatus.UNAVAILABLE, "Quodex", showPercentage = false)
    }
    val snapshot = state.snapshot
    if (snapshot == null || (!state.connected && !state.demo)) {
        return DesktopIconContent(null, "", DesktopIconStatus.DISCONNECTED, "Quodex · 尚未连接账号")
    }
    val mainWindows = snapshot.windows.filter { it.id.startsWith("codex:") || it.id.startsWith("demo:") }
    val weekly = preferences.desktopIconWindow == "weekly"
    val window = if (weekly) mainWindows.firstOrNull { it.durationSeconds == 604800L }
    else mainWindows.filter { (it.durationSeconds ?: 0) in 1L until 86400L }.minByOrNull { it.durationSeconds!! }
    val name = window?.name ?: if (weekly) "每周额度" else "短周期额度"
    val period = if (weekly) "周" else window?.durationSeconds?.let {
        if (it % 3600L == 0L) "${it / 3600}h" else "短周期"
    } ?: "短周期"
    if (snapshot.allowed == false || snapshot.limitReached == true) {
        return DesktopIconContent(null, period, DesktopIconStatus.RESTRICTED, "Quodex · 当前使用受限")
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
        if (state.demo) append("示例 · ")
        append(if (percent == null) "$name 暂不可用" else "$name 剩余 $percent%")
        if (status == DesktopIconStatus.CACHED) append(" · 上次数据，请刷新")
    }
    return DesktopIconContent(percent, period, status, description)
}
