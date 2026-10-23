package com.tsonglew.quodex

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.NetworkType
import androidx.work.WorkManager
import com.tsonglew.quodex.update.AndroidUpdateNotifier
import com.tsonglew.quodex.update.AppUpdate
import com.tsonglew.quodex.update.AppUpdateScheduler
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class AppUpdatesDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun updateScheduleIsUniqueAndCanBeDisabledWithoutQuotaWork() {
        val manager = WorkManager.getInstance(context)
        AppUpdateScheduler.reconcile(context, false).result.get(10, TimeUnit.SECONDS)
        try {
            AppUpdateScheduler.reconcile(context, true).result.get(10, TimeUnit.SECONDS)
            val original = manager.getWorkInfosForUniqueWork(AppUpdateScheduler.WORK_NAME).get(10, TimeUnit.SECONDS)
                .single { !it.state.isFinished }
            repeat(3) { AppUpdateScheduler.reconcile(context, true).result.get(10, TimeUnit.SECONDS) }
            val work = manager.getWorkInfosForUniqueWork(AppUpdateScheduler.WORK_NAME).get(10, TimeUnit.SECONDS)
                .filter { !it.state.isFinished }
            assertEquals(1, work.size)
            assertEquals(original.id, work.single().id)
            assertEquals(NetworkType.CONNECTED, work.single().constraints.requiredNetworkType)
            assertTrue(work.single().constraints.requiresBatteryNotLow())
            assertEquals(TimeUnit.HOURS.toMillis(12), work.single().periodicityInfo!!.repeatIntervalMillis)
        } finally {
            AppUpdateScheduler.reconcile(context, false).result.get(10, TimeUnit.SECONDS)
        }
        assertTrue(manager.getWorkInfosForUniqueWork(AppUpdateScheduler.WORK_NAME).get(10, TimeUnit.SECONDS).all { it.state.isFinished })
    }

    @Test fun deniedRuntimePermissionDoesNotPostNotification() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        assumeTrue(ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
        val notifier = AndroidUpdateNotifier(context)
        notifier.clear()
        assertFalse(notifier.show(update()))
    }

    @Test fun allowedUpdateNotificationCanBePostedAndCleared() {
        val notifier = AndroidUpdateNotifier(context)
        // Revoking a runtime permission kills the process, so test the existing permission state.
        assumeTrue("Requires notification permission; API 29/31 exercise this by default", notifier.enabled())
        val manager = context.getSystemService(NotificationManager::class.java)
        notifier.clear()
        try {
            assertTrue(notifier.show(update()))
            awaitNotification(manager, present = true)
            val notification = manager.activeNotifications.single { it.notification.channelId == AndroidUpdateNotifier.CHANNEL }.notification
            assertNotNull(notification.contentIntent)
            assertTrue(notification.extras.getString(android.app.Notification.EXTRA_TITLE).orEmpty().contains("0.1.0-alpha22"))
            notifier.clear()
            awaitNotification(manager, present = false)
        } finally {
            notifier.clear()
        }
    }

    private fun update() = AppUpdate(BuildConfig.VERSION_CODE + 1L, "0.1.0-alpha22", "https://github.com/tsonglew/Quodex/releases/tag/v0.1.0-alpha22", "a".repeat(40))

    private fun awaitNotification(manager: NotificationManager, present: Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (manager.activeNotifications.any { it.notification.channelId == AndroidUpdateNotifier.CHANNEL } == present) return
            Thread.sleep(50)
        }
        assertEquals(present, manager.activeNotifications.any { it.notification.channelId == AndroidUpdateNotifier.CHANNEL })
    }

}
