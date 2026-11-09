package com.tsonglew.quodex.subscriptions.browser

import android.accounts.AccountManager
import android.app.Activity
import android.content.ActivityNotFoundException
import android.os.*
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toArgb
import com.tsonglew.quodex.R
import com.tsonglew.quodex.data.GlassPreferences
import com.tsonglew.quodex.i18n.AppLanguages
import com.tsonglew.quodex.i18n.AndroidTextResources
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
    private var accountTarget: Pair<QuotaBrowserView, Long>? = null
    private val accountPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val target = accountTarget.also { accountTarget = null } ?: return@registerForActivityResult
        if (result.resultCode != Activity.RESULT_OK || result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE) != "com.google") return@registerForActivityResult
        val email = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME) ?: return@registerForActivityResult
        if (views.lastOrNull() !== target.first) return@registerForActivityResult
        target.first.fillGoogleAccount(email, target.second) { filled ->
            if (!filled && !isFinishing && !isDestroyed) notice(R.string.subscription_browser_google_field)
        }
    }

    private fun chooseGoogleAccount() {
        val browser = views.lastOrNull() ?: return
        if (!GoogleAccountSelection.canFill(browser.url)) return
        accountTarget = browser to browser.documentRevision
        try {
            accountPicker.launch(AccountManager.newChooseAccountIntent(null, null, arrayOf("com.google"), null, null, null, null))
        } catch (_: ActivityNotFoundException) {
            accountTarget = null
            notice(R.string.subscription_browser_google_unavailable)
        } catch (_: SecurityException) {
            accountTarget = null
            notice(R.string.subscription_browser_google_unavailable)
        }
    }

    private fun notice(resource: Int) = Toast.makeText(this, AndroidTextResources(this)(resource), Toast.LENGTH_LONG).show()

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
        val layout = BrowserLoginLayout(this)
        frame = layout.browserContainer
        setContentView(layout)
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
        layout.header.setContent {
            QuotaTheme(intent.getStringExtra("theme") ?: "system", glass) {
                val background = MaterialTheme.colorScheme.background.toArgb()
                SideEffect { layout.setBackgroundColor(background) }
                BrowserLoginHeader(profile, page) { complete(BrowserSession.CANCELLED) }
            }
        }
        layout.footer.setContent {
            QuotaTheme(intent.getStringExtra("theme") ?: "system", glass) {
                BrowserLoginFooter(page, ::chooseGoogleAccount) { views.lastOrNull()?.readAgain() }
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
        accountTarget = null
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
