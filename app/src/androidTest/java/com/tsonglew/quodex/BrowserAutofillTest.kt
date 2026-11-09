package com.tsonglew.quodex

import android.webkit.WebResourceResponse
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.tsonglew.quodex.subscriptions.browser.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BrowserAutofillTest {
    @Test fun passwordManagerReceivesWebsiteFieldsInsideLoginLayout() {
        val inst = InstrumentationRegistry.getInstrumentation()
        fun shell(command: String): String = inst.uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()
        }
        val previous = shell("settings get secure autofill_service")
        shell("settings put secure autofill_service com.tsonglew.quodex/.FixtureAutofillService")
        FixtureAutofillService.requests.clear()
        try {
            ActivityScenario.launch(AutofillTestHostActivity::class.java).use { scenario ->
                lateinit var browser: QuotaBrowserView
                val loaded = CountDownLatch(1)
                scenario.onActivity { activity ->
                    browser = QuotaBrowserView(activity, BrowserProvider.GROK, {}, { _, state ->
                        if (!state.loading) loaded.countDown()
                    }, { _, _ -> }, intercept = {
                        WebResourceResponse("text/html", "UTF-8", """<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><style>body{padding:24px}input{display:block;padding:12px;margin:12px 0;width:80%}</style><form><label>Email<input id="email" type="email" autocomplete="username"></label><label>Password<input id="password" type="password" autocomplete="current-password"></label></form>""".byteInputStream())
                    })
                    val layout = BrowserLoginLayout(activity)
                    layout.header.setContent { androidx.compose.material3.Text("Synthetic official login") }
                    layout.footer.setContent { androidx.compose.material3.Text("Choose account") }
                    layout.browserContainer.addView(browser)
                    activity.setContentView(layout)
                    browser.loadUrl("https://accounts.google.com/signin/identifier")
                }
                try {
                    assertTrue(loaded.await(15, TimeUnit.SECONDS))
                    val drawn = CountDownLatch(1)
                    scenario.onActivity { browser.postVisualStateCallback(1, object : android.webkit.WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) { drawn.countDown() }
                    }) }
                    assertTrue(drawn.await(10, TimeUnit.SECONDS))
                    inst.waitForIdleSync()
                    inst.uiAutomation.waitForIdle(300, 5000)
                    val position = IntArray(2)
                    val ready = CountDownLatch(1)
                    var x = 0f; var y = 0f
                    scenario.onActivity {
                        browser.getLocationOnScreen(position)
                        browser.evaluateJavascript("JSON.stringify((()=>{const r=document.getElementById('email').getBoundingClientRect();return [(r.left+r.width/2)/innerWidth,(r.top+r.height/2)/innerWidth]})())") { raw ->
                            val coords = org.json.JSONArray(org.json.JSONTokener(raw).nextValue() as String)
                            x = position[0] + coords.getDouble(0).toFloat() * browser.width
                            y = position[1] + coords.getDouble(1).toFloat() * browser.width
                            ready.countDown()
                        }
                    }
                    assertTrue(ready.await(5, TimeUnit.SECONDS))
                    shell("input tap ${x.toInt()} ${y.toInt()}")
                    val focused = CountDownLatch(1)
                    var focusedField = ""
                    scenario.onActivity { browser.evaluateJavascript("document.activeElement.id") { focusedField = it; focused.countDown() } }
                    assertTrue(focused.await(5, TimeUnit.SECONDS))
                    assertEquals("\"email\"", focusedField)
                    val fields = FixtureAutofillService.requests.poll(15, TimeUnit.SECONDS)
                    assertNotNull("Password manager received no request", fields)
                    assertTrue("Login form fields were omitted from Android autofill", fields!!.any { it.htmlInfo?.attributes?.any { a -> a.first == "type" && a.second == "email" } == true })
                    var suggestion: android.view.accessibility.AccessibilityNodeInfo? = null
                    val deadline = android.os.SystemClock.uptimeMillis() + 10000
                    while (suggestion == null && android.os.SystemClock.uptimeMillis() < deadline) {
                        suggestion = inst.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Quodex synthetic saved account")?.firstOrNull()
                        if (suggestion == null) android.os.SystemClock.sleep(100)
                    }
                    assertNotNull("Saved-account suggestion was not displayed", suggestion)
                    var clickable = suggestion!!
                    while (!clickable.isClickable && clickable.parent != null) clickable = clickable.parent
                    assertTrue(clickable.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                    var values = ""
                    val filledDeadline = android.os.SystemClock.uptimeMillis() + 5000
                    while (!values.contains("synthetic-password") && android.os.SystemClock.uptimeMillis() < filledDeadline) {
                        val done = CountDownLatch(1)
                        scenario.onActivity { browser.evaluateJavascript("document.getElementById('email').value + ':' + document.getElementById('password').value") { values = it; done.countDown() } }
                        assertTrue(done.await(5, TimeUnit.SECONDS))
                        if (!values.contains("synthetic-password")) android.os.SystemClock.sleep(100)
                    }
                    assertEquals("\"saved@example.test:synthetic-password\"", values)
                } finally { scenario.onActivity { browser.destroy() } }
            }
        } finally {
            if (previous == "null" || previous.isBlank()) shell("settings delete secure autofill_service")
            else shell("settings put secure autofill_service $previous")
            FixtureAutofillService.requests.clear()
        }
    }
}
