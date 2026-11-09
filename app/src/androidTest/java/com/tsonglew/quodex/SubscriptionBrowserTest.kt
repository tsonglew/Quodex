package com.tsonglew.quodex

import android.webkit.*
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.tsonglew.quodex.subscriptions.SubscriptionParser
import com.tsonglew.quodex.subscriptions.browser.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** All page and API traffic is intercepted. Synthetic login values never reach a network. */
class SubscriptionBrowserTest {
    private fun response(body: String, html: Boolean = false, status: Int = 200) = WebResourceResponse(
        if (html) "text/html" else "application/json", "UTF-8", status, if (status == 200) "OK" else "Unavailable",
        mapOf("Cache-Control" to "no-store"), body.byteInputStream())

    @Test fun boundProfileServiceHandlesRepeatedRequestsAndIgnoresObsoleteCancellation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val connected = CountDownLatch(1)
        val endpoint = AtomicReference<android.os.Messenger>()
        val connection = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName, binder: android.os.IBinder) {
                endpoint.set(android.os.Messenger(binder)); connected.countDown()
            }
            override fun onServiceDisconnected(name: android.content.ComponentName) = Unit
        }
        val marker = java.io.File(context.noBackupFilesDir, "browser-profiles/${BrowserProvider.GROK.profile}")
        marker.parentFile!!.mkdirs(); marker.createNewFile()
        assertTrue(context.bindService(android.content.Intent(context, GrokRefreshProbeService::class.java), connection,
            android.content.Context.BIND_AUTO_CREATE))
        try {
            assertTrue(connected.await(20, TimeUnit.SECONDS))
            for (index in 1..2) {
                val done = CountDownLatch(1)
                val quota = AtomicReference<String>()
                val code = AtomicInteger()
                val failure = AtomicReference<String>()
                val reply = object : android.os.ResultReceiver(android.os.Handler(android.os.Looper.getMainLooper())) {
                    override fun onReceiveResult(resultCode: Int, data: android.os.Bundle?) {
                        code.set(resultCode); quota.set(data?.getString(BrowserSession.QUOTA)); failure.set(data?.getString("failure")); done.countDown()
                    }
                }
                endpoint.get().send(android.os.Message.obtain().apply {
                    what = BrowserRefreshService.READ
                    data = android.os.Bundle().apply {
                        putString(BrowserRefreshService.REQUEST_ID, "read-$index")
                        putParcelable(BrowserSession.REPLY, BrowserSession.remote(reply))
                    }
                })
                endpoint.get().send(android.os.Message.obtain().apply {
                    what = BrowserRefreshService.CANCEL
                    data = android.os.Bundle().apply { putString(BrowserRefreshService.REQUEST_ID, "obsolete") }
                })
                assertTrue(done.await(30, TimeUnit.SECONDS))
                assertEquals("IPC read $index: ${failure.get()}", BrowserSession.SUCCESS, code.get())
                assertTrue(requireNotNull(quota.get()).contains("\"usedCents\":$index"))
                assertFalse(quota.get().contains("fixture-ipc"))
            }
        } finally {
            context.unbindService(connection)
            kotlinx.coroutines.runBlocking { BrowserSession.clear(context, BrowserProvider.GROK.provider) }
        }
    }

    @Test fun invisibleRefreshReusesLoginForRepeatedReadsAndReportsVerificationAndRateLimit() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val profile = BrowserProvider.GROK
        val calls = AtomicInteger()
        var login = true
        var status = 200
        var used = 37
        // No Activity or window is created by the reader; all traffic stays inside fixtures.
        fun read(): Pair<String?, com.tsonglew.quodex.data.ApiFailure?> {
            val done = CountDownLatch(1)
            val quota = AtomicReference<String>()
            val error = AtomicReference<com.tsonglew.quodex.data.ApiFailure>()
            lateinit var request: BrowserRefreshRequest
            instrumentation.runOnMainSync {
                request = BrowserRefreshRequest(context, profile, { raw, failure ->
                    quota.set(raw); error.set(failure); done.countDown()
                }, intercept = { resource ->
                    when {
                        resource.url.path == "/dashboard/usage" -> response(
                            if (login) "<script>document.cookie='sso=fixture-refresh; path=/; Secure'</script>" else "<p>Signed in</p>", html = true)
                        resource.url.path.orEmpty().contains("usage-status") -> {
                            calls.incrementAndGet()
                            if (status == 429) WebResourceResponse("application/json", "UTF-8", 429, "Limited",
                                mapOf("Retry-After" to "120"), "{}".byteInputStream())
                            else response("""{"availability":"available","weeklyIncludedUsage":{"usedCents":$used,"budgetCents":100}}""", status = status)
                        }
                        else -> response("{}", status = 404)
                    }
                })
                request.start()
            }
            try { assertTrue("Silent browser read did not finish", done.await(45, TimeUnit.SECONDS)) }
            finally { instrumentation.runOnMainSync { request.close() } }
            return quota.get() to error.get()
        }
        val first = read()
        assertNull(first.second)
        assertTrue(requireNotNull(first.first).contains("37"))
        login = false // The second reader must use the first reader's persisted cookie.
        used = 80
        val next = read()
        assertNull(next.second)
        assertTrue(requireNotNull(next.first).contains("80"))
        assertEquals(2, calls.get())
        status = 401
        val expired = read()
        assertNull(expired.first)
        assertEquals(com.tsonglew.quodex.data.FailureKind.AUTH, expired.second?.kind)
        status = 429
        val limited = read()
        assertEquals(com.tsonglew.quodex.data.FailureKind.LIMITED, limited.second?.kind)
        assertTrue(requireNotNull(limited.second?.retryAt) > java.time.Instant.now().epochSecond + 90)
    }

    @Test fun officialPageReadersReturnOnlyUsableQuotaWithoutExportingLoginSecrets() {
        for (profile in BrowserProvider.entries) {
            val done = CountDownLatch(1)
            val quota = AtomicReference<String>()
            val calls = AtomicInteger()
            val failure = AtomicReference<Throwable>()
            lateinit var browser: QuotaBrowserView
            ActivityScenario.launch(BrowserTestHostActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    browser = QuotaBrowserView(activity, profile, { quota.set(it); done.countDown() }, { _, _ -> }, { _, _ -> }, intercept = { request ->
                        try {
                            check(request.url.host == java.net.URI(profile.origin).host)
                            val path = request.url.path.orEmpty()
                            when {
                                path == java.net.URI(profile.dashboard).path -> response("""<!doctype html><meta name="viewport" content="width=device-width"><h1>Synthetic login fixture</h1><script>
                                    localStorage.setItem('access_token','fixture-only-token');
                                    document.cookie='session=fixture-only-session; path=/; Secure';
                                    document.cookie='session_2=fixture-only-chunk; path=/; Secure';
                                    document.cookie='sso=fixture-only-sso; path=/; Secure';</script>""", html = true)
                                path.contains("GetSubscriptionStats") -> {
                                    assertEquals("Bearer fixture-only-token", request.requestHeaders.entries.first { it.key.equals("authorization", true) }.value)
                                    response("""{"subscriptionBalance":{"unit":"UNIT_CREDIT","amountUsedRatio":0.28}}""")
                                }
                                path.contains("get-user-resource") -> {
                                    val index = calls.incrementAndGet()
                                    response("""{"code":0,"data":{"Response":{"Data":{"TotalCount":2,"Accounts":[{"AccountId":$index,"UserId":"discard-me","CapacityUnit":"credits","CycleCapacityRemainPrecise":"60","CycleCapacitySizePrecise":"100","PackageName":"Free Plan"}]}}}}""")
                                }
                                path.contains("usage-status") -> response("""{"availability":"available","weeklyIncludedUsage":{"usedCents":37,"budgetCents":100}}""")
                                path.contains("get-complat-credit-balance") -> response("{}", status = 503)
                                else -> response("{}", status = 404)
                            }
                        } catch (error: Throwable) { failure.set(error); done.countDown(); response("{}", status = 500) }
                    })
                    activity.setContentView(browser)
                    browser.loadUrl(profile.dashboard)
                }
                try {
                    assertTrue("$profile did not return quota", done.await(30, TimeUnit.SECONDS))
                    failure.get()?.let { throw AssertionError("intercepted request failed", it) }
                    val raw = requireNotNull(quota.get())
                    assertFalse(raw.contains("fixture-only"))
                    assertFalse(raw.contains("discard-me"))
                    assertEquals(profile.provider, SubscriptionParser.parse(profile.provider, raw, 1).provider)
                    if (profile == BrowserProvider.WORKBUDDY) assertEquals(2, calls.get())
                } finally { scenario.onActivity { browser.destroy() } }
            }
        }
    }

    @Test fun foreignPageCannotReadQuotaAndDestroyStopsPolling() {
        val calls = AtomicInteger()
        val received = AtomicInteger()
        val loaded = CountDownLatch(1)
        lateinit var browser: QuotaBrowserView
        ActivityScenario.launch(BrowserTestHostActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                browser = QuotaBrowserView(activity, BrowserProvider.KIMI, { received.incrementAndGet() }, { _, state ->
                    if (!state.loading) loaded.countDown()
                }, { _, _ -> }, intercept = {
                    calls.incrementAndGet()
                    response("<script>localStorage.setItem('access_token','fixture-only-token')</script>", html = true)
                })
                activity.setContentView(browser)
                browser.loadUrl("https://untrusted.example/")
            }
            assertTrue(loaded.await(20, TimeUnit.SECONDS))
            Thread.sleep(1800)
            assertEquals(0, received.get())
            val before = calls.get()
            scenario.onActivity { browser.destroy() }
            Thread.sleep(1800)
            assertEquals(before, calls.get())
            assertEquals(0, received.get())
        }
    }

    @Test fun browserProfilesPersistSeparatelyAndDisconnectClearsOnlySelectedCookies() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activities = mapOf(BrowserProvider.KIMI to KimiProfileProbeActivity::class.java,
            BrowserProvider.WORKBUDDY to WorkBuddyProfileProbeActivity::class.java,
            BrowserProvider.GROK to GrokProfileProbeActivity::class.java)
        fun probe(profile: BrowserProvider, value: String? = null): String {
            val done = CountDownLatch(1)
            val result = AtomicReference<String>()
            val reply = object : android.os.ResultReceiver(android.os.Handler(android.os.Looper.getMainLooper())) {
                override fun onReceiveResult(code: Int, data: android.os.Bundle?) {
                    result.set(data?.getString("value")); done.countDown()
                }
            }
            instrumentation.runOnMainSync {
                context.startActivity(android.content.Intent(context, activities.getValue(profile))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(BrowserSession.REPLY, BrowserSession.remote(reply)).putExtra("value", value))
            }
            assertTrue("$profile probe did not respond", done.await(30, TimeUnit.SECONDS))
            return requireNotNull(result.get())
        }
        ActivityScenario.launch(BrowserTestHostActivity::class.java).use {
            try {
                for (profile in BrowserProvider.entries) {
                    java.io.File(context.noBackupFilesDir, "browser-profiles/${profile.profile}").apply {
                        parentFile!!.mkdirs(); createNewFile()
                    }
                    assertEquals("fixture=${profile.name}", probe(profile, profile.name))
                }
                for (profile in BrowserProvider.entries) assertEquals("fixture=${profile.name}", probe(profile))
                kotlinx.coroutines.runBlocking { BrowserSession.clear(context, BrowserProvider.WORKBUDDY.provider) }
                assertEquals("", probe(BrowserProvider.WORKBUDDY))
                assertEquals("fixture=KIMI", probe(BrowserProvider.KIMI))
                assertEquals("fixture=GROK", probe(BrowserProvider.GROK))
            } finally {
                kotlinx.coroutines.runBlocking { BrowserProvider.entries.forEach { BrowserSession.clear(context, it.provider) } }
            }
        }
    }

    @Test fun loginActivitiesAndCleanupServicesArePrivateAndUseSeparateProcesses() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = context.packageManager.getPackageInfo(context.packageName,
            android.content.pm.PackageManager.GET_ACTIVITIES or android.content.pm.PackageManager.GET_SERVICES)
        val activities = info.activities.orEmpty().filter { it.name.contains("subscriptions.browser") }
        val services = info.services.orEmpty().filter { it.name.contains("subscriptions.browser") }
        assertEquals(3, activities.size)
        assertEquals(6, services.size)
        for (profile in BrowserProvider.entries) {
            val name = "${context.packageName}:${profile.profile}"
            assertEquals(1, activities.count { it.processName == name && !it.exported })
            assertEquals(2, services.count { it.processName == name && !it.exported })
            val rotation = android.content.pm.ActivityInfo.CONFIG_ORIENTATION or android.content.pm.ActivityInfo.CONFIG_SCREEN_SIZE
            assertEquals(rotation, activities.single { it.processName == name }.configChanges and rotation)
        }
    }
}
