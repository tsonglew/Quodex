package com.tsonglew.quodex

import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.tsonglew.quodex.subscriptions.browser.*
import com.tsonglew.quodex.ui.QuotaTheme
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Every document is intercepted. The selected email is synthetic; Google is never contacted. */
class GoogleAccountBrowserTest {
    @Test fun selectedEmailStaysOnTheChosenGoogleDocumentAndNeverSubmits() {
        val inst = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(AutofillTestHostActivity::class.java).use { scenario ->
            lateinit var browser: QuotaBrowserView
            var page by mutableStateOf(BrowserPageState(host = "accounts.google.com", loading = false))
            val loaded = LinkedBlockingQueue<Boolean>()
            scenario.onActivity { activity ->
                    activity.enableEdgeToEdge()
                browser = QuotaBrowserView(activity, BrowserProvider.GROK, {}, { _, state -> if (!state.loading) loaded.offer(true) }, { _, _ -> }, intercept = {
                    WebResourceResponse("text/html", "UTF-8", """<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><style>body{font:18px sans-serif;padding:24px}input{display:block;margin:12px 0;padding:12px;width:85%}</style><h2>登录表单测试样例</h2><p>合成数据 · 不连接 Google</p><form onsubmit="window.submitted=true;return false"><label>邮箱<input id="identifierId" type="email" autocomplete="username"></label><label>密码<input id="password" type="password" value="untouched" autocomplete="current-password"></label></form><script>window.changes=0;window.submitted=false;identifierId.addEventListener('input',()=>window.changes++);identifierId.addEventListener('change',()=>window.changes++);</script>""".byteInputStream())
                })
                val layout = BrowserLoginLayout(activity)
                layout.header.setContent { QuotaTheme("light") { BrowserLoginHeader(BrowserProvider.GROK, page, layout.compactChrome) {} } }
                layout.footer.setContent { QuotaTheme("light") { BrowserLoginFooter(page, {}, layout.compactChrome) {} } }
                layout.browserContainer.addView(browser)
                activity.setContentView(layout)
            }
            fun load(url: String) {
                loaded.clear()
                scenario.onActivity { browser.loadUrl(url) }
                assertNotNull("Fixture page did not load", loaded.poll(15, TimeUnit.SECONDS))
                val drawn = CountDownLatch(1)
                scenario.onActivity { browser.postVisualStateCallback(1, object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) { drawn.countDown() }
                }) }
                assertTrue(drawn.await(10, TimeUnit.SECONDS))
            }
            fun evaluate(script: String): String {
                val done = CountDownLatch(1); var result = ""
                scenario.onActivity { browser.evaluateJavascript(script) { result = it; done.countDown() } }
                assertTrue(done.await(5, TimeUnit.SECONDS)); return result
            }
            fun fill(email: String, revision: Long): Boolean {
                val done = CountDownLatch(1); var filled = false
                scenario.onActivity { browser.fillGoogleAccount(email, revision) { filled = it; done.countDown() } }
                assertTrue(done.await(5, TimeUnit.SECONDS)); return filled
            }
            try {
                load("https://accounts.google.com/signin/identifier")
                var revision = 0L
                scenario.onActivity { revision = browser.documentRevision }
                val selected = "a'\"\\b@example.test"
                assertTrue(fill(selected, revision))
                val values = org.json.JSONArray(org.json.JSONTokener(evaluate("JSON.stringify([identifierId.value,password.value,window.changes,window.submitted])")).nextValue() as String)
                assertEquals(selected, values.getString(0))
                assertEquals("untouched", values.getString(1))
                assertEquals(2, values.getInt(2))
                assertFalse(values.getBoolean(3))
                evaluate("identifierId.disabled=true")
                assertFalse(fill("other@example.test", revision))
                load("https://accounts.google.com/signin/identifier?step=next")
                assertFalse(fill("stale@example.test", revision))
                assertEquals("\"\"", evaluate("identifierId.value"))
                scenario.onActivity { revision = browser.documentRevision }
                assertTrue(fill("saved@example.test", revision))
                evaluate("document.activeElement.blur()")
                inst.uiAutomation.executeShellCommand("input keyevent 111").close()
                val readyScreenshot = CountDownLatch(1)
                scenario.onActivity { browser.postVisualStateCallback(2, object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) { browser.postOnAnimation { browser.postOnAnimation { readyScreenshot.countDown() } } }
                }) }
                assertTrue(readyScreenshot.await(10, TimeUnit.SECONDS))
                inst.waitForIdleSync()
                inst.uiAutomation.waitForIdle(300, 5000)
                inst.uiAutomation.takeScreenshot()?.let { bitmap ->
                    java.io.File(inst.targetContext.getExternalFilesDir(null), "google-login-alpha40.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                scenario.onActivity { page = page.copy(status = BrowserReadStatus.RETRY) }
                inst.waitForIdleSync()
                inst.uiAutomation.waitForIdle(300, 5000)
                inst.uiAutomation.takeScreenshot()?.let { bitmap ->
                    java.io.File(inst.targetContext.getExternalFilesDir(null), "google-login-recovery-alpha40.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                load("https://accounts.google.com.evil.test/signin")
                scenario.onActivity { revision = browser.documentRevision }
                assertFalse(fill("selected@example.test", revision))
                assertEquals("false", evaluate(GoogleAccountSelection.script("selected@example.test")!!))
                assertEquals("\"\"", evaluate("identifierId.value"))
            } finally {
                scenario.onActivity { browser.destroy() }
            }
        }
    }
}
