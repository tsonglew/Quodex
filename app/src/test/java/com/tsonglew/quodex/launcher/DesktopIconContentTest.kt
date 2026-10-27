package com.tsonglew.quodex.launcher

import com.tsonglew.quodex.data.*
import org.junit.Assert.*
import org.junit.Test

class DesktopIconContentTest {
    private val now = 1000L
    private val preferences = PreferencesState()
    private fun state(snapshot: UsageSnapshot = demoSnapshot(now)) = AppState(
        initialized = true, connected = true, snapshot = snapshot.copy(accountId = "test-account",
            windows = snapshot.windows.map { it.copy(id = it.id.replace("demo:", "codex:")) }))
    private fun content(state: AppState = state(), prefs: PreferencesState = preferences) = desktopIconContent(state, prefs, now)

    @Test fun shortWindowIsSelectedEvenWhenWeeklyWindowIsFirst() {
        val icon = content()
        assertEquals(36, icon.percent)
        assertEquals("5h", icon.period)
        assertEquals(DesktopIconStatus.CURRENT, icon.status)
        assertTrue(icon.description.contains("5 小时额度 剩余 36%"))
    }

    @Test fun weeklySelectionAlwaysShowsRemainingEvenWhenDashboardShowsUsed() {
        val icon = content(prefs = preferences.copy(desktopIconWindow = "weekly", showRemaining = false))
        assertEquals(62, icon.percent)
        assertEquals("周", icon.period)
        assertTrue(icon.description.contains("每周额度 剩余 62%"))
    }

    @Test fun exactEndpointsAndFractionalValuesUseRemainingPercentage() {
        for ((used, remaining) in listOf(0.0 to 100, 100.0 to 0, 38.4 to 62, 38.6 to 61)) {
            val snapshot = UsageSnapshot("test-account", null,
                listOf(QuotaWindow("codex:primary_window", "5 小时额度", used, 18000, null)), now)
            assertEquals(remaining, content(state(snapshot)).percent)
        }
    }

    @Test fun missingAndInvalidUsageNeverBecomeACrediblePercentage() {
        for (used in listOf(null, -1.0, 101.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val snapshot = UsageSnapshot("test-account", null,
                listOf(QuotaWindow("codex:primary_window", "5 小时额度", used, 18000, null)), now)
            val icon = content(state(snapshot))
            assertNull(icon.percent)
            assertEquals("—", icon.percentageLabel)
            assertEquals(DesktopIconStatus.UNAVAILABLE, icon.status)
        }
    }

    @Test fun missingSelectedWindowAndAdditionalQuotasAreNotSubstituted() {
        val snapshot = UsageSnapshot("test-account", null, listOf(
            QuotaWindow("codex:secondary_window", "每周额度", 20.0, 604800, null),
            QuotaWindow("review:primary_window", "Review", 10.0, 18000, null),
        ), now)
        assertNull(content(state(snapshot)).percent)
        assertEquals(80, content(state(snapshot), preferences.copy(desktopIconWindow = "weekly")).percent)
        assertNull(content(state(snapshot.copy(windows = emptyList()))).percent)
    }

    @Test fun logoutClearsPercentageEvenIfAnOldSnapshotIsPresent() {
        val icon = content(state().copy(connected = false))
        assertNull(icon.percent)
        assertEquals(DesktopIconStatus.DISCONNECTED, icon.status)
    }

    @Test fun failuresAndOldSnapshotsKeepTheLastValueWithAVisibleMarker() {
        val failed = content(state().copy(failure = FailureKind.NETWORK))
        assertEquals(36, failed.percent)
        assertEquals(DesktopIconStatus.CACHED, failed.status)
        assertEquals("旧数据", failed.marker)
        assertTrue(failed.description.contains("上次数据"))
        val old = desktopIconContent(state(), preferences, now + preferences.refreshMinutes * 120 + 1)
        assertEquals(36, old.percent)
        assertEquals(DesktopIconStatus.CACHED, old.status)
    }

    @Test fun elapsedResetDoesNotInventReplenishedQuota() {
        val original = state().snapshot!!
        val snapshot = original.copy(windows = original.windows.map { it.copy(resetsAt = now) })
        val icon = content(state(snapshot))
        assertEquals(36, icon.percent)
        assertEquals(DesktopIconStatus.CACHED, icon.status)
    }

    @Test fun restrictionsHideThePercentageAndDemoIsExplicit() {
        for (snapshot in listOf(state().snapshot!!.copy(allowed = false), state().snapshot!!.copy(limitReached = true))) {
            val icon = content(state(snapshot))
            assertNull(icon.percent)
            assertEquals(DesktopIconStatus.RESTRICTED, icon.status)
        }
        val demo = content(AppState(initialized = true, snapshot = demoSnapshot(now), demo = true))
        assertEquals(36, demo.percent)
        assertEquals("示例", demo.marker)
        assertTrue(demo.description.contains("示例"))
    }

    @Test fun disablingTheOverlayRestoresTheOriginalLogo() {
        val icon = content(prefs = preferences.copy(desktopIconEnabled = false))
        assertFalse(icon.showPercentage)
        assertNull(icon.percent)
        assertEquals("Quodex", icon.description)
    }
}
