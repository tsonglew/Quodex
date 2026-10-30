package com.tsonglew.quodex.update

import com.tsonglew.quodex.i18n.*
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.*
import com.tsonglew.quodex.R
import com.tsonglew.quodex.quotaApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

private val Context.appUpdatePreferences by preferencesDataStore("app_updates")

class AndroidUpdateStore(context: Context) : UpdateStore {
    private val store = context.applicationContext.appUpdatePreferences
    private val key = stringPreferencesKey("state")
    private val json = Json { ignoreUnknownKeys = true }
    override suspend fun read(): SavedUpdates = store.data.first()[key]?.let { json.decodeFromString<SavedUpdates>(it) } ?: SavedUpdates()
    override suspend fun write(value: SavedUpdates) { store.edit { it[key] = json.encodeToString(value) } }
}

internal fun installedSigningCertificate(context: Context): String {
    val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    val signature = info.signingInfo?.apkContentsSigners?.singleOrNull() ?: return ""
    return MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
}

class AndroidUpdateNotifier(private val context: Context) : UpdateNotifier {
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    fun enabled(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE

    override fun show(update: AppUpdate): Boolean {
        val texts = AndroidTextResources(context)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        manager.createNotificationChannel(NotificationChannel(CHANNEL, texts(R.string.version_updates), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = texts(R.string.alerts_when_a_compatible_quodex_update_is)
        })
        if (!enabled()) return false
        val intent = Intent(context, com.tsonglew.quodex.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context, NOTIFICATION_ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return try {
            manager.notify(NOTIFICATION_ID, NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_update_notification)
                .setContentTitle(texts(R.string.quodex_value_is_available, update.versionName))
                .setContentText(texts(R.string.tap_to_download_and_install_the_update))
                .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true)
                .build())
            true
        } catch (_: SecurityException) { false }
    }

    fun refreshIfVisible(update: AppUpdate) {
        if (manager.activeNotifications.any { it.id == NOTIFICATION_ID }) show(update)
    }

    override fun clear() { manager.cancel(NOTIFICATION_ID) }

    companion object {
        const val CHANNEL = "app_updates"
        private const val NOTIFICATION_ID = 2001
    }
}

class AppUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            // The HTTP client retries within this check. Do not multiply that budget here.
            if (applicationContext.quotaApp.updates.check()) Result.success() else Result.failure()
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { Result.failure() }
    }
}

object AppUpdateScheduler {
    const val WORK_NAME = "app-update-check"
    fun reconcile(context: Context, enabled: Boolean): Operation {
        val work = WorkManager.getInstance(context)
        if (!enabled) return work.cancelUniqueWork(WORK_NAME)
        val request = PeriodicWorkRequestBuilder<AppUpdateWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
            .setInitialDelay(12, TimeUnit.HOURS)
            .build()
        return work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
