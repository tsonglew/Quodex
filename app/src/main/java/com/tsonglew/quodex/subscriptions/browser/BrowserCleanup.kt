package com.tsonglew.quodex.subscriptions.browser

import com.tsonglew.quodex.data.ApiFailure
import com.tsonglew.quodex.data.FailureKind
import kotlinx.coroutines.withTimeoutOrNull

/** Our deadline is a storage failure; cancellation from the caller still propagates normally. */
internal suspend fun awaitBrowserCleanup(operation: suspend () -> Unit) {
    withTimeoutOrNull(20_000) { operation(); true } ?: throw ApiFailure(FailureKind.STORAGE)
}
