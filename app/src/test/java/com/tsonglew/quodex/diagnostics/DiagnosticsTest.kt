package com.tsonglew.quodex.diagnostics

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

class DiagnosticsTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun boundsHistoryAndClearsIt() {
        val log = Diagnostics(File(temp.root, "events.log"))
        repeat(250) { log.record(DiagnosticEvent.REFRESH_END, it.toLong()) }
        val lines = log.report().lines().filter { it.contains("REFRESH_END") }
        assertEquals(200, lines.size)
        assertTrue(lines.first().endsWith("REFRESH_END 50"))
        assertTrue(lines.last().endsWith("REFRESH_END 249"))
        log.clear()
        assertFalse(log.report().contains("REFRESH_END"))
    }
    @Test fun persistenceSurvivesNewInstance() {
        val file = File(temp.root, "events.log")
        Diagnostics(file).record(DiagnosticEvent.SKIP_BACKOFF, 60)
        assertTrue(Diagnostics(file).report().contains("SKIP_BACKOFF 60"))
    }
    @Test fun brokenStorageDoesNotBreakOperations() {
        val parent = temp.newFile()
        Diagnostics(File(parent, "events.log")).record(DiagnosticEvent.APP_START)
    }
}
