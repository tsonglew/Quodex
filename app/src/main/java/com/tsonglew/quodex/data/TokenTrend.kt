package com.tsonglew.quodex.data

import java.math.BigInteger
import java.text.NumberFormat
import java.time.DateTimeException
import java.time.LocalDate
import java.util.Locale

data class TokenTrendDay(val date: LocalDate, val tokens: BigInteger?)

data class TokenTrend(val days: List<TokenTrendDay>) {
    val knownDays: Int get() = days.count { it.tokens != null }
    val total: BigInteger get() = days.mapNotNull { it.tokens }.fold(BigInteger.ZERO, BigInteger::add)
    val peak: BigInteger get() = days.mapNotNull { it.tokens }.maxOrNull() ?: BigInteger.ZERO
    val startDate: LocalDate get() = days.first().date
    val endDate: LocalDate get() = days.last().date
}

/** Date labels belong to the service; do not turn them into local-midnight timestamps. */
fun tokenTrend(buckets: List<DailyTokenUsage>?, days: Int): TokenTrend? {
    require(days in 1..366)
    val byDate = mutableMapOf<LocalDate, BigInteger>()
    buckets.orEmpty().forEach { bucket ->
        val date = try { LocalDate.parse(bucket.date) } catch (_: DateTimeException) { return@forEach }
        if (bucket.tokens < 0) return@forEach
        byDate[date] = (byDate[date] ?: BigInteger.ZERO) + BigInteger.valueOf(bucket.tokens)
    }
    val end = byDate.keys.maxOrNull() ?: return null
    val start = try { end.minusDays(days - 1L) } catch (_: DateTimeException) { return null }
    return TokenTrend(List(days) { offset ->
        val date = start.plusDays(offset.toLong())
        TokenTrendDay(date, byDate[date])
    })
}

fun exactTokenCount(tokens: BigInteger): String = NumberFormat.getIntegerInstance(Locale.US).format(tokens)

/** Sample data never enters the repository or account cache. */
fun demoTokenUsage(): TokenUsageSnapshot {
    val end = LocalDate.of(2026, 10, 22)
    val counts = listOf(8000L, 45000L, 19000L, 0L, 62000L, 28000L, 94000L)
    return TokenUsageSnapshot(
        fetchedAt = 0,
        lifetimeTokens = 4_128_640,
        peakDailyTokens = 184_000,
        dailyBuckets = List(30) { index ->
            DailyTokenUsage(end.minusDays(29L - index).toString(), counts[index % counts.size] + if (index < 23) 3000L else 0L)
        },
        statsAsOf = end.toString(),
    )
}
