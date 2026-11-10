package com.tsonglew.quodex.subscriptions.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.webkit.*
import com.tsonglew.quodex.data.AppJson
import com.tsonglew.quodex.data.FailureKind
import com.tsonglew.quodex.subscriptions.SubscriptionParser
import kotlinx.serialization.json.*
import java.net.URI
import java.time.Instant
import java.util.UUID

internal enum class BrowserReadStatus { LOGIN, READING, RETRY, LIMITED, BLOCKED, AUTH_REQUIRED }
internal data class BrowserPageState(val host: String = "", val loading: Boolean = true,
    val status: BrowserReadStatus = BrowserReadStatus.LOGIN, val retryAt: Long = 0,
    val failure: FailureKind? = null, val documentReady: Boolean = false)

/** No JavaScript interface is exposed to pages. Only a nonce-scoped result on the exact origin is read. */
@SuppressLint("SetJavaScriptEnabled")
internal class QuotaBrowserView(
    context: Context,
    private val provider: BrowserProvider,
    private val onQuota: (String) -> Unit,
    private val onState: (QuotaBrowserView, BrowserPageState) -> Unit,
    private val onPopup: (QuotaBrowserView, Boolean) -> Unit,
    private val intercept: ((WebResourceRequest) -> WebResourceResponse?)? = null,
    private val interactive: Boolean = true,
) : WebView(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val reader = context.assets.open("subscription_quota_reader.js").bufferedReader().use { it.readText() }
    private var revision = 0L
    val documentRevision: Long get() = revision
    private var active = true
    private var pending = false
    private var automatic = true
    private var nextRead = 0L
    private var pageState = BrowserPageState()
    private val poll = object : Runnable {
        override fun run() {
            if (!active) return
            if (!pageState.loading && !pending && automatic && now() >= nextRead && provider.canRead(url) && hasLoginCookie()) readQuota()
            handler.postDelayed(this, 1500)
        }
    }

    init {
        id = android.view.View.generateViewId()
        importantForAutofill = if (interactive) IMPORTANT_FOR_AUTOFILL_YES else IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            safeBrowsingEnabled = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        setDownloadListener { _, _, _, _, _ -> state(pageState.copy(status = BrowserReadStatus.BLOCKED)) }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val blocked = if (interactive) !provider.canNavigate(request.url.toString())
                    else !provider.canRead(request.url.toString())
                if (blocked) state(pageState.copy(status = BrowserReadStatus.BLOCKED, failure = FailureKind.AUTH))
                return blocked
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = intercept?.invoke(request)
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                revision++
                pending = false
                automatic = true
                // A server cooldown survives navigation and cannot be reset by reloading.
                nextRead = maxOf(nextRead.takeIf { pageState.status == BrowserReadStatus.LIMITED } ?: 0, pageState.retryAt)
                state(BrowserPageState(host = runCatching { URI(url).host }.getOrNull().orEmpty(),
                    status = if (now() < pageState.retryAt) BrowserReadStatus.LIMITED else BrowserReadStatus.LOGIN,
                    retryAt = pageState.retryAt))
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (!active) return
                if (!interactive && (!provider.canRead(url) || !hasLoginCookie())) {
                    automatic = false
                    state(pageState.copy(loading = false, status = BrowserReadStatus.AUTH_REQUIRED, failure = FailureKind.AUTH))
                } else state(pageState.copy(loading = false, documentReady =
                    pageState.status != BrowserReadStatus.RETRY &&
                        !(pageState.status == BrowserReadStatus.BLOCKED && pageState.failure == FailureKind.NETWORK)))
            }
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                automatic = false
                state(pageState.copy(loading = false, status = BrowserReadStatus.BLOCKED, failure = FailureKind.NETWORK, documentReady = false))
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    automatic = false
                    state(pageState.copy(loading = false, status = BrowserReadStatus.RETRY, failure = FailureKind.NETWORK, documentReady = false))
                }
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                automatic = false
                state(pageState.copy(loading = false, status = BrowserReadStatus.RETRY, failure = FailureKind.NETWORK, documentReady = false))
                if (active) {
                    (parent as? android.view.ViewGroup)?.removeView(this@QuotaBrowserView)
                    destroy()
                }
                return true
            }
        }
        webChromeClient = object : WebChromeClient() {
            // Provider console output may contain account data; it is not App diagnostics.
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean = true
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                if (!interactive || !isUserGesture) return false
                val child = QuotaBrowserView(context, provider, onQuota, onState, onPopup, intercept)
                onPopup(child, true)
                (resultMsg.obj as WebView.WebViewTransport).webView = child
                resultMsg.sendToTarget()
                return true
            }
            override fun onCloseWindow(window: WebView) { onPopup(this@QuotaBrowserView, false) }
        }
        handler.post(poll)
    }

    fun fillGoogleAccount(email: String, expectedRevision: Long, done: (Boolean) -> Unit) {
        val script = GoogleAccountSelection.script(email)
        if (!active || !interactive || revision != expectedRevision || !GoogleAccountSelection.canFill(url) || script == null) {
            done(false)
            return
        }
        evaluateJavascript(script) { result -> done(active && revision == expectedRevision && result == "true") }
    }

    private fun hasLoginCookie(): Boolean {
        if (provider == BrowserProvider.KIMI) return true // Token presence is checked inside the page, never exported.
        val names = CookieManager.getInstance().getCookie(provider.origin).orEmpty().split(';')
            .map { it.substringBefore('=').trim() }.toSet()
        return if (provider == BrowserProvider.WORKBUDDY) names.containsAll(setOf("session", "session_2"))
        else names.any { it in setOf("WorkosCursorSessionToken", "sso", "sso-rw") }
    }

    fun readAgain() {
        if (now() < pageState.retryAt || !active) return
        automatic = true
        nextRead = 0
        if (!provider.canRead(url) || pageState.status == BrowserReadStatus.RETRY || pageState.status == BrowserReadStatus.BLOCKED)
            loadUrl(provider.dashboard)
    }

    private fun readQuota() {
        if (!active || !provider.canRead(url)) return
        pending = true
        state(pageState.copy(status = BrowserReadStatus.READING))
        val page = revision
        val key = "__quodex_${UUID.randomUUID().toString().replace("-", "")}"
        val quotedKey = JsonPrimitive(key).toString()
        val origin = JsonPrimitive(provider.origin).toString()
        val start = """(function(){if(location.origin!==$origin)return;window[$quotedKey]={kind:'pending'};
            ($reader)('${provider.name}').then(function(result){window[$quotedKey]=result;},function(){window[$quotedKey]={kind:'error'};});})()"""
        evaluateJavascript(start, null)
        val expires = android.os.SystemClock.elapsedRealtime() + 25_000
        fun observe() {
            if (!active || page != revision || !provider.canRead(url)) return
            if (android.os.SystemClock.elapsedRealtime() >= expires) {
                pending = false; automatic = false
                state(pageState.copy(status = BrowserReadStatus.RETRY, failure = FailureKind.NETWORK))
                return
            }
            evaluateJavascript("location.origin===$origin ? JSON.stringify(window[$quotedKey] || {kind:'pending'}) : null") { encoded ->
                if (!active || page != revision || !provider.canRead(url)) return@evaluateJavascript
                val result = runCatching {
                    require(encoded.length <= 800_000)
                    AppJson.parseToJsonElement(AppJson.parseToJsonElement(encoded).jsonPrimitive.content).jsonObject
                }.getOrNull()
                when (result?.get("kind")?.jsonPrimitive?.contentOrNull) {
                    "pending", null -> handler.postDelayed({ observe() }, 350)
                    "ok" -> {
                        pending = false; automatic = false
                        val raw = result["raw"]?.jsonPrimitive?.contentOrNull
                        if (raw != null && raw.length <= 131072 && runCatching {
                                SubscriptionParser.parse(provider.provider, raw, now())
                            }.isSuccess) {
                            CookieManager.getInstance().flush()
                            onQuota(raw)
                        } else state(pageState.copy(status = BrowserReadStatus.RETRY, failure = FailureKind.PROTOCOL))
                    }
                    "login", "origin" -> {
                        pending = false; nextRead = now() + 15
                        state(pageState.copy(status = BrowserReadStatus.AUTH_REQUIRED, failure = FailureKind.AUTH))
                    }
                    else -> {
                        pending = false; automatic = false
                        val status = result?.get("status")?.jsonPrimitive?.intOrNull
                        val limited = status == 429
                        val retryAt = if (limited) now() + (result?.get("retrySeconds")?.jsonPrimitive?.longOrNull ?: 60).coerceIn(1, 86400) else 0
                        state(pageState.copy(status = if (limited) BrowserReadStatus.LIMITED else BrowserReadStatus.RETRY, retryAt = retryAt,
                            failure = when { limited -> FailureKind.LIMITED; status == null || status == 0 -> FailureKind.NETWORK
                                status >= 500 -> FailureKind.SERVER; else -> FailureKind.PROTOCOL }))
                    }
                }
            }
        }
        handler.postDelayed({ observe() }, 350)
    }

    private fun state(next: BrowserPageState) { pageState = next; onState(this, next) }
    private fun now() = Instant.now().epochSecond
    override fun destroy() {
        if (!active) return
        active = false
        handler.removeCallbacksAndMessages(null)
        stopLoading()
        super.destroy()
    }
}
