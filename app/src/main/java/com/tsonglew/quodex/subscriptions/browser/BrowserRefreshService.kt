package com.tsonglew.quodex.subscriptions.browser

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.*
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.tsonglew.quodex.data.ApiFailure
import com.tsonglew.quodex.data.FailureKind

/** One bounded read in the existing profile; no window, credentials or authentication UI cross IPC. */
internal class BrowserRefreshRequest(
    private val context: Context,
    private val profile: BrowserProvider,
    private val complete: (String?, ApiFailure?) -> Unit,
    private val intercept: ((WebResourceRequest) -> WebResourceResponse?)? = null,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var view: QuotaBrowserView? = null
    private var finished = false
    private val deadline = Runnable { finish(null, ApiFailure(FailureKind.NETWORK)) }

    fun start() {
        if (finished) return
        handler.postDelayed(deadline, 40_000)
        try {
            view = QuotaBrowserView(context, profile, { finish(it, null) }, { _, state ->
                state.failure?.let { finish(null, ApiFailure(it, state.retryAt.takeIf { time -> time > 0 })) }
            }, { popup, _ -> popup.destroy() }, intercept, interactive = false)
            view?.loadUrl(profile.dashboard)
        } catch (_: Exception) { finish(null, ApiFailure(FailureKind.PROTOCOL)) }
    }

    private fun finish(raw: String?, failure: ApiFailure?) {
        if (finished) return
        close()
        complete(raw, failure)
    }

    fun close() {
        finished = true
        handler.removeCallbacks(deadline)
        view?.destroy()
        view = null
    }
}

abstract class BrowserRefreshService : Service() {
    companion object {
        const val READ = 1
        const val CANCEL = 2
        const val REQUEST_ID = "refresh-request-id"
    }
    protected abstract val profile: BrowserProvider
    protected open fun intercept(request: WebResourceRequest): WebResourceResponse? = null
    private var request: BrowserRefreshRequest? = null
    private var reply: ResultReceiver? = null
    private var requestId: String? = null
    private val closeForCleanup: () -> Unit = {
        reply?.send(BrowserSession.FAILED, Bundle().apply { putString("failure", FailureKind.AUTH.name) })
        close()
    }

    // onBind is not called for every client while a Service remains bound. Send each
    // request explicitly so a rapid cancel/rebind cannot reuse an obsolete callback.
    private val endpoint = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            when (message.what) {
                READ -> {
                    closeForCleanup()
                    requestId = message.data.getString(REQUEST_ID) ?: return
                    reply = BrowserSession.receiver(message.data, BrowserSession.REPLY) ?: return
                    startRead()
                }
                CANCEL -> if (requestId == message.data.getString(REQUEST_ID)) close()
            }
        }
    })

    override fun onCreate() {
        super.onCreate()
        check(BrowserProvider.forProcess(packageName, android.app.Application.getProcessName()) == profile)
        BrowserWindows.close += closeForCleanup
    }

    override fun onBind(intent: Intent): IBinder = endpoint.binder

    private fun startRead() {
        request = BrowserRefreshRequest(this, profile, { raw, failure ->
            reply?.send(if (raw != null) BrowserSession.SUCCESS else BrowserSession.FAILED, Bundle().apply {
                putString(BrowserSession.QUOTA, raw)
                putString("failure", failure?.kind?.name)
                putLong("retry-at", failure?.retryAt ?: 0)
            })
        }, ::intercept)
        request?.start()
    }

    private fun close() { request?.close(); request = null; reply = null; requestId = null }
    override fun onUnbind(intent: Intent): Boolean { close(); return false }
    override fun onDestroy() {
        BrowserWindows.close -= closeForCleanup
        close()
        super.onDestroy()
    }
}

class KimiBrowserRefreshService : BrowserRefreshService() { override val profile = BrowserProvider.KIMI }
class WorkBuddyBrowserRefreshService : BrowserRefreshService() { override val profile = BrowserProvider.WORKBUDDY }
class GrokBrowserRefreshService : BrowserRefreshService() { override val profile = BrowserProvider.GROK }
