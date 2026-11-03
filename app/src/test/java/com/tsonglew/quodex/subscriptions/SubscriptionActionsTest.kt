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
