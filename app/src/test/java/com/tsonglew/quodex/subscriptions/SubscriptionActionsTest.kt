package com.tsonglew.quodex.subscriptions

import com.tsonglew.quodex.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionActionsTest {
    private val provider = SubscriptionProvider.DEEPSEEK
    private class MemoryStore : SubscriptionStore {
        var accounts: Map<SubscriptionProvider, SubscriptionAccount> = emptyMap()
        override fun read() = accounts
        override fun write(accounts: Map<SubscriptionProvider, SubscriptionAccount>) { this.accounts = accounts }
    }
    private fun repository(store: MemoryStore) = SubscriptionRepository({ p, credential ->
        if (credential == "bad-replacement") throw ApiFailure(FailureKind.AUTH)
        SubscriptionSnapshot(p, 1000, listOf(SubscriptionMetric(SubscriptionMetricKind.BALANCE, "20", "USD")))
    }, store)

    @Test fun slowBrowserDoesNotDelayApiAndBrowserReadsRemainSerial() = runBlocking {
        withTimeout(5000) {
            val store = MemoryStore()
            val browserProviders = SubscriptionRepository.browserProviders
            store.accounts = (browserProviders + provider).associateWith {
                SubscriptionAccount("", SubscriptionSnapshot(it, 1, emptyList()), browser = it in browserProviders)
            }
            val apiRead = CompletableDeferred<Unit>()
            val browserRead = CompletableDeferred<Unit>()
            val releaseBrowser = CompletableDeferred<Unit>()
            val reads = java.util.Collections.synchronizedList(mutableListOf<SubscriptionProvider>())
            val repo = SubscriptionRepository({ p, _ ->
                apiRead.complete(Unit)
                SubscriptionSnapshot(p, 1000, emptyList())
            }, store, now = { 1000 }, readBrowser = {
                reads += it
                browserRead.complete(Unit)
                releaseBrowser.await()
                throw ApiFailure(FailureKind.NETWORK)
            })
            val actions = SubscriptionActions(repo, this)
            val refresh = launch { actions.refreshAll() }
            browserRead.await()
            apiRead.await()
            assertEquals(listOf(SubscriptionProvider.KIMI), reads.toList())
            releaseBrowser.complete(Unit)
            refresh.join()
            assertEquals(browserProviders.toList(), reads.toList())
            assertEquals(1000L, repo.state.value[provider]?.snapshot?.fetchedAt)
        }
    }

    @Test fun cancellingForegroundBatchCancelsBothApiAndBrowserReads() = runBlocking {
        withTimeout(5000) {
            val store = MemoryStore()
            store.accounts = listOf(provider, SubscriptionProvider.GROK).associateWith {
                SubscriptionAccount("", SubscriptionSnapshot(it, 1, emptyList()), browser = it == SubscriptionProvider.GROK)
            }
            val apiRead = CompletableDeferred<Unit>()
            val browserRead = CompletableDeferred<Unit>()
            val apiClosed = CompletableDeferred<Unit>()
            val browserClosed = CompletableDeferred<Unit>()
            val repo = SubscriptionRepository({ _, _ ->
                try { apiRead.complete(Unit); awaitCancellation() } finally { apiClosed.complete(Unit) }
            }, store, now = { 1000 }, readBrowser = {
                try { browserRead.complete(Unit); awaitCancellation() } finally { browserClosed.complete(Unit) }
            })
            val actions = SubscriptionActions(repo, this)
            val refresh = launch { actions.refreshAll() }
            apiRead.await(); browserRead.await()
            refresh.cancelAndJoin()
            assertTrue(apiClosed.isCompleted)
            assertTrue(browserClosed.isCompleted)
            assertFalse(repo.state.value.getValue(provider).busy)
            assertFalse(repo.state.value.getValue(SubscriptionProvider.GROK).busy)
        }
    }

    @Test fun failedReplacementWaitsForPendingRemovalInsteadOfCancellingIt() = runTest {
        val store = MemoryStore()
        val repo = repository(store)
        repo.connect(provider, "original")
        val actions = SubscriptionActions(repo, this)
        // Neither queued job has been dispatched yet: this reproduces the UI race.
        val removal = actions.disconnect(provider)
        val replacement = actions.connect(provider, "bad-replacement", this)
        assertTrue(removal.isActive)
        assertTrue(store.accounts.containsKey(provider))
        replacement.join()
        assertFalse(removal.isCancelled)
        assertFalse(repo.state.value.getValue(provider).connected)
        assertEquals(FailureKind.AUTH, repo.state.value.getValue(provider).failure)
        assertTrue(store.accounts.isEmpty())
    }

    @Test fun cancellingNewRequestAndItsViewModelScopeStillCompletesRemoval() = runTest {
        val store = MemoryStore()
        val repo = repository(store)
        repo.connect(provider, "original")
        repo.connect(SubscriptionProvider.CURSOR, "cursor-key")
        val actions = SubscriptionActions(repo, this)
        val viewModelScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val removal = actions.disconnect(provider)
        actions.connect(provider, "new-key", viewModelScope)
        actions.cancel(provider)
        viewModelScope.cancel()
        removal.join()
        assertFalse(removal.isCancelled)
        assertFalse(repo.state.value.getValue(provider).connected)
        assertEquals(setOf(SubscriptionProvider.CURSOR), store.accounts.keys)
    }
}
