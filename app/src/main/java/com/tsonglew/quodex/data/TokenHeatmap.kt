package com.tsonglew.quodex.data

import java.math.BigInteger

/** Monday-first weeks, with padding outside the requested service-date range. */
data class TokenHeatmap(val trend: TokenTrend) {
    val leadingDays: Int = trend.startDate.dayOfWeek.value - 1
    val weeks: Int = (leadingDays + trend.days.size + 6) / 7
    fun day(week: Int, weekday: Int): TokenTrendDay? =
        if (week in 0 until weeks && weekday in 0..6) trend.days.getOrNull(week * 7 + weekday - leadingDays) else null

    /** -1 is unreported, 0 is an explicitly reported zero; positive values use four intensity levels. */
    fun level(day: TokenTrendDay): Int {
        val value = day.tokens ?: return -1
        if (value == BigInteger.ZERO) return 0
        val peak = trend.peak.max(BigInteger.ONE)
        return ((value * BigInteger.valueOf(4) + peak - BigInteger.ONE) / peak).toInt().coerceIn(1, 4)
    }
}
