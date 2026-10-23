package com.tsonglew.quodex

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.tsonglew.quodex.data.FailureKind
import com.tsonglew.quodex.data.Session
import com.tsonglew.quodex.sync.SyncScheduler
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Uses real WorkManager delays; no TestDriver, fake clock or attempt-count injection. */
class ScheduledRetryDeviceTest {
    @Test fun actualScheduledRetriesStopAndANewUserRequestRecovers() = runBlocking {
        assumeTrue("Requires explicit owned-emulator scheduled retry probe",
            InstrumentationRegistry.getArguments().getString("scheduledRetryProbe") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.quotaApp as TestQuotaApplication
        val manager = WorkManager.getInstance(context)
        val network = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        fun await(message: String, timeout: Long = 60_000, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + timeout
            while (SystemClock.elapsedRealtime() < deadline) {
                if (condition()) return
                Thread.sleep(100)
            }
            fail(message)
        }
        fun work() = manager.getWorkInfosForUniqueWork("codex-manual-sync")
            .get(10, TimeUnit.SECONDS).single()
        try {
            await("Actual scheduler requires validated connectivity") {
                network.getNetworkCapabilities(network.activeNetwork)
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            }
            manager.cancelAllWork().result.get(10, TimeUnit.SECONDS)
            app.repository.logout()
            app.usedPercent = 38
            app.repository.connect(Session("test-access", "test-refresh", "test-account", Long.MAX_VALUE))
            val cached = app.repository.state.value.snapshot
            val before = app.usageRequests.get()
            val priorTimes = app.usageRequestTimes.size
            app.usageStatusCode = 503
            // Only separate the seed read from the first user request. Do not change
            // persisted guards, clocks or attempts during scheduled retries.
            Thread.sleep(11_000)
            SyncScheduler.refresh(context).result.get(10, TimeUnit.SECONDS)
            val failedId = work().id
            await("Real retries must reach terminal failure", 240_000) {
                assertTrue("Must never issue a fourth request", app.usageRequests.get() <= before + 3)
                work().state == WorkInfo.State.FAILED
            }
            val attempts = app.usageRequestTimes.toList().drop(priorTimes)
            assertEquals(3, attempts.size)
            val delays = attempts.zipWithNext { first, second -> second - first }
            assertTrue("First real backoff must be at least 30s: $delays", delays[0] >= 29_000)
            assertTrue("Second real backoff must be at least 60s: $delays", delays[1] >= 59_000)
            println("SCHEDULED_RETRY backoffMillis=$delays state=${work().state}")
            assertEquals(before + 3, app.usageRequests.get())
            assertEquals(cached, app.repository.state.value.snapshot)
            assertEquals(FailureKind.SERVER, app.repository.state.value.failure)
            Thread.sleep(11_000)
            assertEquals("Terminal work must remain failed", WorkInfo.State.FAILED, work().state)
            assertEquals("No request after terminal failure", before + 3, app.usageRequests.get())
            app.usageStatusCode = 200
            app.usedPercent = 17
            SyncScheduler.refresh(context).result.get(10, TimeUnit.SECONDS)
            assertNotEquals("A new user action replaces the finished work", failedId, work().id)
            await("New user request must complete") { work().state == WorkInfo.State.SUCCEEDED }
            assertEquals(before + 4, app.usageRequests.get())
            assertEquals(17.0, app.repository.state.value.snapshot?.windows?.single()?.usedPercent)
            assertNull(app.repository.state.value.failure)
        } finally {
            manager.cancelAllWork().result.get(10, TimeUnit.SECONDS)
            app.usageStatusCode = 200
            app.repository.logout()
            app.reconcileSync()
        }
    }
}
