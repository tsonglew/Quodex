package com.tsonglew.quodex

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.tsonglew.quodex.data.Session
import com.tsonglew.quodex.sync.SyncScheduler
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Explicit owned-emulator probe: disables and restores its real network transports. */
class NetworkConstraintDeviceTest {
    @Test fun actualOfflineWorkWaitsThenRefreshesOnceAfterConnectivityReturns() = runBlocking {
        assumeTrue("Requires explicit owned-emulator network probe",
            InstrumentationRegistry.getArguments().getString("networkProbe") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.quotaApp as TestQuotaApplication
        val manager = WorkManager.getInstance(context)
        val network = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
            java.io.FileInputStream(it.fileDescriptor).bufferedReader().use { reader -> reader.readText().trim() }
        }
        val wifi = shell("settings get global wifi_on") == "1"
        val data = shell("settings get global mobile_data") == "1"
        fun restore() {
            shell("svc wifi ${if (wifi) "enable" else "disable"}")
            shell("svc data ${if (data) "enable" else "disable"}")
        }
        fun await(message: String, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 60_000
            while (SystemClock.elapsedRealtime() < deadline) {
                if (condition()) return
                Thread.sleep(100)
            }
            fail(message)
        }
        fun work() = manager.getWorkInfosForUniqueWork("codex-manual-sync").get(10, TimeUnit.SECONDS).single()
        try {
            await("Probe requires OS-validated internet before changing network settings") {
                network.getNetworkCapabilities(network.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            }
            manager.cancelAllWork().result.get(10, TimeUnit.SECONDS)
            app.repository.logout()
            app.usedPercent = 38
            app.repository.connect(Session("test-access", "test-refresh", "test-account", Long.MAX_VALUE))
            val cached = app.repository.state.value.snapshot
            val requests = app.usageRequests.get()
            shell("svc wifi disable")
            shell("svc data disable")
            await("The OS must actually have no active network") { network.activeNetwork == null }
            app.usedPercent = 17
            app.settings.syncResult(null, lastAttemptAt = 0)
            SyncScheduler.refresh(context).result.get(10, TimeUnit.SECONDS)
            Thread.sleep(3_000)
            assertEquals(WorkInfo.State.ENQUEUED, work().state)
            assertEquals(0, work().runAttemptCount)
            assertEquals("Offline constraints must prevent HTTP attempts", requests, app.usageRequests.get())
            assertEquals(cached, app.repository.state.value.snapshot)
            restore()
            await("OS-validated internet must return") {
                network.getNetworkCapabilities(network.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            }
            await("The actual scheduled worker must complete after reconnect") { work().state == WorkInfo.State.SUCCEEDED }
            assertEquals("Reconnection must fetch once", requests + 1, app.usageRequests.get())
            assertEquals(17.0, app.repository.state.value.snapshot?.windows?.single()?.usedPercent)
            assertNull(app.repository.state.value.failure)
        } finally {
            manager.cancelAllWork().result.get(10, TimeUnit.SECONDS)
            app.repository.logout()
            restore()
            app.reconcileSync()
        }
    }
}
