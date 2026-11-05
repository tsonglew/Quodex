package com.tsonglew.quodex

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import com.tsonglew.quodex.data.CodexApi
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.Protocol
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.atomic.AtomicInteger

/** Device tests never send synthetic credentials to a real service. */
class QuotaTestRunner : AndroidJUnitRunner() {
    override fun newApplication(loader: ClassLoader, name: String, context: Context): Application =
        super.newApplication(loader, TestQuotaApplication::class.java.name, context)
}

class TestQuotaApplication : QuodexApplication() {
    val subscriptionRequests = AtomicInteger()
    @Volatile var subscriptionFailure: com.tsonglew.quodex.data.FailureKind? = null
    @Volatile var subscriptionDelayMillis = 0L
    override val subscriptionSource = com.tsonglew.quodex.subscriptions.SubscriptionSource { provider, _ ->
        subscriptionRequests.incrementAndGet()
        kotlinx.coroutines.delay(subscriptionDelayMillis)
        subscriptionFailure?.let { throw com.tsonglew.quodex.data.ApiFailure(it) }
        val raw = when (provider) {
            com.tsonglew.quodex.subscriptions.SubscriptionProvider.DEEPSEEK -> """{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"110","granted_balance":"10","topped_up_balance":"100"}]}"""
            com.tsonglew.quodex.subscriptions.SubscriptionProvider.OPENCODE_GO -> """{"usage":{"rolling":{"status":"ok","percent":24,"resetsAt":"2027-01-01T02:00:00Z"},"weekly":{"status":"ok","percent":38,"resetsAt":"2027-01-04T00:00:00Z"},"monthly":{"status":"ok","percent":46,"resetsAt":"2027-02-01T00:00:00Z"}}}"""
            com.tsonglew.quodex.subscriptions.SubscriptionProvider.OPENCODE_ZEN -> """{"billingMode":"prepaid","balanceMicroCents":"1850000000","availableMicroCents":"1850000000"}"""
            com.tsonglew.quodex.subscriptions.SubscriptionProvider.CURSOR -> """{"membershipType":"pro","billingCycleEnd":"2027-01-01T00:00:00Z","individualUsage":{"plan":{"autoPercentUsed":35,"apiPercentUsed":18,"totalPercentUsed":27}}}"""
        }
        com.tsonglew.quodex.subscriptions.SubscriptionParser.parse(provider, raw, java.time.Instant.now().epochSecond)
    }
    override fun onCreate() {
        // Keep existing Chinese UI assertions deterministic without changing the emulator's system UI language.
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            // The fixture starts with framework locales, so skip AndroidX's one-time
            // legacy-file migration; it can otherwise clear the injected locale on first launch.
            packageManager.setComponentEnabledSetting(android.content.ComponentName(this,
                "androidx.appcompat.app.AppLocalesMetadataHolderService"),
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                android.content.pm.PackageManager.DONT_KILL_APP)
            getSystemService(android.app.LocaleManager::class.java).applicationLocales = android.os.LocaleList.forLanguageTags("zh-Hans")
        } else {
            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(androidx.core.os.LocaleListCompat.forLanguageTags("zh-Hans"))
            // Standalone widget smoke tests do not create an AppCompat Activity first.
            com.tsonglew.quodex.i18n.AppLanguages.delegateInitialized()
        }
        super.onCreate()
    }

    val updateRequests = AtomicInteger()
    @Volatile var availableUpdate: com.tsonglew.quodex.update.AppUpdate? = null
    override val updateSource = com.tsonglew.quodex.update.UpdateSource { updateRequests.incrementAndGet(); availableUpdate }
    val usageRequests = AtomicInteger()
    val tokenUsageRequests = AtomicInteger()
    val resetCreditRequests = AtomicInteger()
    @Volatile var tokenUsageStatusCode = 200
    @Volatile var tokenUsageBody = """{"stats":{"lifetime_tokens":1234567,"peak_daily_tokens":45000,"daily_usage_buckets":[{"start_date":"2026-10-20","tokens":12000},{"start_date":"2026-10-21","tokens":0},{"start_date":"2026-10-22","tokens":45000}]}}"""
    val usageRequestTimes = java.util.concurrent.ConcurrentLinkedQueue<Long>()
    @Volatile var usedPercent = 38
    @Volatile var responseDelayMillis = 0L
    @Volatile var usageResponseGate: java.util.concurrent.CountDownLatch? = null
    @Volatile var usageStatusCode = 200
    @Volatile var usageRetryAfter: String? = null
    @Volatile var usageTransportFailure: java.io.IOException? = null
    override val api by lazy {
        CodexApi(OkHttpClient.Builder().addInterceptor { chain ->
            if (chain.request().url.encodedPath == "/backend-api/wham/rate-limit-reset-credits") {
                check(chain.request().method == "GET")
                resetCreditRequests.incrementAndGet()
                return@addInterceptor Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("Test reset cards")
                    .body("""{"available_count":2,"credits":[{"id":"fixture-only","status":"available","granted_at":"2026-11-01T00:00:00Z","expires_at":"2026-12-01T00:00:00Z"}]}""".toResponseBody("application/json".toMediaType())).build()
            }
            if (chain.request().url.encodedPath == "/backend-api/wham/profiles/me") {
                tokenUsageRequests.incrementAndGet()
                return@addInterceptor Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(tokenUsageStatusCode).message("Test token response")
                    .body(tokenUsageBody.toResponseBody("application/json".toMediaType())).build()
            }
            if (chain.request().url.encodedPath.endsWith("/benefits/chatgpt_api_credits_promo")) {
                return@addInterceptor Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("Synthetic API credit preview")
                    .body("""{"eligible":false,"ineligibility_reason":"not_qualified","offer":null}"""
                        .toResponseBody("application/json".toMediaType())).build()
            }
            if (chain.request().url.encodedPath != "/backend-api/wham/usage") {
                throw java.io.IOException("No external network in device tests")
            }
            usageRequestTimes.add(android.os.SystemClock.elapsedRealtime())
            usageRequests.incrementAndGet()
            Thread.sleep(responseDelayMillis)
            usageResponseGate?.let { gate ->
                if (!gate.await(7, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw java.io.IOException("Test response gate was not released within the widget request budget")
                }
            }
            usageTransportFailure?.let { throw it }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(usageStatusCode).message("Test response")
                .apply { usageRetryAfter?.let { header("Retry-After", it) } }
                .body("""{"rate_limit":{"primary_window":{"used_percent":$usedPercent,"limit_window_seconds":604800}}}"""
                    .toResponseBody("application/json".toMediaType())).build()
        }.build(), diagnostics = diagnostics)
    }
}
