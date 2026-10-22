package com.tsonglew.quotapal.data

import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.time.LocalDate
import java.util.TimeZone

class TokenTrendTest {
    @Test fun sevenDayWindowEndsOnLastServiceDateAndExcludesOlderBuckets() {
        val trend = tokenTrend(listOf(
            DailyTokenUsage("2026-10-15", 999), DailyTokenUsage("2026-10-16", 10),
            DailyTokenUsage("2026-10-22", 20), DailyTokenUsage("2026-10-20", 5),
        ), 7)!!
        assertEquals(LocalDate.of(2026, 10, 16), trend.startDate)
        assertEquals(LocalDate.of(2026, 10, 22), trend.endDate)
        assertEquals(7, trend.days.size)
        assertEquals(BigInteger.valueOf(35), trend.total)
        assertEquals(3, trend.knownDays)
    }

    @Test fun thirtyDayWindowPreservesCalendarBoundariesAndMissingDays() {
        val trend = tokenTrend(listOf(DailyTokenUsage("2026-03-01", 5)), 30)!!
        assertEquals(LocalDate.of(2026, 1, 31), trend.startDate)
        assertEquals(30, trend.days.size)
        assertEquals(1, trend.knownDays)
        assertTrue(trend.days.dropLast(1).all { it.tokens == null })
    }

    @Test fun zeroIsAReportedValueButMissingDateIsUnknown() {
        val trend = tokenTrend(listOf(DailyTokenUsage("2026-10-22", 0)), 7)!!
        assertNull(trend.days.first().tokens)
        assertEquals(BigInteger.ZERO, trend.days.last().tokens)
        assertEquals(1, trend.knownDays)
        assertEquals(BigInteger.ZERO, trend.total)
    }

    @Test fun largeCountsAndDuplicateDateAggregationDoNotOverflow() {
        val trend = tokenTrend(listOf(
            DailyTokenUsage("2026-10-21", Long.MAX_VALUE),
            DailyTokenUsage("2026-10-22", Long.MAX_VALUE),
            DailyTokenUsage("2026-10-22", Long.MAX_VALUE),
        ), 7)!!
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(3)), trend.total)
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO), trend.peak)
        assertEquals("27,670,116,110,564,327,421", exactTokenCount(trend.total))
    }

    @Test fun serviceDatesAreIndependentOfPhoneTimezone() {
        val original = TimeZone.getDefault()
        try {
            val input = listOf(DailyTokenUsage("2026-01-01", 123))
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"))
            val east = tokenTrend(input, 7)
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"))
            assertEquals(east, tokenTrend(input, 7))
            assertEquals(LocalDate.of(2026, 1, 1), east!!.endDate)
        } finally { TimeZone.setDefault(original) }
    }

    @Test fun absentOrInvalidDataDoesNotInventAnEmptyUsagePeriod() {
        assertNull(tokenTrend(null, 7))
        assertNull(tokenTrend(emptyList(), 30))
        assertNull(tokenTrend(listOf(DailyTokenUsage("invalid", 7), DailyTokenUsage("2026-10-22", -1)), 7))
    }

    @Test fun demoIsDeterministicAndHasNoAccountIdentifier() {
        assertEquals(demoTokenUsage(), demoTokenUsage())
        assertEquals(30, demoTokenUsage().dailyBuckets!!.size)
        assertEquals(30, tokenTrend(demoTokenUsage().dailyBuckets, 30)!!.knownDays)
    }
}
