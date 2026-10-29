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
    override fun onCreate() {
        // Keep existing Chinese UI assertions deterministic without changing the emulator's system UI language.
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            getSystemService(android.app.LocaleManager::class.java).applicationLocales = android.os.LocaleList.forLanguageTags("zh-Hans")
        } else {
            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(androidx.core.os.LocaleListCompat.forLanguageTags("zh-Hans"))
        }
        super.onCreate()
    }

    val updateRequests = AtomicInteger()
    @Volatile var availableUpdate: com.tsonglew.quodex.update.AppUpdate? = null
    override val updateSource = com.tsonglew.quodex.update.UpdateSource { updateRequests.incrementAndGet(); availableUpdate }
    val usageRequests = AtomicInteger()
    val tokenUsageRequests = AtomicInteger()
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
            if (chain.request().url.encodedPath == "/backend-api/wham/profiles/me") {
                tokenUsageRequests.incrementAndGet()
                return@addInterceptor Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(tokenUsageStatusCode).message("Test token response")
                    .body(tokenUsageBody.toResponseBody("application/json".toMediaType())).build()
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
