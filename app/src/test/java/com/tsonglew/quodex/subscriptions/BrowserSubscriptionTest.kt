package com.tsonglew.quodex.subscriptions

import com.tsonglew.quodex.data.*
import com.tsonglew.quodex.subscriptions.browser.BrowserProvider
import com.tsonglew.quodex.subscriptions.browser.awaitBrowserCleanup
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BrowserSubscriptionTest {
    private val provider = SubscriptionProvider.GROK
    private val raw = """{"availability":"available","weeklyIncludedUsage":{"usedCents":37,"budgetCents":100}}"""
    private class Store : SubscriptionStore {
        var data = emptyMap<SubscriptionProvider, SubscriptionAccount>()
        var fail = false
        override fun read() = data
        override fun write(accounts: Map<SubscriptionProvider, SubscriptionAccount>) {
            check(!fail); data = accounts
        }
    }

    @Test fun cleanupDeadlineReportsStorageFailure() = runTest {
        try {
            awaitBrowserCleanup { awaitCancellation() }
            fail("cleanup must time out")
        } catch (error: ApiFailure) { assertEquals(FailureKind.STORAGE, error.kind) }
    }

    @Test fun callerCancellationIsNotConvertedToStorageFailure() = runTest {
        val entered = CompletableDeferred<Unit>()
        val request = launch {
            awaitBrowserCleanup { entered.complete(Unit); awaitCancellation() }
        }
        entered.await()
        request.cancelAndJoin()
        assertTrue(request.isCancelled)
    }

    @Test fun disconnectWaitsForValidatedCommitBeforeClaimingItsGenerationAndClearingProfile() = runBlocking {
        val store = Store()
        val writing = CompletableDeferred<Unit>()
        val finishWrite = java.util.concurrent.CountDownLatch(1)
        val clearing = CompletableDeferred<Unit>()
        val blockedStore = object : SubscriptionStore by store {
            override fun write(accounts: Map<SubscriptionProvider, SubscriptionAccount>) {
                if (accounts.isNotEmpty()) {
                    writing.complete(Unit)
                    check(finishWrite.await(5, java.util.concurrent.TimeUnit.SECONDS))
                }
                store.write(accounts)
            }
        }
        val repo = SubscriptionRepository({ _, _ -> error("no native HTTP") }, blockedStore,
            clearBrowserProfile = { clearing.complete(Unit) })
        val writer = launch { repo.acceptBrowser(provider, raw) }
        writing.await()
        val removal = launch { repo.disconnect(provider) }
        try {
            writer.cancel() // Cancellation cannot interrupt the synchronous, already validated disk commit.
            assertNull(withTimeoutOrNull(150) { clearing.await() })
        } finally { finishWrite.countDown() }
        writer.join(); removal.join()
        assertTrue(clearing.isCompleted)
        assertTrue(store.data.isEmpty())
        assertFalse(repo.state.value.getValue(provider).connected)
    }

    @Test fun originsCannotBeSelectedByUntrustedNavigation() {
        for (profile in BrowserProvider.entries) {
            assertTrue(profile.canRead(profile.dashboard))
            for (url in listOf(null, "file:///tmp/secret", "http://grok.com", "javascript:alert(1)",
                "https://grok.com.evil.test/", "https://grok.com@evil.test/", "${profile.origin}:444/", "${profile.origin}@evil.test/"))
                assertFalse("$profile accepted $url", profile.canRead(url))
            assertTrue(profile.canNavigate("https://accounts.google.com/"))
            assertFalse(profile.canNavigate("intent://login"))
            assertEquals(profile, BrowserProvider.forProcess("app", "app:${profile.profile}"))
        }
        assertEquals(BrowserProvider.GROK, BrowserProvider.forProvider(SubscriptionProvider.CURSOR))
        assertNull(BrowserProvider.forProvider(SubscriptionProvider.GROK_API))
        assertNull(BrowserProvider.forProcess("app", "app"))
        assertEquals(3, BrowserProvider.entries.map { it.profile }.toSet().size)
    }

    @Test fun browserMigrationStoresQuotaOnlyAndRestartsWithAutomaticBrowserRefresh() = runBlocking {
        val store = Store()
        val repo = SubscriptionRepository({ p, _ -> SubscriptionParser.parse(p, raw, 1) }, store)
        repo.connect(provider, "old-session")
        repo.acceptBrowser(provider, raw)
        assertEquals("", store.data.getValue(provider).credential)
        assertTrue(store.data.getValue(provider).browser)
        assertFalse(store.data.getValue(provider).imported)
        var reads = 0
        val restored = SubscriptionRepository({ _, _ -> error("Browser sessions cannot use native HTTP") }, store,
            now = { 2000 }, readBrowser = { reads++; raw.replace("37", "80") })
        restored.initialize()
        restored.refresh(provider, true)
        assertEquals(1, reads)
        assertTrue(restored.state.value.getValue(provider).browser)
        assertEquals(2000L, restored.state.value[provider]?.snapshot?.fetchedAt)
        assertNotEquals(repo.state.value[provider]?.snapshot, restored.state.value[provider]?.snapshot)
        assertEquals("", store.data.getValue(provider).credential)
    }

    @Test fun automaticBrowserRefreshUsesCacheAndRespectsRetryAfterEvenWhenForced() = runBlocking {
        val store = Store()
        var time = 1000L
        var reads = 0
        var limited = false
        val repo = SubscriptionRepository({ _, _ -> error("no native HTTP") }, store, now = { time }, readBrowser = {
            reads++
            if (limited) throw ApiFailure(FailureKind.LIMITED, 3000)
            raw.replace("37", "80")
        })
        repo.acceptBrowser(provider, raw)
        time = 1299
        repo.refresh(provider)
        assertEquals(0, reads)
        time = 1300
        repo.refresh(provider)
        assertEquals(1, reads)
        assertEquals(1300L, repo.state.value[provider]?.snapshot?.fetchedAt)
        limited = true
        time = 1600
        repo.refresh(provider)
        val previous = repo.state.value.getValue(provider).snapshot
        time = 2999
        repo.refresh(provider, true)
        assertEquals(2, reads)
        assertEquals(previous, repo.state.value[provider]?.snapshot)
        assertEquals(FailureKind.LIMITED, repo.state.value[provider]?.failure)
        limited = false
        time = 3000
        repo.refresh(provider)
        assertEquals(3, reads)
        assertNull(repo.state.value[provider]?.failure)
    }

    @Test fun transientFailureBacksOffAutomaticReadsButAllowsImmediateManualRetry() = runBlocking {
        var time = 1000L
        var reads = 0
        val repo = SubscriptionRepository({ _, _ -> error("no native HTTP") }, Store(), now = { time }, readBrowser = {
            reads++
            if (reads == 1) throw ApiFailure(FailureKind.NETWORK)
            raw
        })
        repo.acceptBrowser(provider, raw)
        time = 1300
        repo.refresh(provider)
        assertEquals(0L, repo.state.value.getValue(provider).retryAt)
        assertEquals(1600L, repo.state.value.getValue(provider).automaticRetryAt)
        time = 1301
        repo.refresh(provider)
        assertEquals(1, reads)
        assertEquals(1000L, repo.state.value[provider]?.snapshot?.fetchedAt)
        repo.refresh(provider, true)
        assertEquals(2, reads)
        assertEquals(1301L, repo.state.value[provider]?.snapshot?.fetchedAt)
        assertEquals(0L, repo.state.value.getValue(provider).automaticRetryAt)
    }

    @Test fun expiredLoginPausesAutomaticReadsUntilWebsiteLoginSucceeds() = runBlocking {
        var time = 1000L
        var reads = 0
        val repo = SubscriptionRepository({ _, _ -> error("no native HTTP") }, Store(), now = { time }, readBrowser = {
            reads++; throw ApiFailure(FailureKind.AUTH)
        })
        repo.acceptBrowser(provider, raw)
        time = 1300
        repo.refresh(provider)
        time = 10000
        repo.refresh(provider)
        assertEquals(1, reads)
        assertEquals(1000L, repo.state.value[provider]?.snapshot?.fetchedAt)
        assertEquals(FailureKind.AUTH, repo.state.value[provider]?.failure)
        repo.acceptBrowser(provider, raw)
        assertNull(repo.state.value[provider]?.failure)
        time += 300
        repo.refresh(provider)
        assertEquals(2, reads)
    }

    @Test fun disconnectedAccountCannotBeRestoredByLateAutomaticBrowserRead() = runBlocking {
        val store = Store()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val repo = SubscriptionRepository({ _, _ -> error("no native HTTP") }, store, readBrowser = {
            started.complete(Unit); finish.await(); raw
        })
        repo.acceptBrowser(provider, raw)
        val refresh = launch { repo.refresh(provider, true) }
        started.await()
        repo.disconnect(provider)
        finish.complete(Unit)
        refresh.join()
        assertTrue(store.data.isEmpty())
        assertFalse(repo.state.value.getValue(provider).connected)
    }

    @Test fun leavingForegroundCancelsReadAndPreservesLastSuccessfulSnapshot() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var closed = false
        val repo = SubscriptionRepository({ _, _ -> error("no native HTTP") }, Store(), readBrowser = {
            try { started.complete(Unit); awaitCancellation() } finally { closed = true }
        })
        repo.acceptBrowser(provider, raw)
        val previous = repo.state.value.getValue(provider)
        val refresh = launch { repo.refresh(provider, true) }
        started.await()
        assertTrue(repo.state.value.getValue(provider).busy)
        refresh.cancelAndJoin()
        assertTrue(closed)
        assertEquals(previous, repo.state.value.getValue(provider))
    }

    @Test fun failedBrowserCommitPreservesPreviousQuotaAndFailedClearPreservesConnection() = runBlocking {
        val store = Store()
        var failClear = true
        val cleared = mutableListOf<SubscriptionProvider>()
        val repo = SubscriptionRepository({ _, _ -> error("no native HTTP") }, store, clearBrowserProfile = {
            cleared += it; check(!failClear)
        })
        repo.acceptBrowser(provider, raw)
        val original = store.data
        store.fail = true
        repo.acceptBrowser(provider, raw.replace("37", "80"))
        assertEquals(original, store.data)
        assertEquals(FailureKind.STORAGE, repo.state.value[provider]?.failure)
        store.fail = false
        repo.disconnect(provider)
        assertEquals(original, store.data)
        failClear = false
        repo.disconnect(provider)
        assertTrue(store.data.isEmpty())
        assertEquals(listOf(provider, provider), cleared)
    }

    @Test fun cancelledBrowserResultCannotReconnectAfterDisconnect() = runBlocking {
        val store = Store()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val repo = SubscriptionRepository({ _, _ -> error("no native HTTP") }, store)
        val actions = SubscriptionActions(repo, this)
        val request = actions.connectBrowser(provider, this) {
            withContext(NonCancellable) { entered.complete(Unit); finish.await() }
            raw
        }
        entered.await()
        actions.disconnect(provider).join()
        finish.complete(Unit)
        request.join()
        assertTrue(store.data.isEmpty())
        assertFalse(repo.state.value.getValue(provider).connected)
    }

    @Test fun newBrowserLoginWaitsForProfileRemoval() = runBlocking {
        val store = Store()
        val clearing = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val repo = SubscriptionRepository({ _, _ -> error("no native HTTP") }, store, clearBrowserProfile = {
            clearing.complete(Unit); finish.await()
        })
        val actions = SubscriptionActions(repo, this)
        val removal = actions.disconnect(provider)
        clearing.await()
        var opened = false
        val request = actions.connectBrowser(provider, this) { opened = true; raw }
        yield()
        assertFalse(opened)
        finish.complete(Unit)
        request.join()
        assertTrue(removal.isCompleted)
        assertTrue(repo.state.value.getValue(provider).browser)
    }
}
