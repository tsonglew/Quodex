package com.tsonglew.quodex.subscriptions.browser

import android.os.*
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.tsonglew.quodex.R
import com.tsonglew.quodex.data.GlassPreferences
import com.tsonglew.quodex.i18n.AppLanguages
import com.tsonglew.quodex.i18n.localTextResources
import com.tsonglew.quodex.ui.*

/** A normal official login page in a service-specific browser profile. No password/token text fields exist in native UI. */
abstract class BrowserLoginActivity : AppCompatActivity() {
    protected abstract val profile: BrowserProvider
    private var reply: ResultReceiver? = null
    private var delivered = false
    private val views = mutableListOf<QuotaBrowserView>()
    private var page by mutableStateOf(BrowserPageState())
    private val closeForCleanup: () -> Unit = { complete(BrowserSession.CANCELLED) }
    private lateinit var frame: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLanguages.delegateInitialized()
        if (BrowserProvider.forProcess(packageName, android.app.Application.getProcessName()) != profile) { finish(); return }
        reply = BrowserSession.receiver(intent.extras, BrowserSession.REPLY)
        if (reply == null) { finish(); return }
        BrowserWindows.closeAll()
        BrowserWindows.close += closeForCleanup
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        reply?.send(BrowserSession.READY, Bundle().apply {
            putParcelable(BrowserSession.CANCEL_RECEIVER, BrowserSession.remote(object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    if (resultCode == BrowserSession.CLOSE) complete(BrowserSession.CANCELLED)
                }
            }))
        })
        frame = FrameLayout(this)
        try {
            val first = createBrowser()
            show(first)
            first.loadUrl(profile.dashboard)
        } catch (_: Exception) { complete(BrowserSession.FAILED); return }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val current = views.lastOrNull()
                when {
                    current?.canGoBack() == true -> current.goBack()
                    views.size > 1 && current != null -> closePopup(current)
                    else -> complete(BrowserSession.CANCELLED)
                }
            }
        })
        val glass = GlassPreferences(intent.getFloatExtra("light-tint", 1f), intent.getFloatExtra("dark-tint", 1f),
            intent.getBooleanExtra("reduce-transparency", false))
        setContent {
            QuotaTheme(intent.getStringExtra("theme") ?: "system", glass) {
                val texts = localTextResources()
                Column(Modifier.fillMaxSize().glassSceneBackground().safeDrawingPadding().imePadding()) {
                    GlassPanel(modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                                Text(texts(R.string.subscription_browser_title, profile.provider.displayName), style = MaterialTheme.typography.titleMedium)
                                Text(page.host.ifBlank { java.net.URI(profile.origin).host }, style = MaterialTheme.typography.labelSmall, maxLines = 2)
                            }
                            TextButton(onClick = { complete(BrowserSession.CANCELLED) }) { Text(texts(R.string.close)) }
                        }
                    }
                    if (page.loading || page.status == BrowserReadStatus.READING) LinearProgressIndicator(Modifier.fillMaxWidth())
                    AndroidView(factory = { frame }, modifier = Modifier.fillMaxWidth().weight(1f))
                    GlassPanel(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(texts(when (page.status) {
                                BrowserReadStatus.READING -> R.string.subscription_browser_reading
                                BrowserReadStatus.RETRY -> R.string.subscription_browser_retry
                                BrowserReadStatus.LIMITED -> R.string.subscription_browser_limited
                                BrowserReadStatus.BLOCKED -> R.string.subscription_browser_blocked
                                else -> R.string.subscription_browser_sign_in
                            }), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { views.lastOrNull()?.readAgain() }, enabled = page.status != BrowserReadStatus.READING) {
                                Text(texts(R.string.subscription_browser_read))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun createBrowser() = QuotaBrowserView(this, profile, onQuota = { raw ->
        complete(BrowserSession.SUCCESS, Bundle().apply { putString(BrowserSession.QUOTA, raw) })
    }, onState = { view, value -> if (views.lastOrNull() == view) page = value }, onPopup = { view, open ->
        if (open) show(view) else closePopup(view)
    })

    private fun show(view: QuotaBrowserView) {
        views.lastOrNull()?.visibility = android.view.View.GONE
        views += view
        view.isSaveEnabled = false
        frame.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun closePopup(view: QuotaBrowserView) {
        if (views.size <= 1 || view == views.first()) return
        views.remove(view)
        frame.removeView(view)
        view.destroy()
        views.lastOrNull()?.apply { visibility = android.view.View.VISIBLE; readAgain() }
    }

    private fun complete(code: Int, data: Bundle = Bundle.EMPTY) {
        if (delivered) return
        delivered = true
        reply?.send(code, data)
        disposeBrowsers()
        finish()
    }

    private fun disposeBrowsers() {
        views.forEach { (it.parent as? ViewGroup)?.removeView(it); it.destroy() }
        views.clear()
    }

    override fun onDestroy() {
        BrowserWindows.close -= closeForCleanup
        disposeBrowsers()
        if (isFinishing && !delivered) reply?.send(BrowserSession.CANCELLED, Bundle.EMPTY)
        super.onDestroy()
    }
}
class KimiLoginActivity : BrowserLoginActivity() { override val profile = BrowserProvider.KIMI }
class WorkBuddyLoginActivity : BrowserLoginActivity() { override val profile = BrowserProvider.WORKBUDDY }
class GrokLoginActivity : BrowserLoginActivity() { override val profile = BrowserProvider.GROK }
