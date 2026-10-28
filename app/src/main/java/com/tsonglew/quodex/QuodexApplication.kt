package com.tsonglew.quodex

import com.tsonglew.quodex.diagnostics.*
import java.io.File
import android.app.Application
import android.content.Context
import android.content.ComponentName
import android.appwidget.AppWidgetManager
import androidx.room.Room
import com.tsonglew.quodex.data.*
import com.tsonglew.quodex.launcher.DesktopQuotaIcon
import com.tsonglew.quodex.sync.SyncScheduler
import com.tsonglew.quodex.widget.QuotaWidget
import com.tsonglew.quodex.widget.QuotaWidgetReceiver
import com.tsonglew.quodex.widget.SlimQuotaWidget
import com.tsonglew.quodex.widget.SlimQuotaWidgetReceiver
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.tsonglew.quodex.update.*

open class QuodexApplication : Application() {
    val diagnostics by lazy { Diagnostics(File(noBackupFilesDir, "diagnostics/events.log")) }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableWallClockRevision = MutableStateFlow(0L)
    val wallClockRevision = mutableWallClockRevision.asStateFlow()
    fun wallClockChanged() { mutableWallClockRevision.update { it + 1 } }
    open val api by lazy { CodexApi(diagnostics = diagnostics) }
    val settings by lazy { SettingsStore(this) }
    val desktopIcon by lazy { DesktopQuotaIcon(this) }
    open val updateSource: UpdateSource by lazy {
        GitHubUpdateClient(BuildConfig.VERSION_CODE.toLong(), installedSigningCertificate(this))
    }
    val updates by lazy { UpdateRepository(updateSource, AndroidUpdateStore(this), AndroidUpdateNotifier(this), BuildConfig.VERSION_CODE.toLong()) }
    val repository by lazy {
        UsageRepository(api, CredentialVault(this), Room.databaseBuilder(this, QuotaDatabase::class.java, "quota.db").build().snapshots(), settings, diagnostics = diagnostics)
    }
    override fun onCreate() {
        super.onCreate()
        Thread.getDefaultUncaughtExceptionHandler()?.let { previous ->
            Thread.setDefaultUncaughtExceptionHandler(CrashRecorder(previous, diagnostics, android.os.Looper.getMainLooper().thread))
        }
        scope.launch {
            diagnostics.record(DiagnosticEvent.APP_START)
            repository.initialize()
            desktopIcon.update()
        }
        scope.launch {
            try {
                updates.initialize()
                AppUpdateScheduler.reconcile(this@QuodexApplication, updates.state.value.saved.enabled)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* A storage error must not prevent quota display. */ }
        }
    }
    suspend fun updateWidgets() = withContext(Dispatchers.IO) {
        desktopIcon.update()
        diagnostics.record(DiagnosticEvent.WIDGET_START)
        try {
            QuotaWidget().updateAll(this@QuodexApplication); SlimQuotaWidget().updateAll(this@QuodexApplication)
            diagnostics.record(DiagnosticEvent.WIDGET_END)
        } catch (failure: Exception) {
            diagnostics.record(DiagnosticEvent.WIDGET_ERROR)
            throw failure
        }
    }
    suspend fun reconcileSync() {
        if (backgroundSyncRequired()) SyncScheduler.schedule(this, settings.read().refreshMinutes)
        else SyncScheduler.cancel(this)
    }
    suspend fun backgroundSyncRequired(): Boolean {
        repository.initialize()
        val state = repository.state.value
        if (!state.connected || state.demo) return false
        val manager = AppWidgetManager.getInstance(this)
        val hasWidget = listOf(QuotaWidgetReceiver::class.java, SlimQuotaWidgetReceiver::class.java).any {
            manager.getAppWidgetIds(ComponentName(this, it)).isNotEmpty()
        }
        val preferences = settings.read()
        val hasIcon = preferences.desktopIconEnabled && desktopIcon.refreshAvailability().pinned
        return hasWidget || hasIcon
    }
}

val Context.quotaApp: QuodexApplication get() = applicationContext as QuodexApplication
