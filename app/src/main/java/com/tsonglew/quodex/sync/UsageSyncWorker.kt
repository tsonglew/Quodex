package com.tsonglew.quodex.sync

import com.tsonglew.quodex.diagnostics.DiagnosticEvent
import android.content.Context
import androidx.work.*
import com.tsonglew.quodex.data.SyncResult
import com.tsonglew.quodex.quotaApp
import java.util.concurrent.TimeUnit

class UsageSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.quotaApp
        // Launchers do not broadcast shortcut removal. Check before the next periodic request.
        if (inputData.getBoolean("periodic", false)) {
            app.repository.initialize()
            if (app.repository.state.value.connected && !app.backgroundSyncRequired()) {
                SyncScheduler.cancelPeriodic(applicationContext)
                return Result.success()
            }
        }
        app.diagnostics.record(DiagnosticEvent.WORK_START, runAttemptCount.toLong())
        // WorkManager owns this operation's retry budget; do not nest HTTP retries.
        val outcome = app.repository.refresh(inputData.getBoolean("force", false), retryNetwork = false)
        app.diagnostics.record(DiagnosticEvent.WORK_END, outcome.ordinal.toLong())
        app.updateWidgets()
        if (app.hasTokenHeatmapWidgets() && app.repository.state.value.snapshot != null) {
            // The token repository has its own hourly cache and Retry-After policy.
            // Render quota first so an optional statistics request cannot hold it up.
            val tokenOutcome = com.tsonglew.quodex.widget.refreshTokenWidget(applicationContext, force = false)
            if (tokenOutcome == SyncResult.RETRY || tokenOutcome == SyncResult.DEFERRED) SyncScheduler.refreshTokens(applicationContext, force = false)
            app.updateWidgets()
        }
        val retry = outcome == SyncResult.RETRY || outcome == SyncResult.DEFERRED && inputData.getBoolean("force", false)
        // Bound this work cycle; a later periodic cycle or explicit user action can try again.
        return when {
            outcome == SyncResult.PERMANENT_FAILURE -> Result.failure()
            !retry -> Result.success()
            runAttemptCount < 2 -> Result.retry()
            else -> Result.failure()
        }
    }
}

class TokenWidgetSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val outcome = com.tsonglew.quodex.widget.refreshTokenWidget(applicationContext, inputData.getBoolean("force", false))
        applicationContext.quotaApp.updateWidgets()
        if (outcome == SyncResult.PERMANENT_FAILURE) return Result.failure()
        return if (outcome == SyncResult.RETRY || outcome == SyncResult.DEFERRED) {
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        } else Result.success()
    }
}

object SyncScheduler {
    private const val PERIODIC = "codex-periodic-sync"
    private const val MANUAL = "codex-manual-sync"
    private const val TOKENS = "codex-token-widget-sync"
    fun refreshTokens(context: Context, force: Boolean = true): Operation = WorkManager.getInstance(context).enqueueUniqueWork(
        TOKENS, if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<TokenWidgetSyncWorker>()
            .setInputData(workDataOf("force" to force))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    fun schedule(context: Context, minutes: Long): Operation {
        context.quotaApp.diagnostics.record(DiagnosticEvent.SCHEDULE, minutes)
        val request = PeriodicWorkRequestBuilder<UsageSyncWorker>(minutes, TimeUnit.MINUTES)
            .setInputData(workDataOf("periodic" to true))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        return WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
    fun refresh(context: Context): Operation {
        val request = OneTimeWorkRequestBuilder<UsageSyncWorker>().setInputData(workDataOf("force" to true))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .apply {
                // Older systems require a foreground notification for expedited work.
                if (android.os.Build.VERSION.SDK_INT >= 31)
                    setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }.build()
        // A new user attempt must not be hidden behind a previous retry's backoff.
        return WorkManager.getInstance(context).enqueueUniqueWork(MANUAL, ExistingWorkPolicy.REPLACE, request)
    }

    /** Called on an IO dispatcher. Do not expose WorkManager UUIDs or input/output data. */
    fun diagnosticReport(context: Context): String = listOf(PERIODIC, MANUAL, TOKENS).joinToString("\n", postfix = "\n") { name ->
        runCatching {
            val work = WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get(2, TimeUnit.SECONDS)
            val active = work.filter { !it.state.isFinished }
            "$name active=${active.size}: " + active.take(5).joinToString { "${it.state}/attempt=${it.runAttemptCount}" }
        }.getOrElse { "$name status=unavailable" }
    }

    fun cancel(context: Context) {
        context.quotaApp.diagnostics.record(DiagnosticEvent.CANCEL_WORK)
        cancelPeriodic(context)
        WorkManager.getInstance(context).cancelUniqueWork(MANUAL)
        WorkManager.getInstance(context).cancelUniqueWork(TOKENS)
    }

    fun cancelPeriodic(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(PERIODIC) }
}
