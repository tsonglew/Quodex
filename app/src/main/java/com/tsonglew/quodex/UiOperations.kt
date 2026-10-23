package com.tsonglew.quodex

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.retryWhen
import java.io.IOException

/** Retry a temporarily unavailable preferences file without replacing the last successful value. */
internal fun <T> Flow<T>.retrySettings(onFailure: () -> Unit): Flow<T> = retryWhen { cause, attempt ->
    if (cause !is IOException) return@retryWhen false
    onFailure()
    delay(minOf(1_000L * (attempt.coerceAtMost(29) + 1), 30_000L))
    true
}

internal suspend fun runUiOperation(onFailure: () -> Unit, operation: suspend () -> Unit) {
    try {
        operation()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        onFailure()
    }
}
