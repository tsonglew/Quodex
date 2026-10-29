package com.tsonglew.quodex.launcher

import com.tsonglew.quodex.i18n.AndroidTextResources
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.PersistableBundle
import com.tsonglew.quodex.MainActivity
import com.tsonglew.quodex.QuodexApplication
import com.tsonglew.quodex.diagnostics.DiagnosticEvent
import com.tsonglew.quodex.quotaApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class DesktopIconAvailability(val supported: Boolean = false, val pinned: Boolean = false)
enum class PinIconResult { REQUESTED, UPDATED, UNSUPPORTED, FAILED }

/** A stable pinned shortcut lets launchers update the bitmap without replacing app components. */
class DesktopQuotaIcon(private val app: QuodexApplication) {
    private val manager get() = app.getSystemService(ShortcutManager::class.java)
    private val lock = Mutex()
    private val mutableAvailability = MutableStateFlow(DesktopIconAvailability())
    val availability = mutableAvailability.asStateFlow()
    private var lastPublished: DesktopIconContent? = null

    suspend fun refreshAvailability(): DesktopIconAvailability = withContext(Dispatchers.IO) {
        try {
            val shortcuts = manager
            DesktopIconAvailability(shortcuts?.isRequestPinShortcutSupported == true,
                shortcuts?.pinnedShortcuts?.any { it.id == SHORTCUT_ID } == true).also {
                mutableAvailability.value = it
            }
        } catch (_: RuntimeException) {
            app.diagnostics.record(DiagnosticEvent.DESKTOP_ICON_ERROR)
            mutableAvailability.value
        }
    }

    suspend fun update(): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            try {
                if (!refreshAvailability().pinned) {
                    lastPublished = null
                    return@withLock true
                }
                app.repository.initialize()
                val content = desktopIconContent(app.repository.state.value, app.settings.read(), AndroidTextResources(app))
                if (lastPublished == content) return@withLock true
                val shortcuts = manager ?: return@withLock false
                // Restored shortcuts may be disabled. They should still open the app after logout.
                shortcuts.enableShortcuts(listOf(SHORTCUT_ID))
                shortcuts.updateShortcuts(listOf(shortcut(content))).also { success ->
                    if (success) lastPublished = content
                    else app.diagnostics.record(DiagnosticEvent.DESKTOP_ICON_ERROR)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // A launcher failure must not prevent saving quota or updating widgets.
                app.diagnostics.record(DiagnosticEvent.DESKTOP_ICON_ERROR)
                false
            }
        }
    }

    suspend fun requestPin(): PinIconResult {
        try {
            val status = refreshAvailability()
            if (status.pinned) return if (update()) PinIconResult.UPDATED else PinIconResult.FAILED
            if (!status.supported) return PinIconResult.UNSUPPORTED
            val shortcut = withContext(Dispatchers.IO) {
                app.repository.initialize()
                shortcut(desktopIconContent(app.repository.state.value, app.settings.read(), AndroidTextResources(app)))
            }
            val callback = PendingIntent.getBroadcast(app, 0,
                Intent(app, DesktopIconPinnedReceiver::class.java).setAction(ACTION_PINNED),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val requested = withContext(Dispatchers.Main) {
                manager?.requestPinShortcut(shortcut, callback.intentSender) == true
            }
            // A positive return only means the launcher accepted the request, not user confirmation.
            return if (requested) PinIconResult.REQUESTED else PinIconResult.UNSUPPORTED
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            app.diagnostics.record(DiagnosticEvent.DESKTOP_ICON_ERROR)
            return PinIconResult.FAILED
        }
    }

    private fun shortcut(content: DesktopIconContent): ShortcutInfo = ShortcutInfo.Builder(app, SHORTCUT_ID)
        .setActivity(ComponentName(app, MainActivity::class.java))
        .setShortLabel("Quodex")
        .setLongLabel(content.description)
        .setIcon(Icon.createWithAdaptiveBitmap(QuotaIconRenderer.render(app, content)))
        .setIntent(Intent(app, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        .setExtras(PersistableBundle().apply {
            putInt("remaining_percent", content.percent ?: -1)
            putString("quota_status", content.status.name)
            putBoolean("show_percentage", content.showPercentage)
        })
        .build()

    companion object {
        const val SHORTCUT_ID = "remaining-quota"
        internal const val ACTION_PINNED = "com.tsonglew.quodex.DESKTOP_ICON_PINNED"
    }
}

class DesktopIconPinnedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DesktopQuotaIcon.ACTION_PINNED) return
        val pending = goAsync()
        val app = context.quotaApp
        app.scope.launch {
            try {
                app.desktopIcon.update()
                app.reconcileSync()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                app.diagnostics.record(DiagnosticEvent.DESKTOP_ICON_ERROR)
            } finally {
                pending.finish()
            }
        }
    }
}
