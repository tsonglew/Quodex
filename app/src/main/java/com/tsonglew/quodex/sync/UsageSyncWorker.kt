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
        app.diagnostics.record(DiagnosticEvent.WORK_START, runAttemptCount.toLong())
        val outcome = app.repository.refresh(inputData.getBoolean("force", false))
        app.diagnostics.record(DiagnosticEvent.WORK_END, outcome.ordinal.toLong())
        app.updateWidgets()
        val retry = outcome == SyncResult.RETRY || outcome == SyncResult.DEFERRED && inputData.getBoolean("force", false)
        // Bound this work cycle; a later periodic cycle or explicit user action can try again.
        return when {
            !retry -> Result.success()
            runAttemptCount < 2 -> Result.retry()
            else -> Result.failure()
        }
    }
}

object SyncScheduler {
    private const val PERIODIC = "codex-periodic-sync"
    private const val MANUAL = "codex-manual-sync"
    fun schedule(context: Context, minutes: Long): Operation {
        context.quotaApp.diagnostics.record(DiagnosticEvent.SCHEDULE, minutes)
        val request = PeriodicWorkRequestBuilder<UsageSyncWorker>(minutes, TimeUnit.MINUTES)
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
    fun diagnosticReport(context: Context): String = listOf(PERIODIC, MANUAL).joinToString("\n", postfix = "\n") { name ->
        runCatching {
            val work = WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get(2, TimeUnit.SECONDS)
            val active = work.filter { !it.state.isFinished }
            "$name active=${active.size}: " + active.take(5).joinToString { "${it.state}/attempt=${it.runAttemptCount}" }
        }.getOrElse { "$name status=unavailable" }
    }

    fun cancel(context: Context) {
        context.quotaApp.diagnostics.record(DiagnosticEvent.CANCEL_WORK)
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        WorkManager.getInstance(context).cancelUniqueWork(MANUAL)
    }
}
