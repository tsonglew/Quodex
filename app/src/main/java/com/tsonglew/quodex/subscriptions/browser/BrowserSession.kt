package com.tsonglew.quodex.subscriptions.browser

import android.app.Application
import android.app.Service
import android.content.*
import android.os.*
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import com.tsonglew.quodex.data.*
import com.tsonglew.quodex.subscriptions.SubscriptionProvider
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import java.io.File
import kotlin.coroutines.resumeWithException

internal object BrowserSession {
    const val REPLY = "browser-reply"
    const val QUOTA = "browser-quota"
    const val CANCEL_RECEIVER = "browser-cancel"
    const val READY = 1
    const val SUCCESS = 2
    const val CANCELLED = 3
    const val FAILED = 4
    const val CLOSE = 5

    fun inBrowserProcess(context: Context) = BrowserProvider.forProcess(context.packageName, Application.getProcessName()) != null

    fun configureProcess(context: Context): Boolean {
        val profile = BrowserProvider.forProcess(context.packageName, Application.getProcessName()) ?: return false
        // Supported since API 28. Each service has a separate Chromium profile even on older Android releases.
        WebView.setDataDirectorySuffix(profile.profile)
        return true
    }

    @Suppress("DEPRECATION")
    fun receiver(bundle: Bundle?, name: String): ResultReceiver? = bundle?.getParcelable(name)

    // Send the platform Parcelable type, not an anonymous callback class from the caller's classloader.
    fun remote(receiver: ResultReceiver): ResultReceiver {
        val parcel = Parcel.obtain()
        return try {
            receiver.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            ResultReceiver.CREATOR.createFromParcel(parcel)
        } finally { parcel.recycle() }
    }

    private fun activity(profile: BrowserProvider) = when (profile) {
        BrowserProvider.KIMI -> KimiLoginActivity::class.java
        BrowserProvider.WORKBUDDY -> WorkBuddyLoginActivity::class.java
        BrowserProvider.GROK -> GrokLoginActivity::class.java
    }
    private fun cleaner(profile: BrowserProvider) = when (profile) {
        BrowserProvider.KIMI -> KimiBrowserCleanupService::class.java
        BrowserProvider.WORKBUDDY -> WorkBuddyBrowserCleanupService::class.java
        BrowserProvider.GROK -> GrokBrowserCleanupService::class.java
    }

    private fun marker(context: Context, profile: BrowserProvider) = File(context.noBackupFilesDir, "browser-profiles/${profile.profile}")

    suspend fun refresh(context: Context, provider: SubscriptionProvider): String = withContext(Dispatchers.Main.immediate) {
        val profile = BrowserProvider.forProvider(provider) ?: throw ApiFailure(FailureKind.PROTOCOL)
        val service = when (profile) {
            BrowserProvider.KIMI -> KimiBrowserRefreshService::class.java
            BrowserProvider.WORKBUDDY -> WorkBuddyBrowserRefreshService::class.java
            BrowserProvider.GROK -> GrokBrowserRefreshService::class.java
        }
        var bound = false
        var endpoint: Messenger? = null
        val requestId = java.util.UUID.randomUUID().toString()
        lateinit var connection: ServiceConnection
        try {
            withTimeoutOrNull(45_000) {
                suspendCancellableCoroutine { continuation ->
                    val reply = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                        override fun onReceiveResult(code: Int, data: Bundle?) {
                            if (!continuation.isActive) return
                            val raw = data?.getString(QUOTA)
                            if (code == SUCCESS && raw != null && raw.length <= 131072) continuation.resume(raw)
                            else {
                                val kind = FailureKind.entries.find { it.name == data?.getString("failure") } ?: FailureKind.PROTOCOL
                                continuation.resumeWithException(ApiFailure(kind, data?.getLong("retry-at")?.takeIf { it > 0 }))
                            }
                        }
                    }
                    connection = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName, service: IBinder) {
                            if (!continuation.isActive) return
                            endpoint = Messenger(service)
                            try {
                                endpoint?.send(Message.obtain().apply {
                                    what = BrowserRefreshService.READ
                                    data = Bundle().apply {
                                        putString(BrowserRefreshService.REQUEST_ID, requestId)
                                        putParcelable(REPLY, remote(reply))
                                    }
                                })
                            } catch (_: RemoteException) { onServiceDisconnected(name) }
                        }
                        override fun onServiceDisconnected(name: ComponentName) {
                            if (continuation.isActive) continuation.resumeWithException(ApiFailure(FailureKind.NETWORK))
                        }
                        override fun onNullBinding(name: ComponentName) = onServiceDisconnected(name)
                        override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
                    }
                    bound = context.bindService(Intent(context, service), connection, Context.BIND_AUTO_CREATE)
                    if (!bound) continuation.resumeWithException(ApiFailure(FailureKind.NETWORK))
                }
            } ?: throw ApiFailure(FailureKind.NETWORK)
        } finally {
            try {
                endpoint?.send(Message.obtain().apply {
                    what = BrowserRefreshService.CANCEL
                    data = Bundle().apply { putString(BrowserRefreshService.REQUEST_ID, requestId) }
                })
            } catch (_: RemoteException) { /* The remote process is already gone. */ }
            if (bound) context.unbindService(connection)
        }
    }

    suspend fun read(context: Context, profile: BrowserProvider, prefs: PreferencesState): String = withContext(Dispatchers.Main.immediate) {
        withContext(Dispatchers.IO) {
            val file = marker(context, profile)
            check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
            check(file.exists() || file.createNewFile())
        }
        suspendCancellableCoroutine { continuation ->
            var close: ResultReceiver? = null
            val reply = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(code: Int, data: Bundle?) {
                    if (code == READY) {
                        close = receiver(data, CANCEL_RECEIVER)
                        if (!continuation.isActive) close?.send(CLOSE, Bundle.EMPTY)
                        return
                    }
                    if (!continuation.isActive) return
                    when (code) {
                        SUCCESS -> {
                            val raw = data?.getString(QUOTA)
                            if (raw != null && raw.length <= 131072) continuation.resume(raw)
                            else continuation.resumeWithException(ApiFailure(FailureKind.PROTOCOL))
                        }
                        CANCELLED -> continuation.cancel()
                        FAILED -> continuation.resumeWithException(ApiFailure(FailureKind.PROTOCOL))
                    }
                }
            }
            continuation.invokeOnCancellation { close?.send(CLOSE, Bundle.EMPTY) }
            val intent = Intent(context, activity(profile)).putExtra(REPLY, remote(reply))
                .putExtra("theme", prefs.theme).putExtra("light-tint", prefs.glass.lightTint)
                .putExtra("dark-tint", prefs.glass.darkTint).putExtra("reduce-transparency", prefs.glass.reduceTransparency)
            try { context.startActivity(intent) }
            catch (_: Exception) { continuation.resumeWithException(ApiFailure(FailureKind.PROTOCOL)) }
        }
    }

    suspend fun clear(context: Context, provider: SubscriptionProvider) {
        val profile = BrowserProvider.forProvider(provider) ?: return
        if (!withContext(Dispatchers.IO) { marker(context, profile).exists() }) return
        withContext(Dispatchers.Main.immediate) {
            awaitBrowserCleanup {
                var bound = false
                lateinit var connection: ServiceConnection
                try {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        val reply = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                            override fun onReceiveResult(code: Int, data: Bundle?) {
                                if (!continuation.isActive) return
                                if (code == SUCCESS) continuation.resume(Unit)
                                else continuation.resumeWithException(ApiFailure(FailureKind.STORAGE))
                            }
                        }
                        connection = object : ServiceConnection {
                            override fun onServiceConnected(name: ComponentName, service: IBinder) = Unit
                            override fun onServiceDisconnected(name: ComponentName) {
                                if (continuation.isActive) continuation.resumeWithException(ApiFailure(FailureKind.STORAGE))
                            }
                            override fun onNullBinding(name: ComponentName) = onServiceDisconnected(name)
                            override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
                        }
                        bound = context.bindService(Intent(context, cleaner(profile)).putExtra(REPLY, remote(reply)), connection, Context.BIND_AUTO_CREATE)
                        if (!bound) continuation.resumeWithException(ApiFailure(FailureKind.STORAGE))
                    }
                } finally { if (bound) context.unbindService(connection) }
            }
        }
        withContext(Dispatchers.IO) {
            val file = marker(context, profile)
            check(!file.exists() || file.delete())
        }
    }
}

internal object BrowserWindows {
    val close = mutableSetOf<() -> Unit>()
    fun closeAll() { close.toList().forEach { it() } }
}

/** Bound only by this App; browser state never enters the main process or its encrypted quota store. */
abstract class BrowserCleanupService : Service() {
    override fun onBind(intent: Intent): IBinder {
        val reply = BrowserSession.receiver(intent.extras, BrowserSession.REPLY)
        try {
            check(BrowserSession.inBrowserProcess(this))
            BrowserWindows.closeAll()
            WebView(this).apply { clearCache(true); destroy() }
            WebStorage.getInstance().deleteAllData()
            CookieManager.getInstance().removeAllCookies {
                CookieManager.getInstance().flush()
                reply?.send(BrowserSession.SUCCESS, Bundle.EMPTY)
            }
        } catch (_: Exception) { reply?.send(BrowserSession.FAILED, Bundle.EMPTY) }
        return Binder()
    }
}
class KimiBrowserCleanupService : BrowserCleanupService()
class WorkBuddyBrowserCleanupService : BrowserCleanupService()
class GrokBrowserCleanupService : BrowserCleanupService()
