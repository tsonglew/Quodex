package com.tsonglew.quodex.widget

import com.tsonglew.quodex.data.SyncResult
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/** A user click gets an immediate attempt instead of waiting for an ordinary scheduled job. */
internal suspend fun refreshFromWidget(
    refresh: suspend () -> SyncResult,
    enqueue: suspend () -> Unit,
    update: suspend () -> Unit,
    timeoutMillis: Long = 8_000,
) = coroutineScope {
    val attempt = async(start = CoroutineStart.UNDISPATCHED) { withTimeoutOrNull(timeoutMillis) { refresh() } }
    // Start a Glance session while the request is pending so its state collector can
    // render progress, rather than waking the widget only after the request ends.
    if (!attempt.isCompleted) update()
    val result = attempt.await()
    if (result == null || result == SyncResult.RETRY || result == SyncResult.DEFERRED) enqueue()
    update()
}
