package com.tsonglew.quodex.subscriptions

import com.tsonglew.quodex.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SubscriptionRepositoryTest {
    private val provider = SubscriptionProvider.DEEPSEEK
    private fun snapshot(p: SubscriptionProvider = provider, value: String = "20") =
        SubscriptionSnapshot(p, 1000, listOf(SubscriptionMetric(SubscriptionMetricKind.BALANCE, value, "USD")))
    private class MemoryStore : SubscriptionStore {
        var accounts: Map<SubscriptionProvider, SubscriptionAccount> = emptyMap()
        var failWrite = false
        var failRead = false
        var resets = 0
        override fun read(): Map<SubscriptionProvider, SubscriptionAccount> {
            if (failRead) throw ApiFailure(FailureKind.STORAGE)
            return accounts
        }
        override fun write(accounts: Map<SubscriptionProvider, SubscriptionAccount>) {
            if (failWrite) error("disk")
            this.accounts = accounts
        }
        override fun reset() { write(emptyMap()); failRead = false; resets++ }
    }

    @Test fun initializationWaitsForSavedAccountsBeforeReportingReady() = runBlocking {
        val saved = MemoryStore().apply {
            accounts = mapOf(provider to SubscriptionAccount("saved-key", snapshot()))
        }
        val started = CompletableDeferred<Unit>()
        val finish = java.util.concurrent.CountDownLatch(1)
        val store = object : SubscriptionStore by saved {
            override fun read(): Map<SubscriptionProvider, SubscriptionAccount> {
                started.complete(Unit)
                check(finish.await(5, java.util.concurrent.TimeUnit.SECONDS))
                return saved.read()
            }
        }
        val repo = SubscriptionRepository({ _, _ -> error("must use cache") }, store)
        val loading = launch { repo.initialize() }
        try {
            withTimeout(5_000) { started.await() }
            assertFalse(repo.initialized.value)
            assertFalse(repo.storageUnavailable.value)
        } finally {
            finish.countDown()
        }
        loading.join()
        assertTrue(repo.initialized.value)
        assertTrue(repo.state.value.getValue(provider).connected)
        assertEquals(saved.accounts.getValue(provider).snapshot, repo.state.value.getValue(provider).snapshot)
    }

    @Test fun restartRestoresEachProviderWithoutExposingCredentialsInUi() = runBlocking {
        val store = MemoryStore()
        val repo = SubscriptionRepository({ p, _ -> snapshot(p) }, store)
        SubscriptionProvider.entries.forEach { repo.connect(it, "secret-${it.name}") }
        val restored = SubscriptionRepository({ _, _ -> error("must use cache") }, store)
        restored.initialize()
        assertTrue(restored.state.value.values.all { it.connected && it.snapshot != null })
        assertFalse(restored.state.value.toString().contains("secret-"))
        assertFalse(store.accounts.toString().contains("secret-"))
    }

    @Test fun failedReconnectKeepsTheOriginalCredentialAndSnapshotTogether() = runBlocking {
        val store = MemoryStore()
        val repo = SubscriptionRepository({ p, key ->
            if (key == "bad") throw ApiFailure(FailureKind.AUTH)
            snapshot(p)
        }, store)
        repo.connect(provider, "original")
        repo.connect(provider, "bad")
        assertEquals("original", store.accounts[provider]?.credential)
        assertEquals("20", repo.state.value[provider]?.snapshot?.metrics?.first()?.value)
        assertEquals(FailureKind.AUTH, repo.state.value[provider]?.failure)
    }

    @Test fun failedDiskCommitNeverPublishesNewConnection() = runBlocking {
        val store = MemoryStore().apply { failWrite = true }
        val repo = SubscriptionRepository({ p, _ -> snapshot(p) }, store)
        repo.connect(provider, "key")
        assertFalse(repo.state.value.getValue(provider).connected)
        assertNull(repo.state.value[provider]?.snapshot)
        assertEquals(FailureKind.STORAGE, repo.state.value[provider]?.failure)
        assertTrue(store.accounts.isEmpty())
    }

    @Test fun disconnectInvalidatesInflightRequestWithoutClearingOtherProviders() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val store = MemoryStore()
        val repo = SubscriptionRepository({ p, _ ->
            if (p == provider) { started.complete(Unit); finish.await() }
            snapshot(p)
        }, store)
        repo.connect(SubscriptionProvider.CURSOR, "cursor-key")
        val request = launch { repo.connect(provider, "deepseek-key") }
        started.await()
        repo.disconnect(provider)
        finish.complete(Unit)
        request.join()
        assertFalse(repo.state.value.getValue(provider).connected)
        assertFalse(repo.state.value.getValue(provider).busy)
        assertEquals(setOf(SubscriptionProvider.CURSOR), store.accounts.keys)
    }

    @Test fun cancelledRefreshKeepsCacheAndClearsSpinner() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var refresh = false
        val repo = SubscriptionRepository({ p, _ ->
            if (refresh) { started.complete(Unit); awaitCancellation() }
            snapshot(p)
        }, MemoryStore())
        repo.connect(provider, "key")
        refresh = true
        val request = launch { repo.refresh(provider, true) }
        started.await()
        request.cancelAndJoin()
        assertFalse(repo.state.value.getValue(provider).busy)
        assertNotNull(repo.state.value[provider]?.snapshot)
        assertNull(repo.state.value[provider]?.failure)
    }

    @Test fun retryDeadlineBlocksEvenManualRefreshButNotAnotherProvider() = runBlocking {
        var calls = 0
        var limited = false
        val repo = SubscriptionRepository({ p, _ ->
            calls++
            if (limited && p == provider) throw ApiFailure(FailureKind.LIMITED, 1120, true)
            snapshot(p)
        }, MemoryStore(), now = { 1000 })
        repo.connect(provider, "key")
        limited = true
        repo.refresh(provider, true)
        repo.refresh(provider, true)
        assertEquals(2, calls)
        assertNotNull(repo.state.value[provider]?.snapshot)
        repo.connect(SubscriptionProvider.CURSOR, "cursor-key")
        assertEquals(3, calls)
    }

    @Test fun refreshOnPageOpenUsesRecentCache() = runBlocking {
        var calls = 0
        val repo = SubscriptionRepository({ p, _ -> calls++; snapshot(p) }, MemoryStore(), now = { 1001 })
        repo.connect(provider, "key")
        repo.refresh(provider)
        assertEquals(1, calls)
    }

    @Test fun explicitRecoveryResetsUnreadableStoreAndAllowsNewConnection() = runBlocking {
        val store = MemoryStore().apply { failRead = true }
        var calls = 0
        val repo = SubscriptionRepository({ p, _ -> calls++; snapshot(p) }, store)
        repo.connect(provider, "new-key")
        assertTrue(repo.storageUnavailable.value)
        assertFalse(repo.initialized.value)
        assertEquals(0, calls)
        repo.resetUnreadableStorage()
        assertFalse(repo.storageUnavailable.value)
        assertTrue(repo.initialized.value)
        assertEquals(1, store.resets)
        repo.connect(provider, "new-key")
        assertTrue(repo.state.value.getValue(provider).connected)
        assertEquals(1, calls)
    }

    @Test fun recoveryRetriesReadAndPreservesConnectionsAfterTransientFailure() = runBlocking {
        val account = SubscriptionAccount("existing-key", snapshot())
        val store = MemoryStore().apply { accounts = mapOf(provider to account); failRead = true }
        val repo = SubscriptionRepository({ _, _ -> error("no network during recovery") }, store)
        repo.initialize()
        assertTrue(repo.storageUnavailable.value)
        store.failRead = false
        repo.resetUnreadableStorage()
        assertEquals(0, store.resets)
        assertEquals(account, store.accounts[provider])
        assertTrue(repo.state.value.getValue(provider).connected)
        assertFalse(repo.storageUnavailable.value)
    }

    @Test fun failedResetKeepsStorageErrorUntilRecoverySucceeds() = runBlocking {
        val store = MemoryStore().apply { failRead = true; failWrite = true }
        val repo = SubscriptionRepository({ p, _ -> snapshot(p) }, store)
        repo.initialize()
        repo.resetUnreadableStorage()
        assertTrue(repo.storageUnavailable.value)
        assertEquals(FailureKind.STORAGE, repo.state.value.getValue(provider).failure)
        store.failWrite = false
        repo.resetUnreadableStorage()
        assertFalse(repo.storageUnavailable.value)
        assertTrue(repo.state.value.values.all { !it.connected && it.failure == null })
    }
}
