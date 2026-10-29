package com.tsonglew.quodex.data

import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.time.LocalDate

class TokenHeatmapTest {
    @Test fun calendarColumnsPreserveExactlyThirtyDatesAcrossLeapDay() {
        val trend = tokenTrend(listOf(DailyTokenUsage("2024-03-10", 10)), 30)!!
        val map = TokenHeatmap(trend)
        val cells = (0 until map.weeks).flatMap { week -> (0..6).mapNotNull { map.day(week, it) } }
        assertEquals(trend.days, cells)
        assertEquals(30, cells.map { it.date }.toSet().size)
        assertTrue(cells.any { it.date == LocalDate.of(2024, 2, 29) })
        for (week in 0 until map.weeks) for (row in 0..6) map.day(week, row)?.let {
            assertEquals(row + 1, it.date.dayOfWeek.value)
        }
    }

    @Test fun partialFirstAndLastWeeksArePaddingRatherThanMissingData() {
        val map = TokenHeatmap(tokenTrend(listOf(DailyTokenUsage("2026-10-22", 0)), 30)!!)
        assertNull(map.day(0, 0)) // Monday lies outside the 30-date period.
        assertNull(map.day(map.weeks - 1, 6))
        assertEquals(-1, map.level(map.day(0, 2)!!)) // Wednesday is in range but not reported.
        assertEquals(0, map.level(map.trend.days.last()))
    }

    @Test fun intensityUsesFourPositiveBucketsAndKeepsZeroSeparate() {
        val days = listOf(0L, 1, 25, 26, 50, 51, 75, 76, 100).mapIndexed { i, value ->
            TokenTrendDay(LocalDate.of(2026, 10, 1).plusDays(i.toLong()), BigInteger.valueOf(value))
        }
        val map = TokenHeatmap(TokenTrend(days))
        assertEquals(listOf(0, 1, 1, 2, 2, 3, 3, 4, 4), days.map(map::level))
    }

    @Test fun hugeCountsAreNotRoundedToFloatsOrOverflowed() {
        val peak = BigInteger.valueOf(Long.MAX_VALUE).pow(2)
        val date = LocalDate.of(2026, 1, 1)
        val days = listOf(TokenTrendDay(date, BigInteger.ONE), TokenTrendDay(date.plusDays(1), peak))
        val map = TokenHeatmap(TokenTrend(days))
        assertEquals(1, map.level(days[0]))
        assertEquals(4, map.level(days[1]))
    }

    @Test fun allZeroPeriodHasNoInventedPositiveSquares() {
        val trend = tokenTrend(listOf(DailyTokenUsage("2026-01-01", 0), DailyTokenUsage("2026-01-30", 0)), 30)!!
        val map = TokenHeatmap(trend)
        assertEquals(2, trend.days.count { map.level(it) == 0 })
        assertEquals(28, trend.days.count { map.level(it) == -1 })
    }

    @Test fun weekCountCoversEveryPossibleStartWeekdayWithoutClipping() {
        for (offset in 0L..6L) {
            val end = LocalDate.of(2026, 10, 22).plusDays(offset)
            val map = TokenHeatmap(tokenTrend(listOf(DailyTokenUsage(end.toString(), 7)), 30)!!)
            assertTrue(map.weeks in 5..6)
            assertEquals(end, (0 until map.weeks).flatMap { week -> (0..6).mapNotNull { map.day(week, it)?.date } }.last())
        }
    }
}
