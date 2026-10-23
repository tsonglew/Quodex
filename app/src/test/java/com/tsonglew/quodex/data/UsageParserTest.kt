package com.tsonglew.quodex.data

import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class UsageParserTest {
    private fun parse(body: String) = UsageParser.parse(body, "test-account", 1000)
    @Test fun singleWindowIsNotInventedIntoTwo() {
        val data = parse("""{"rate_limit":{"primary_window":{"used_percent":38,"limit_window_seconds":604800,"reset_at":2000}},"unknown":true}""")
        assertEquals(1, data.windows.size)
        assertEquals("每周额度", data.windows.single().name)
        assertEquals("62", data.windows.single().percentageLabel(true))
    }
    @Test fun weeklyWindowIsOrderedFirst() {
        val data = parse("""{"rate_limit":{"primary_window":{"used_percent":64,"limit_window_seconds":18000},"secondary_window":{"used_percent":38,"limit_window_seconds":604800}}}""")
        assertEquals("每周额度", data.windows.first().name)
        assertEquals("5 小时额度", data.windows.last().name)
    }
    @Test fun missingPercentageStaysUnknown() {
        val window = parse("""{"rate_limit":{"primary_window":{"limit_window_seconds":900}}}""").windows.single()
        assertNull(window.usedPercent)
        assertEquals("暂不可用", window.percentageLabel(true))
        assertEquals("暂不可用", window.percentageLabel(false))
    }
    @Test fun weeklyOnlyAccountGetsAnExplanationWithoutInventingShortWindow() {
        val data = parse("""{"plan_type":"pro","rate_limit":{"primary_window":null,"secondary_window":{"used_percent":38,"limit_window_seconds":604800}}}""")
        assertEquals(1, data.windows.size)
        assertEquals("62", data.windows.single().percentageLabel(true))
        assertEquals("未提供短周期额度", data.quotaNotice()!!.title)
    }
    @Test fun explicitlyAllowedAccountWithoutWindowsIsASuccess() {
        val data = parse("""{"plan_type":"pro","rate_limit":{"allowed":true,"limit_reached":false,"primary_window":null,"secondary_window":null}}""")
        assertTrue(data.windows.isEmpty())
        assertEquals("当前可用", data.quotaNotice()!!.title)
        assertEquals(1000L, data.fetchedAt)
    }
    @Test fun nullQuotaDoesNotMeanUnlimitedOrAllowed() {
        val data = parse("""{"plan_type":"pro","rate_limit":null,"credits":{"unlimited":true}}""")
        assertTrue(data.windows.isEmpty())
        assertNull(data.allowed)
        assertEquals("未提供周期额度", data.quotaNotice()!!.title)
    }
    @Test fun planAloneCannotDetermineQuotaState() {
        val data = parse("""{"plan_type":"pro","rate_limit":{"primary_window":{"used_percent":75,"limit_window_seconds":18000}}}""")
        assertNull(data.quotaNotice())
        assertEquals("25", data.windows.single().percentageLabel(true))
    }
    @Test fun blockedAccountWithoutWindowsStillShowsRestriction() {
        for (flags in listOf("\"allowed\":false", "\"allowed\":true,\"limit_reached\":true")) {
            val data = parse("""{"rate_limit":{$flags}}""")
            assertEquals("当前使用受限", data.quotaNotice()!!.title)
        }
    }
    @Test fun additionalLimitDoesNotHideMissingMainQuota() {
        val data = parse("""{"rate_limit":null,"additional_rate_limits":[{"metered_feature":"review","rate_limit":{"primary_window":{"used_percent":20,"limit_window_seconds":18000}}}]}""")
        assertEquals(1, data.windows.size)
        assertEquals("未提供周期额度", data.quotaNotice()!!.title)
    }
    @Test fun unknownWindowDurationDoesNotImplyShortCycleIsAbsent() {
        val data = parse("""{"rate_limit":{"primary_window":{"used_percent":20}}}""")
        assertNull(data.quotaNotice())
    }
    @Test fun windowlessSnapshotSurvivesStorageAndOldCacheRemainsReadable() {
        val data = parse("""{"rate_limit":{"allowed":true}}""")
        val restored = AppJson.decodeFromString<UsageSnapshot>(kotlinx.serialization.json.Json.encodeToString(UsageSnapshot.serializer(), data))
        assertEquals(data, restored)
        val legacy = AppJson.decodeFromString<UsageSnapshot>("""{"accountId":"test-account","plan":"pro","windows":[],"fetchedAt":1000}""")
        assertNull(legacy.allowed)
        assertEquals("未提供周期额度", legacy.quotaNotice()!!.title)
    }
    @Test fun zeroAndFullUsageArePreserved() {
        val data = parse("""{"rate_limit":{"primary_window":{"used_percent":0},"secondary_window":{"used_percent":100}}}""")
        assertEquals(listOf("100", "0"), data.windows.map { it.percentageLabel(true) })
    }
    @Test fun invalidPercentageIsNotClampedIntoCredibleValue() {
        val data = parse("""{"rate_limit":{"primary_window":{"used_percent":-1},"secondary_window":{"used_percent":101}}}""")
        assertTrue(data.windows.all { it.usedPercent == null })
    }
    @Test fun additionalWindowsRemainAvailable() {
        val data = parse("""{"rate_limit":{"primary_window":{"used_percent":10}},"additional_rate_limits":[{"metered_feature":"review","limit_name":"Review","rate_limit":{"primary_window":{"used_percent":20}}}]}""")
        assertEquals(2, data.windows.size)
        assertTrue(data.windows.last().id.startsWith("review:"))
    }
    @Test fun relativeResetUsesFetchTime() {
        val window = parse("""{"rate_limit":{"primary_window":{"used_percent":10,"reset_after_seconds":300}}}""").windows.single()
        assertEquals(1300L, window.resetsAt)
    }
    @Test fun elapsedResetDoesNotRestoreQuota() {
        val window = QuotaWindow("a", "每周额度", 100.0, 604800, 900)
        assertEquals("重置待确认 · 请刷新", resetLabel(window, 1000))
        assertEquals("0", window.percentageLabel(true))
    }
    @Test fun conflictingAccountIsRejected() {
        try { parse("""{"account_id":"other","rate_limit":{"primary_window":{"used_percent":10}}}"""); fail() }
        catch (error: ApiFailure) { assertEquals(FailureKind.FORBIDDEN, error.kind) }
    }
    @Test fun emptyOrMalformedResponseIsExplicitFailure() {
        for (body in listOf("{}", "null", "<html>", """{"plan_type":"pro"}""", """{"rate_limit":{}}""",
            """{"plan_type":"pro","rate_limit":false}""", """{"plan_type":"pro","rate_limit":{"primary_window":[]}}""")) {
            try { parse(body); fail() } catch (error: ApiFailure) { assertEquals(FailureKind.PROTOCOL, error.kind) }
        }
    }
    @Test fun absoluteTimeUsesCurrentTimezone() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC")); assertEquals("00:00", absoluteTime(0))
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai")); assertEquals("08:00", absoluteTime(0))
        } finally { TimeZone.setDefault(original) }
    }
    @Test fun credentialStringNeverContainsTokens() {
        val session = Session("sensitive-access", "sensitive-refresh", "account", 2000)
        assertFalse(session.toString().contains("sensitive"))
    }
    @Test fun dateAndTimezoneChangeDoNotChangeResetOrSuccessfulFetchInstants() {
        val original = TimeZone.getDefault()
        val fetchedAt = java.time.Instant.parse("2026-10-09T23:30:00Z").epochSecond
        val snapshot = demoSnapshot(fetchedAt)
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            assertEquals("10/09 23:30", absoluteTime(snapshot.fetchedAt, true))
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            assertEquals("10/10 07:30", absoluteTime(snapshot.fetchedAt, true))
            assertEquals(fetchedAt, snapshot.fetchedAt)
            assertEquals("重置待确认 · 请刷新", resetLabel(snapshot.windows.first(), snapshot.windows.first().resetsAt!!))
            assertEquals("62", snapshot.windows.first().percentageLabel(true))
        } finally { TimeZone.setDefault(original) }
    }
    @Test fun daylightSavingTransitionUsesLocalOffsetForEachInstant() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            val before = java.time.Instant.parse("2026-03-08T06:59:00Z").epochSecond
            val after = before + 60
            assertEquals("03/08 01:59", absoluteTime(before, true))
            assertEquals("03/08 03:00", absoluteTime(after, true))
            val window = QuotaWindow("test", "窗口", 100.0, 3600, after)
            assertEquals("03/08 03:00 重置", resetLabel(window, before))
            assertEquals("重置待确认 · 请刷新", resetLabel(window, after))
            assertEquals("0", window.percentageLabel(true))
        } finally { TimeZone.setDefault(original) }
    }
}
