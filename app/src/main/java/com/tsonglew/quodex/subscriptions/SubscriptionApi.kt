package com.tsonglew.quodex.subscriptions

import com.tsonglew.quodex.data.*
import com.tsonglew.quodex.network.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun interface SubscriptionSource {
    suspend fun fetch(provider: SubscriptionProvider, credential: String): SubscriptionSnapshot
}

class SubscriptionApi(
    client: OkHttpClient = OkHttpClient(),
    private val endpoints: Map<SubscriptionProvider, String> = mapOf(
        SubscriptionProvider.DEEPSEEK to "https://api.deepseek.com/user/balance",
        SubscriptionProvider.OPENCODE_GO to "https://opencode.ai/zen/go/v1/usage",
        SubscriptionProvider.OPENCODE_ZEN to "https://opencode.ai/console/api/billing/status",
        SubscriptionProvider.CURSOR to "https://cursor.com/api/usage-summary",
    ),
    private val now: () -> Long = { Instant.now().epochSecond },
    private val retry: RequestRetry = RequestRetry(now),
) : SubscriptionSource {
    // A redirect must never carry an API key or session cookie to a second host.
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).cookieJar(CookieJar.NO_COOKIES)
        .callTimeout(30, TimeUnit.SECONDS).connectTimeout(15, TimeUnit.SECONDS).build()

    override suspend fun fetch(provider: SubscriptionProvider, credential: String): SubscriptionSnapshot {
        require(validCredential(credential))
        val request = Request.Builder().url(endpoints.getValue(provider))
            .header("Accept", "application/json").header("User-Agent", "Quodex/0.1 Android")
            .apply {
                if (provider == SubscriptionProvider.CURSOR) {
                    header("Cookie", "WorkosCursorSessionToken=$credential")
                    header("Origin", "https://cursor.com")
                } else header("Authorization", "Bearer $credential")
            }.build()
        val raw = retry.run(classify = { error ->
            (error as? ApiFailure)?.takeIf { it.retryable }?.let { RetryDirective(it.retryAt) }
        }) { send(request) }
        return SubscriptionParser.parse(provider, raw, now())
    }

    private suspend fun send(request: Request): String = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(
                        ApiFailure(FailureKind.NETWORK, retryable = isTransientTransport(e)))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            if (!it.isSuccessful) {
                                val kind = when (it.code) {
                                    401 -> FailureKind.AUTH
                                    403 -> FailureKind.FORBIDDEN
                                    408 -> FailureKind.NETWORK
                                    429 -> FailureKind.LIMITED
                                    in 500..599 -> FailureKind.SERVER
                                    else -> FailureKind.PROTOCOL
                                }
                                throw ApiFailure(kind, retryAfterAt(it.header("Retry-After"), now())
                                    ?: if (it.code == 429) now() + 60 else null,
                                    it.code in setOf(408, 429, 500, 502, 503, 504))
                            }
                            val source = it.body?.source() ?: throw ApiFailure(FailureKind.PROTOCOL)
                            source.request(256 * 1024 + 1L)
                            if (source.buffer.size > 256 * 1024) throw ApiFailure(FailureKind.PROTOCOL)
                            val raw = source.readUtf8()
                            if (continuation.isActive) continuation.resume(raw)
                        } catch (failure: Exception) {
                            val safe = when (failure) {
                                is ApiFailure -> failure
                                is IOException -> ApiFailure(FailureKind.NETWORK, retryable = isTransientTransport(failure))
                                else -> ApiFailure(FailureKind.PROTOCOL)
                            }
                            if (continuation.isActive) continuation.resumeWithException(safe)
                        }
                    }
                }
            })
        }
    }

    companion object {
        fun validCredential(value: String): Boolean = value.length in 1..8192 &&
            value.all { it.code in 33..126 && it !in ";,\"\\" }
    }
}
