package com.tsonglew.quodex

import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.tsonglew.quodex.sync.SyncScheduler
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Exercise the real WorkManager database, rather than only checking request builders. */
class SyncSchedulerDeviceTest {
    @Test fun repeatedSchedulesAndIntervalChangesKeepOnePeriodicWork() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = WorkManager.getInstance(context)
        val name = "codex-periodic-sync"
        manager.cancelUniqueWork(name).result.get(10, TimeUnit.SECONDS)
        try {
            SyncScheduler.schedule(context, 30).result.get(10, TimeUnit.SECONDS)
            val original = manager.getWorkInfosForUniqueWork(name).get(10, TimeUnit.SECONDS)
                .single { !it.state.isFinished }.id
            // Foreground entries, preference changes and multiple widget receivers all reconcile this job.
            for (minutes in listOf(30L, 30L, 15L, 60L, 30L)) {
                SyncScheduler.schedule(context, minutes).result.get(10, TimeUnit.SECONDS)
                val active = manager.getWorkInfosForUniqueWork(name).get(10, TimeUnit.SECONDS)
                    .filter { !it.state.isFinished }
                assertEquals("Reconciliation must not add another polling job", 1, active.size)
                assertEquals("UPDATE must preserve the existing periodic job", original, active.single().id)
            }
        } finally {
            manager.cancelUniqueWork(name).result.get(10, TimeUnit.SECONDS)
        }
        assertEquals(0, manager.getWorkInfosForUniqueWork(name).get(10, TimeUnit.SECONDS).count { !it.state.isFinished })
    }
}
