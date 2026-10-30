package com.tsonglew.quodex

import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker.Result
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.tsonglew.quodex.data.FailureKind
import com.tsonglew.quodex.data.Session
import com.tsonglew.quodex.sync.UsageSyncWorker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class SyncWorkerDeviceTest {
    @Test fun terminalTransportAndHttpFailuresDoNotScheduleWorkerRetries() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.quotaApp as TestQuotaApplication
        try {
            app.repository.logout()
            app.repository.connect(Session("test-access", "test-refresh", "test-account", Long.MAX_VALUE))
            val cached = app.repository.state.value.snapshot
            for ((status, transport) in listOf(404 to null, 200 to javax.net.ssl.SSLHandshakeException("Invalid test certificate"))) {
                app.usageStatusCode = status
                app.usageTransportFailure = transport
                app.settings.syncResult(null, lastAttemptAt = 0)
                val before = app.usageRequests.get()
                val worker = TestListenableWorkerBuilder<UsageSyncWorker>(context).setInputData(workDataOf("force" to true)).build()
                assertEquals(Result.failure(), worker.doWork())
                assertEquals(before + 1, app.usageRequests.get())
                assertEquals(cached, app.repository.state.value.snapshot)
            }
        } finally {
            app.usageStatusCode = 200
            app.usageTransportFailure = null
            app.repository.logout(); app.reconcileSync()
        }
    }
    @Test fun transientFailuresKeepCacheStopAfterThreeAttemptsAndRecover() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.quotaApp as TestQuotaApplication
        fun worker(attempt: Int) = TestListenableWorkerBuilder<UsageSyncWorker>(context)
            .setInputData(workDataOf("force" to true)).setRunAttemptCount(attempt).build()
        try {
            app.repository.logout()
            app.usedPercent = 38
            app.repository.connect(Session("test-access", "test-refresh", "test-account", Long.MAX_VALUE))
            val cached = app.repository.state.value.snapshot
            for ((status, transport, expected) in listOf(
                Triple(200, IOException("Simulated offline"), FailureKind.NETWORK),
                Triple(200, SocketTimeoutException("Simulated timeout"), FailureKind.NETWORK),
                Triple(503, null, FailureKind.SERVER),
            )) {
                app.usageStatusCode = status
                app.usageTransportFailure = transport
                val before = app.usageRequests.get()
                for (attempt in 0..2) {
                    // Advance the persisted attempt guard rather than waiting for wall-clock backoff.
                    app.settings.syncResult(null, lastAttemptAt = 0)
                    val result = worker(attempt).doWork()
                    assertEquals(if (attempt < 2) Result.retry() else Result.failure(), result)
                    assertEquals(expected, app.repository.state.value.failure)
                    assertEquals(cached, app.repository.state.value.snapshot)
                }
                assertEquals("One request per eligible attempt", before + 3, app.usageRequests.get())
            }
            app.usageStatusCode = 200
            app.usageTransportFailure = null
            app.usedPercent = 17
            app.settings.syncResult(null, lastAttemptAt = 0)
            assertEquals(Result.success(), worker(0).doWork())
            assertNull(app.repository.state.value.failure)
            assertEquals(17.0, app.repository.state.value.snapshot?.windows?.single()?.usedPercent)
            assertTrue(app.repository.state.value.snapshot!!.fetchedAt >= cached!!.fetchedAt)
        } finally {
            app.usageStatusCode = 200
            app.usageTransportFailure = null
            app.repository.logout(); app.reconcileSync()
        }
    }

    @Test fun rateLimitDefersWithoutRequestsAndPreservesCacheUntilNextUserAttempt() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.quotaApp as TestQuotaApplication
        fun worker(attempt: Int) = TestListenableWorkerBuilder<UsageSyncWorker>(context)
            .setInputData(workDataOf("force" to true)).setRunAttemptCount(attempt).build()
        try {
            app.repository.logout()
            app.usageStatusCode = 200
            app.repository.connect(Session("test-access", "test-refresh", "test-account", Long.MAX_VALUE))
            val cached = app.repository.state.value.snapshot
            app.usageStatusCode = 429
            app.usageRetryAfter = "5"
            app.settings.syncResult(null, lastAttemptAt = 0)
            val before = app.usageRequests.get()
            assertEquals(Result.retry(), worker(0).doWork())
            val deadline = app.settings.read().retryAt
            assertTrue(deadline > 0)
            assertEquals(FailureKind.LIMITED, app.repository.state.value.failure)
            assertEquals(Result.retry(), worker(1).doWork())
            assertEquals(Result.failure(), worker(2).doWork())
            assertEquals("Retry-After blocks additional HTTP attempts", before + 1, app.usageRequests.get())
            assertEquals(deadline, app.settings.read().retryAt)
            assertEquals(cached, app.repository.state.value.snapshot)
            // Wait for the actual deadline; clearing disk state must not bypass the
            // process-local guard used when persisting a server limit fails.
            val waitMillis = ((deadline - java.time.Instant.now().epochSecond + 1).coerceAtLeast(0)) * 1000
            assertTrue("Synthetic limit must have a bounded wait", waitMillis <= 6000)
            Thread.sleep(waitMillis)
            app.usageStatusCode = 200
            app.usageRetryAfter = null
            app.settings.syncResult(FailureKind.LIMITED, retryAt = 0, lastAttemptAt = 0)
            assertEquals(Result.success(), worker(0).doWork())
            assertEquals(before + 2, app.usageRequests.get())
            assertNull(app.repository.state.value.failure)
        } finally {
            app.usageStatusCode = 200
            app.usageRetryAfter = null
            app.repository.logout(); app.reconcileSync()
        }
    }
}
