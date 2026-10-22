package com.tsonglew.quotapal.diagnostics

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

class ObservationStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private fun store() = ObservationStore(File(temp.root, "observations.csv"))

    @Test fun countsRealRequestsSeparatelyFromRefreshesAndPollingResponses() {
        val s = store()
        s.record(DiagnosticEvent.REFRESH_START, longArrayOf(1), 3600)
        s.record(DiagnosticEvent.SKIP_FRESH, longArrayOf(), 3600)
        s.record(DiagnosticEvent.HTTP_START, longArrayOf(0), 3600)
        s.record(DiagnosticEvent.HTTP_START, longArrayOf(1), 3600)
        s.record(DiagnosticEvent.HTTP_END, longArrayOf(1, 403), 3600)
        s.record(DiagnosticEvent.HTTP_NETWORK_FAILURE, longArrayOf(0, 0), 3600)
        assertTrue(s.report(3600).contains("1970-01-01T01:00:00Z 1 1 1 1 0 0 0"))
    }

    @Test fun recordsSuccessGapAcrossHoursAndProcessRestart() {
        store().record(DiagnosticEvent.SNAPSHOT_SAVED, longArrayOf(), 3600)
        store().record(DiagnosticEvent.SNAPSHOT_SAVED, longArrayOf(), 7200)
        assertTrue(store().report(7200).contains("1970-01-01T02:00:00Z 0 0 0 0 1 7200 3600"))
    }

    @Test fun boundsHistoryAndExpiresOldRowsWithoutNewEvents() {
        repeat(200) { store().record(DiagnosticEvent.HTTP_START, longArrayOf(0), (it + 1) * 3600L) }
        val report = store().report(200 * 3600L)
        assertEquals(168, report.lines().count { it.startsWith("1970-") })
        assertFalse(store().report(400 * 3600L).contains("1970-"))
    }

    @Test fun ignoresCorruptRowsAndFutureHistoryAfterClockRollback() {
        File(temp.root, "observations.csv").writeText("broken\n1,1,0,0,0,0,0,-1\n")
        store().record(DiagnosticEvent.SNAPSHOT_SAVED, longArrayOf(), 7200)
        store().record(DiagnosticEvent.SNAPSHOT_SAVED, longArrayOf(), 3600)
        assertFalse(store().report(3600).contains("02:00:00"))
        assertTrue(store().report(3600).contains(" 1 3600 0"))
    }

    @Test fun clearRemovesEventsAndStatisticsTogether() {
        val diagnostics = Diagnostics(File(temp.root, "events.log"))
        diagnostics.record(DiagnosticEvent.HTTP_START, 0)
        assertTrue(File(temp.root, "observations.csv").exists())
        diagnostics.clear()
        assertFalse(File(temp.root, "observations.csv").exists())
        assertFalse(File(temp.root, "events.log").exists())
    }

    @Test fun summarizesCalendarHourWindowsWithoutCountingRefreshCallsAsRequests() {
        val s = store()
        s.record(DiagnosticEvent.HTTP_START, longArrayOf(0), 52 * 3600) // Outside 48 buckets.
        s.record(DiagnosticEvent.HTTP_START, longArrayOf(0), 53 * 3600) // First included bucket.
        s.record(DiagnosticEvent.HTTP_START, longArrayOf(1), 100 * 3600)
        s.record(DiagnosticEvent.HTTP_END, longArrayOf(1, 403), 100 * 3600)
        s.record(DiagnosticEvent.HTTP_NETWORK_FAILURE, longArrayOf(0, 0), 100 * 3600)
        s.record(DiagnosticEvent.REFRESH_START, longArrayOf(1), 100 * 3600)
        val report = s.report(100 * 3600 + 120)
        assertTrue(report.contains("hourBuckets=48 eventHourBuckets=2 usageRequests=1 authRequests=1 httpNon2xx=1 networkFailures=1 snapshotsSaved=0 maxCompletedSuccessGapSeconds=0"))
        assertTrue(report.contains("hourBuckets=168 eventHourBuckets=3 usageRequests=2 authRequests=1"))
        assertTrue(report.contains("including the current partial hour"))
        assertTrue(report.contains("lastSavedAt=unknown secondsSinceLastSuccess=unknown"))
    }

    @Test fun exposesOngoingGapWhenThereAreNoRecentSuccesses() {
        store().record(DiagnosticEvent.SNAPSHOT_SAVED, longArrayOf(), 3600)
        store().record(DiagnosticEvent.SNAPSHOT_SAVED, longArrayOf(), 7200)
        // Read through a new instance without writing any further events.
        val report = store().report(60 * 3600)
        assertTrue(report.contains("hourBuckets=48 eventHourBuckets=0 usageRequests=0 authRequests=0 httpNon2xx=0 networkFailures=0 snapshotsSaved=0 maxCompletedSuccessGapSeconds=0"))
        assertTrue(report.contains("snapshotsSaved=2 maxCompletedSuccessGapSeconds=3600"))
        assertTrue(report.contains("lastSavedAt=7200 secondsSinceLastSuccess=208800"))
        assertTrue(store().report(200 * 3600).contains("lastSavedAt=unknown secondsSinceLastSuccess=unknown"))
    }

    @Test fun completedGapCanBeginBeforeSelectedWindow() {
        store().record(DiagnosticEvent.SNAPSHOT_SAVED, longArrayOf(), 52 * 3600)
        store().record(DiagnosticEvent.SNAPSHOT_SAVED, longArrayOf(), 54 * 3600)
        val report = store().report(100 * 3600)
        assertTrue(report.contains("snapshotsSaved=1 maxCompletedSuccessGapSeconds=7200"))
        assertTrue(report.contains("may start before it"))
    }

    @Test fun rollbackNeverReportsNegativeSuccessAge() {
        store().record(DiagnosticEvent.SNAPSHOT_SAVED, longArrayOf(), 3600)
        store().record(DiagnosticEvent.SNAPSHOT_SAVED, longArrayOf(), 7300)
        assertTrue(store().report(7200).contains("lastSavedAt=unknown secondsSinceLastSuccess=unknown"))
        assertTrue(store().report(3600).contains("lastSavedAt=3600 secondsSinceLastSuccess=0"))
    }

    @Test fun summaryExpiresOldBucketsWithoutChangingStoredEvidence() {
        val file = File(temp.root, "observations.csv")
        file.writeText(listOf(32, 33, 152, 153, 200, 201).joinToString("\n") { "$it,1,0,0,0,0,0,0" })
        val original = file.readText()
        val report = store().report(200 * 3600)
        assertTrue(report.contains("hourBuckets=48 eventHourBuckets=2 usageRequests=2"))
        assertTrue(report.contains("hourBuckets=168 eventHourBuckets=4 usageRequests=4"))
        assertEquals(original, file.readText())
        store().clear()
        assertTrue(store().report(200 * 3600).contains("hourBuckets=168 eventHourBuckets=0 usageRequests=0"))
        assertTrue(store().report(200 * 3600).contains("lastSavedAt=unknown secondsSinceLastSuccess=unknown"))
    }
}
