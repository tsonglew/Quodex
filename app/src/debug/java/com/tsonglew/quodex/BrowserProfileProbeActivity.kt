package com.tsonglew.quodex

import android.app.Activity
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import com.tsonglew.quodex.subscriptions.browser.BrowserSession

/** Debug-only, nonexported probes for real separate-process cookie storage; never loads a network page. */
abstract class BrowserProfileProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(BrowserSession.inBrowserProcess(this))
        val view = WebView(this)
        val cookies = CookieManager.getInstance()
        val reply = BrowserSession.receiver(intent.extras, BrowserSession.REPLY)!!
        fun finishProbe() {
            cookies.flush()
            reply.send(BrowserSession.SUCCESS, Bundle().apply {
                putString("value", cookies.getCookie("https://quota.example").orEmpty())
            })
            view.destroy()
            finish()
        }
        val value = intent.getStringExtra("value")
        if (value == null) finishProbe()
        else cookies.setCookie("https://quota.example", "fixture=$value; Secure; Path=/") { finishProbe() }
    }
}
class KimiProfileProbeActivity : BrowserProfileProbeActivity()
class WorkBuddyProfileProbeActivity : BrowserProfileProbeActivity()
class GrokProfileProbeActivity : BrowserProfileProbeActivity()
