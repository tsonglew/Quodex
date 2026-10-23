package com.tsonglew.quodex

import android.Manifest
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.tsonglew.quodex.data.Session
import com.tsonglew.quodex.data.SyncResult
import com.tsonglew.quodex.data.demoSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.Instant

/** Explicit probe for an owned emulator: changes and restores its real system clock. */
class SystemTimeDeviceTest {
    @Test fun systemTimezoneClockRollbackAndDstUpdateCachedWidgets() {
        assumeTrue("Requires explicit owned-emulator clock probe",
            InstrumentationRegistry.getArguments().getString("systemTimeProbe") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.quotaApp as TestQuotaApplication
        fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
            java.io.FileInputStream(it.fileDescriptor).bufferedReader().use { reader -> reader.readText().trim() }
        }
        val zone = shell("getprop persist.sys.timezone")
        val autoTime = shell("settings get global auto_time")
        val autoZone = shell("settings get global auto_time_zone")
        val originalTime = System.currentTimeMillis()
        val originalElapsed = SystemClock.elapsedRealtime()
        fun setTime(time: Long) {
            shell("cmd alarm set-time $time")
            assertTrue("System clock must really change", kotlin.math.abs(System.currentTimeMillis() - time) < 5_000)
        }
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.BIND_APPWIDGET)
        try {
            shell("settings put global auto_time 0")
            shell("settings put global auto_time_zone 0")
            shell("cmd alarm set-timezone UTC")
            val fixtureTime = Instant.parse("2026-03-08T06:59:00Z").epochSecond
            setTime(fixtureTime * 1000)
            runBlocking { app.repository.demo(demoSnapshot(fixtureTime).let { snapshot ->
                snapshot.copy(windows = snapshot.windows.map { it.copy(resetsAt = fixtureTime + 60) })
            }) }
            ActivityScenario.launch<WidgetTestHostActivity>(Intent(context, WidgetTestHostActivity::class.java)
                .putExtra("width", 280).putExtra("height", 150)).use { scenario ->
                fun text(view: View): String = when (view) {
                    is TextView -> view.text.toString()
                    is ViewGroup -> (0 until view.childCount).joinToString(" ") { text(view.getChildAt(it)) }
                    else -> ""
                }
                fun awaitText(expected: String) {
                    val deadline = SystemClock.elapsedRealtime() + 15_000
                    var actual = ""
                    while (SystemClock.elapsedRealtime() < deadline) {
                        scenario.onActivity { actual = text(it.widgetView) }
                        if (actual.contains(expected)) return
                        Thread.sleep(100)
                    }
                    fail("Missing $expected after system broadcast; widget=$actual")
                }
                runBlocking { app.updateWidgets() }
                awaitText("示例数据 · 06:59")
                shell("cmd alarm set-timezone America/New_York")
                // No explicit update: the system broadcast must repaint the same cached timestamp.
                awaitText("示例数据 · 01:59")
                awaitText("03/08 03:00 重置")
                assertEquals(fixtureTime, app.repository.state.value.snapshot?.fetchedAt)
                setTime((fixtureTime + 120) * 1000)
                awaitText("重置待确认")
                awaitText("62%")
                setTime((fixtureTime + 86400) * 1000)
                awaitText("重置待确认")
                assertEquals("Clock passage must not invent fresh quota", fixtureTime, app.repository.state.value.snapshot?.fetchedAt)
            }
            runBlocking {
                app.repository.logout()
                app.repository.connect(Session("test-access", "test-refresh", "test-account", Long.MAX_VALUE))
                val before = app.usageRequests.get()
                setTime(System.currentTimeMillis() - 120_000)
                app.usedPercent = 17
                assertEquals(SyncResult.SUCCESS, app.repository.refresh())
                assertEquals(before + 1, app.usageRequests.get())
                assertEquals(17.0, app.repository.state.value.snapshot?.windows?.single()?.usedPercent)
            }
        } finally {
            shell("cmd alarm set-timezone $zone")
            shell("cmd alarm set-time ${originalTime + SystemClock.elapsedRealtime() - originalElapsed}")
            for ((key, value) in listOf("auto_time" to autoTime, "auto_time_zone" to autoZone)) {
                shell(if (value == "null") "settings delete global $key" else "settings put global $key $value")
            }
            runBlocking { app.repository.logout(); app.reconcileSync(); app.updateWidgets() }
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }
}
