package com.tsonglew.quotapal

import com.tsonglew.quotapal.diagnostics.*
import java.io.File
import android.app.Application
import android.content.Context
import android.content.ComponentName
import android.appwidget.AppWidgetManager
import androidx.room.Room
import com.tsonglew.quotapal.data.*
import com.tsonglew.quotapal.sync.SyncScheduler
import com.tsonglew.quotapal.widget.QuotaWidget
import com.tsonglew.quotapal.widget.QuotaWidgetReceiver
import com.tsonglew.quotapal.widget.SlimQuotaWidget
import com.tsonglew.quotapal.widget.SlimQuotaWidgetReceiver
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.tsonglew.quotapal.update.*

open class QuotaPalApplication : Application() {
    val diagnostics by lazy { Diagnostics(File(noBackupFilesDir, "diagnostics/events.log")) }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableWallClockRevision = MutableStateFlow(0L)
    val wallClockRevision = mutableWallClockRevision.asStateFlow()
    fun wallClockChanged() { mutableWallClockRevision.update { it + 1 } }
    open val api by lazy { CodexApi(diagnostics = diagnostics) }
    val settings by lazy { SettingsStore(this) }
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
        scope.launch { diagnostics.record(DiagnosticEvent.APP_START); repository.initialize() }
        scope.launch {
            try {
                updates.initialize()
                AppUpdateScheduler.reconcile(this@QuotaPalApplication, updates.state.value.saved.enabled)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* A storage error must not prevent quota display. */ }
        }
    }
    suspend fun updateWidgets() = withContext(Dispatchers.IO) {
        diagnostics.record(DiagnosticEvent.WIDGET_START)
        try {
            QuotaWidget().updateAll(this@QuotaPalApplication); SlimQuotaWidget().updateAll(this@QuotaPalApplication)
            diagnostics.record(DiagnosticEvent.WIDGET_END)
        } catch (failure: Exception) {
            diagnostics.record(DiagnosticEvent.WIDGET_ERROR)
            throw failure
        }
    }
    suspend fun reconcileSync() {
        repository.initialize()
        val state = repository.state.value
        val manager = AppWidgetManager.getInstance(this)
        val hasWidget = listOf(QuotaWidgetReceiver::class.java, SlimQuotaWidgetReceiver::class.java).any {
            manager.getAppWidgetIds(ComponentName(this, it)).isNotEmpty()
        }
        if (state.connected && !state.demo && hasWidget) SyncScheduler.schedule(this, settings.read().refreshMinutes)
        else SyncScheduler.cancel(this)
    }
}

val Context.quotaApp: QuotaPalApplication get() = applicationContext as QuotaPalApplication
