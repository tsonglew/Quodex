package com.tsonglew.quodex.data

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class TokenUsageParserTest {
    private fun parse(raw: String) = TokenUsageParser.parse(raw, 1000)

    @Test fun decodesLargeCountsAndOnlyPersistsAggregateFields() {
        val result = parse("""{
            "profile":{"display_name":"private-name","username":"private-user"},
            "metadata":{"stats_as_of":"2026-09-09"},
            "stats":{"lifetime_tokens":142300000000,"peak_daily_tokens":7000000000,
                "daily_usage_buckets":[{"start_date":"2026-09-09","tokens":3000000000}],
                "top_invocations":[{"skill_name":"private-skill"}],"future_field":true}}
        """)
        assertEquals(1000L, result.fetchedAt)
        assertEquals(142300000000L, result.lifetimeTokens)
        assertEquals(7000000000L, result.peakDailyTokens)
        assertEquals(listOf(DailyTokenUsage("2026-09-09", 3000000000L)), result.dailyBuckets)
        assertEquals("2026-09-09", result.statsAsOf)
        assertFalse(result.partial)
        val stored = AppJson.encodeToString(result)
        assertEquals(result, AppJson.decodeFromString<TokenUsageSnapshot>(stored))
        listOf("private-name", "private-user", "private-skill", "top_invocations").forEach { assertFalse(stored.contains(it)) }
    }

    @Test fun missingNullEmptyAndZeroRemainDistinct() {
        for (stats in listOf("{}", """{"lifetime_tokens":null,"peak_daily_tokens":null,"daily_usage_buckets":null}""")) {
            val result = parse("""{"stats":$stats}""")
            assertNull(result.lifetimeTokens)
            assertNull(result.peakDailyTokens)
            assertNull(result.dailyBuckets)
            assertFalse(result.partial)
        }
        val empty = parse("""{"stats":{"lifetime_tokens":0,"peak_daily_tokens":0,"daily_usage_buckets":[]}}""")
        assertEquals(0L, empty.lifetimeTokens)
        assertEquals(0L, empty.peakDailyTokens)
        assertEquals(emptyList<DailyTokenUsage>(), empty.dailyBuckets)
        assertFalse(empty.partial)
        val zero = parse("""{"stats":{"daily_usage_buckets":[{"start_date":"2026-09-09","tokens":0}]}}""")
        assertEquals(listOf(DailyTokenUsage("2026-09-09", 0)), zero.dailyBuckets)
    }

    @Test fun malformedRootOrStatsCannotBecomeAnEmptySuccess() {
        for (raw in listOf("{}", "[]", "null", "not-json", """{"stats":null}""", """{"stats":[]}""", """{"stats":"private"}""")) {
            try { parse(raw); fail("Expected protocol failure") }
            catch (failure: ApiFailure) { assertEquals(FailureKind.PROTOCOL, failure.kind) }
        }
    }

    @Test fun invalidSummaryCountsAreUnknownAndMarkPartial() {
        for (value in listOf("-1", "1.5", "\"42\"", "true", "{}", "9223372036854775808")) {
            val result = parse("""{"stats":{"lifetime_tokens":$value,"peak_daily_tokens":5}}""")
            assertNull(result.lifetimeTokens)
            assertEquals(5L, result.peakDailyTokens)
            assertTrue(result.partial)
        }
    }

    @Test fun invalidBucketsAreOmittedWithoutInventingZeroCounts() {
        val result = parse("""{"stats":{"daily_usage_buckets":[
            null, "bad", {"start_date":"2026-02-30","tokens":5},
            {"start_date":"2026-9-9","tokens":5}, {"start_date":"2026-09-09T00:00:00Z","tokens":5},
            {"start_date":"2026-09-08","tokens":-2}, {"start_date":"2026-09-07","tokens":1.5},
            {"start_date":"2026-09-06","tokens":"9"}, {"start_date":"2026-09-05"},
            {"tokens":7}, {"start_date":"2026-09-10","tokens":12}
        ]}}""")
        assertTrue(result.partial)
        assertEquals(listOf(DailyTokenUsage("2026-09-10", 12)), result.dailyBuckets)
        assertNull(parse("""{"stats":{"daily_usage_buckets":[{"start_date":"2026-09-09","tokens":-1}]}}""").dailyBuckets)
        val wrongType = parse("""{"stats":{"daily_usage_buckets":{}}}""")
        assertTrue(wrongType.partial)
        assertNull(wrongType.dailyBuckets)
    }

    @Test fun duplicateDatesAreCombinedAndSortedWithoutSummingSnapshots() {
        val result = parse("""{"stats":{"daily_usage_buckets":[
            {"start_date":"2026-09-10","tokens":7}, {"start_date":"2026-09-09","tokens":2},
            {"start_date":"2026-09-10","tokens":3}
        ]}}""")
        assertEquals(listOf(DailyTokenUsage("2026-09-09", 2), DailyTokenUsage("2026-09-10", 10)), result.dailyBuckets)
        assertFalse(result.partial)
    }

    @Test fun overflowOrInvalidDuplicateMakesThatDateUnknown() {
        val result = parse("""{"stats":{"daily_usage_buckets":[
            {"start_date":"2026-09-09","tokens":9223372036854775807},
            {"start_date":"2026-09-09","tokens":1}, {"start_date":"2026-09-09","tokens":2},
            {"start_date":"2026-09-10","tokens":8}, {"start_date":"2026-09-10","tokens":-1},
            {"start_date":"2026-09-11","tokens":9}
        ]}}""")
        assertEquals(listOf(DailyTokenUsage("2026-09-11", 9)), result.dailyBuckets)
        assertTrue(result.partial)
    }

    @Test fun serverPartialErrorIsFlaggedWithoutPersistingItsText() {
        val result = parse("""{"metadata":{"stats_error":"private upstream failure"},"stats":{"lifetime_tokens":12}}""")
        assertEquals(12L, result.lifetimeTokens)
        assertTrue(result.partial)
        assertFalse(AppJson.encodeToString(result).contains("private"))
        for (error in listOf("null", "\"\"")) assertFalse(parse("""{"metadata":{"stats_error":$error},"stats":{}}""").partial)
    }

    @Test fun asOfAcceptsOnlyDatesOrOffsetTimestampsWithoutTimezoneConversion() {
        for (date in listOf("2026-09-09", "2026-09-09T00:30:00Z", "2026-09-09T08:30:00+08:00")) {
            val result = parse("""{"metadata":{"stats_as_of":"$date"},"stats":{}}""")
            assertEquals(date, result.statsAsOf)
            assertFalse(result.partial)
        }
        for (date in listOf("2026-02-30", "private upstream text", "2026-09-09T08:30:00")) {
            val result = parse("""{"metadata":{"stats_as_of":"$date"},"stats":{}}""")
            assertNull(result.statsAsOf)
            assertTrue(result.partial)
        }
    }
}
