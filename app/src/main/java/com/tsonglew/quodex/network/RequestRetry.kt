package com.tsonglew.quodex.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.net.ssl.SSLException

data class RetryDirective(val retryAt: Long? = null)

/** Three total attempts; only caller-classified transient failures enter this loop. */
class RequestRetry(
    private val now: () -> Long = { Instant.now().epochSecond },
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val maxAttempts: Int = 3,
) {
    init { require(maxAttempts in 1..3) }

    suspend fun <T> run(
        enabled: Boolean = true,
        classify: (Exception) -> RetryDirective?,
        onRetry: (Int, Long) -> Unit = { _, _ -> },
        operation: suspend () -> T,
    ): T {
        var attempt = 1
        while (true) {
            currentCoroutineContext().ensureActive()
            try { return operation() }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                if (!enabled || attempt >= maxAttempts) throw failure
                val directive = classify(failure) ?: throw failure
                val seconds = directive.retryAt?.let { (it - now()).coerceAtLeast(0) } ?: 0
                // Long server cooldowns belong to the next scheduled/user attempt.
                // Never shorten Retry-After to fit an interactive request's budget.
                if (seconds > 30) throw failure
                val wait = maxOf(1_000L shl (attempt - 1), seconds * 1_000)
                onRetry(++attempt, wait)
                pause(wait)
            }
        }
    }
}

/** Transport exceptions are marked at socket boundaries, never around disk writes. */
class TransportFailure(cause: IOException) : IOException("Network request interrupted", cause)
class HttpFailure(val status: Int, val retryAt: Long? = null, val rateLimited: Boolean = status == 429) : IOException("HTTP $status") {
    val retryable get() = rateLimited || status in setOf(408, 500, 502, 503, 504)
}

// OkHttp reports a truncated Content-Length body as ProtocolException, so socket
// protocol failures are eligible too. Invalid application data is classified separately.
fun isTransientTransport(failure: IOException): Boolean = failure !is SSLException
fun networkRetry(failure: Exception): RetryDirective? = when (failure) {
    is TransportFailure -> if (isTransientTransport(failure.cause as IOException)) RetryDirective() else null
    is HttpFailure -> if (failure.retryable) RetryDirective(failure.retryAt) else null
    else -> null
}

fun retryAfterAt(header: String?, now: Long): Long? {
    if (header == null) return null
    header.trim().toLongOrNull()?.let { seconds ->
        if (seconds < 0) return null
        return try { Math.addExact(now, seconds) } catch (_: ArithmeticException) { Long.MAX_VALUE }
    }
    return runCatching { ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond() }.getOrNull()
}
