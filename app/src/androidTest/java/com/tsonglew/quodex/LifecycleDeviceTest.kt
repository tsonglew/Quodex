package com.tsonglew.quodex

import android.Manifest
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Process
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.tsonglew.quodex.data.Session
import com.tsonglew.quodex.widget.QuotaWidgetReceiver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** scripts/lifecycle_smoke.sh supplies the external process death between phases. */
class LifecycleDeviceTest {
    @Test fun connectedCacheAndWidgetRecoverAfterExternalProcessDeath() {
        val phase = InstrumentationRegistry.getArguments().getString("lifecyclePhase")
        assumeTrue("Requires the external lifecycle harness", phase == "seed" || phase == "restore")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.quotaApp as TestQuotaApplication
        val fixture = context.getSharedPreferences("lifecycle-test", Context.MODE_PRIVATE)
        val host = AppWidgetHost(context, 907)
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.BIND_APPWIDGET)
        try {
            if (phase == "seed") {
                host.deleteHost()
                runBlocking {
                    app.repository.logout()
                    app.usedPercent = 38
                    app.repository.connect(Session("test-access", "test-refresh", "test-account", Long.MAX_VALUE))
                }
                val id = host.allocateAppWidgetId()
                assertTrue(AppWidgetManager.getInstance(context).bindAppWidgetIdIfAllowed(id,
                    ComponentName(context, QuotaWidgetReceiver::class.java)))
                runBlocking { app.settings.saveWidget(id, false, "dark"); app.updateWidgets(); app.reconcileSync() }
                assertTrue(fixture.edit().putInt("pid", Process.myPid()).putInt("widget", id)
                    .putLong("version", context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode)
                    .putLong("fetchedAt", requireNotNull(app.repository.state.value.snapshot).fetchedAt).commit())
                return
            }
            assertNotEquals("Harness must actually replace the app process", fixture.getInt("pid", -1), Process.myPid())
            if (InstrumentationRegistry.getArguments().getString("lifecycleRestart") == "upgrade") {
                assertTrue("Upgrade must install a newer version", context.packageManager.getPackageInfo(context.packageName, 0)
                    .longVersionCode > fixture.getLong("version", Long.MAX_VALUE))
            }
            val id = fixture.getInt("widget", AppWidgetManager.INVALID_APPWIDGET_ID)
            assertNotNull("Platform widget binding must survive", AppWidgetManager.getInstance(context).getAppWidgetInfo(id))
            runBlocking { app.repository.initialize() }
            val restored = app.repository.state.value
            assertTrue(restored.connected)
            assertFalse(restored.demo)
            assertEquals("test-account", restored.snapshot?.accountId)
            assertEquals(fixture.getLong("fetchedAt", -1), restored.snapshot?.fetchedAt)
            assertEquals(false to "dark", runBlocking { app.settings.widgetSettings(id) })
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
                runBlocking { app.reconcileSync(); app.updateWidgets() }
                assertEquals(1, WorkManager.getInstance(context).getWorkInfosForUniqueWork("codex-periodic-sync")
                    .get(10, TimeUnit.SECONDS).count { !it.state.isFinished })
            }
            ActivityScenario.launch<WidgetTestHostActivity>(Intent(context, WidgetTestHostActivity::class.java)
                .putExtra("hostId", 907).putExtra("existingWidgetId", id)).use { scenario ->
                fun labels(view: View): List<String> = when (view) {
                    is TextView -> listOf(view.text.toString())
                    is ViewGroup -> (0 until view.childCount).flatMap { labels(view.getChildAt(it)) }
                    else -> emptyList()
                }
                runBlocking { app.updateWidgets() }
                val deadline = System.currentTimeMillis() + 20_000
                var rendered = false
                while (!rendered && System.currentTimeMillis() < deadline) {
                    scenario.onActivity { rendered = "38%" in labels(it.widgetView) }
                    if (!rendered) Thread.sleep(100)
                }
                assertTrue("Retained widget must render the stored used quota", rendered)
            }
            assertEquals("Fresh cache recovery must not fetch a replacement", 0, app.usageRequests.get())
            assertEquals(fixture.getLong("fetchedAt", -1), app.repository.state.value.snapshot?.fetchedAt)
        } finally {
            if (phase == "restore") {
                host.deleteHost()
                runBlocking { app.repository.logout(); app.reconcileSync() }
                fixture.edit().clear().commit()
            }
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }
}
