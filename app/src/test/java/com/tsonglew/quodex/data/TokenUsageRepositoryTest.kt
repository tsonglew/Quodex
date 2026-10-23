package com.tsonglew.quodex.data

import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TokenUsageRepositoryTest {
    private val server = MockWebServer()
    private val vault = MemoryVault()
    private val dao = MemoryDao()
    private val settings = MemorySettings()
    private val tokenRequests = AtomicInteger()
    @Volatile private var time = 10_000L
    @Volatile private var tokenResponse: (RecordedRequest) -> MockResponse = { MockResponse().setBody(TOKENS) }
    private lateinit var repository: UsageRepository
    private val originalTokens = TokenUsageSnapshot(1000, lifetimeTokens = 100, dailyBuckets = listOf(DailyTokenUsage("2026-10-20", 100)))

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/tokens" -> { tokenRequests.incrementAndGet(); tokenResponse(request) }
                "/usage" -> MockResponse().setBody("""{"account_id":"${request.getHeader("ChatGPT-Account-Id")}","rate_limit":{"primary_window":{"used_percent":23,"limit_window_seconds":604800}}}""")
                else -> MockResponse().setResponseCode(500)
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        dao.record = record(snapshot())
        repository = newRepository()
    }

    @After fun tearDown() { server.shutdown() }

    // A cancelled localhost route can switch to ::1 even when the test server only listens on IPv4.
    private fun serverUrl(path: String) = server.url(path).newBuilder().host("127.0.0.1").build().toString()

    private fun newRepository() = UsageRepository(CodexApi(
        authBase = serverUrl("/").trimEnd('/'),
        usageUrl = serverUrl("/usage"), tokenUsageUrl = serverUrl("/tokens"), now = { time },
    ), vault, dao, settings, now = { time })

    private fun snapshot(account: String = "account-a", tokens: TokenUsageSnapshot? = originalTokens) =
        UsageSnapshot(account, "plus", listOf(QuotaWindow("codex:primary_window", "每周额度", 12.0, 604800, 20_000)), 9000, tokenUsage = tokens)
    private fun record(snapshot: UsageSnapshot) = SnapshotRecord(accountId = snapshot.accountId, json = AppJson.encodeToString(snapshot))
    private fun cached() = dao.record?.let { AppJson.decodeFromString<UsageSnapshot>(it.json) }

    @Test fun oldSnapshotJsonLoadsWithoutTokenDataOrMigration() = runBlocking {
        dao.record = SnapshotRecord(accountId = "account-a", json = """{"accountId":"account-a","plan":"plus","windows":[],"fetchedAt":9000}""")
        repository.initialize()
        assertTrue(repository.state.value.connected)
        assertEquals(9000L, repository.state.value.snapshot!!.fetchedAt)
        assertNull(repository.state.value.snapshot!!.tokenUsage)
    }

    @Test fun snapshotContentCannotExposeAnotherAccountsTokens() = runBlocking {
        dao.record = SnapshotRecord(accountId = "account-a", json = AppJson.encodeToString(snapshot(account = "account-b")))
        repository.initialize()
        assertTrue(repository.state.value.connected)
        assertNull(repository.state.value.snapshot)
        repository.refreshTokenUsage(force = true)
        assertEquals(0, tokenRequests.get())
    }

    @Test fun tokenSuccessCachesIndependentlyAndSurvivesRestart() = runBlocking {
        settings.value = settings.value.copy(lastFailure = FailureKind.NETWORK, retryAt = 10_500, lastAttemptAt = 9990)
        val originalSettings = settings.value
        repository.refreshTokenUsage()
        val state = repository.state.value
        assertEquals(9000L, state.snapshot!!.fetchedAt)
        assertEquals(12.0, state.snapshot.windows.single().usedPercent!!, 0.0)
        assertEquals(500L, state.snapshot.tokenUsage!!.lifetimeTokens)
        assertEquals(time, state.snapshot.tokenUsage.fetchedAt)
        assertEquals(FailureKind.NETWORK, state.failure)
        assertNull(state.tokenFailure)
        assertFalse(state.tokenLoading)
        assertEquals(originalSettings, settings.value)
        assertEquals(state.snapshot, cached())
        val restarted = newRepository().also { it.initialize() }
        assertEquals(state.snapshot, restarted.state.value.snapshot)
        restarted.refreshTokenUsage()
        assertEquals(1, tokenRequests.get())
    }

    @Test fun quotaRefreshPreservesTokenCacheAndTokenFailure() = runBlocking {
        tokenResponse = { MockResponse().setResponseCode(403) }
        repository.refreshTokenUsage()
        assertEquals(FailureKind.FORBIDDEN, repository.state.value.tokenFailure)
        assertEquals(SyncResult.SUCCESS, repository.refresh(force = true))
        val state = repository.state.value
        assertEquals(23.0, state.snapshot!!.windows.single().usedPercent!!, 0.0)
        assertEquals(time, state.snapshot.fetchedAt)
        assertEquals(originalTokens, state.snapshot.tokenUsage)
        assertEquals(FailureKind.FORBIDDEN, state.tokenFailure)
        assertNull(state.failure)
        assertEquals(state.snapshot, cached())
    }

    @Test fun tokenFailureCannotInvalidateQuotaOrOverwriteItsSettings() = runBlocking {
        repository.initialize()
        val before = repository.state.value.snapshot
        settings.value = settings.value.copy(lastFailure = FailureKind.LIMITED, retryAt = 12_000, lastAttemptAt = 9990)
        val previousSettings = settings.value
        tokenResponse = { MockResponse().setResponseCode(401) }
        repository.refreshTokenUsage(force = true)
        assertEquals(before, repository.state.value.snapshot)
        assertEquals(before, cached())
        assertTrue(repository.state.value.connected)
        assertNull(repository.state.value.failure)
        assertEquals(FailureKind.AUTH, repository.state.value.tokenFailure)
        assertEquals(previousSettings, settings.value)
        assertEquals(0, vault.writes)
        assertEquals(1, server.requestCount)
    }

    @Test fun automaticChecksUseOneHourCadenceAndFailureWaitsOnlyOneMinute() = runBlocking {
        repository.refreshTokenUsage()
        time += 3599
        repository.refreshTokenUsage()
        assertEquals(1, tokenRequests.get())
        time++
        tokenResponse = { MockResponse().setResponseCode(503) }
        repository.refreshTokenUsage()
        assertEquals(2, tokenRequests.get())
        time += 59
        repository.refreshTokenUsage()
        assertEquals(2, tokenRequests.get())
        time++
        tokenResponse = { MockResponse().setBody(TOKENS) }
        repository.refreshTokenUsage()
        assertEquals(3, tokenRequests.get())
        assertNull(repository.state.value.tokenFailure)
    }

    @Test fun tokenRateLimitAppliesToForcedChecksButDoesNotThrottleQuota() = runBlocking {
        tokenResponse = { MockResponse().setResponseCode(429).setHeader("Retry-After", "120") }
        repository.refreshTokenUsage(force = true)
        assertEquals(FailureKind.LIMITED, repository.state.value.tokenFailure)
        repository.refreshTokenUsage(force = true)
        assertEquals(1, tokenRequests.get())
        assertEquals(SyncResult.SUCCESS, repository.refresh(force = true))
        assertNull(repository.state.value.failure)
        time += 119
        repository.refreshTokenUsage(force = true)
        assertEquals(1, tokenRequests.get())
        time++
        tokenResponse = { MockResponse().setBody(TOKENS) }
        repository.refreshTokenUsage(force = true)
        assertEquals(2, tokenRequests.get())
    }

    @Test fun slowTokenResponseDoesNotBlockQuotaAndMergesIntoLatestSnapshot() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        tokenResponse = { entered.countDown(); release.await(5, TimeUnit.SECONDS); MockResponse().setBody(TOKENS) }
        val pending = async { repository.refreshTokenUsage() }
        try {
            await(entered)
            assertTrue(repository.state.value.tokenLoading)
            withTimeout(3000) { assertEquals(SyncResult.SUCCESS, repository.refresh(force = true)) }
            assertTrue(repository.state.value.tokenLoading)
            assertEquals(23.0, repository.state.value.snapshot!!.windows.single().usedPercent!!, 0.0)
            release.countDown()
            withTimeout(3000) { pending.await() }
            assertEquals(23.0, cached()!!.windows.single().usedPercent!!, 0.0)
            assertEquals(time, cached()!!.fetchedAt)
            assertEquals(500L, cached()!!.tokenUsage!!.lifetimeTokens)
        } finally { release.countDown(); pending.cancelAndJoin() }
    }

    @Test fun reconnectingTheSameAccountDoesNotBypassTokenRetryAfter() = runBlocking {
        tokenResponse = { MockResponse().setResponseCode(429).setHeader("Retry-After", "120") }
        repository.refreshTokenUsage(force = true)
        repository.cancelPendingConnection()
        repository.connect(Session("new-access", "new-refresh", "account-a", 90_000))
        repository.refreshTokenUsage(force = true)
        assertEquals(1, tokenRequests.get())
        assertEquals(FailureKind.LIMITED, repository.state.value.tokenFailure)
        assertNull(repository.state.value.failure)
    }

    @Test fun concurrentAutomaticChecksShareTheNewTokenCache() = runBlocking {
        tokenResponse = { MockResponse().setBody(TOKENS).setBodyDelay(100, TimeUnit.MILLISECONDS) }
        coroutineScope { listOf(async { repository.refreshTokenUsage() }, async { repository.refreshTokenUsage() }).awaitAll() }
        assertEquals(1, tokenRequests.get())
        assertFalse(repository.state.value.tokenLoading)
    }

    @Test fun cancelledTokenRequestCanImmediatelyRetryWithoutLosingQuotaOrCache() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelledHttp = CountDownLatch(1)
        val httpCalls = AtomicInteger()
        val client = okhttp3.OkHttpClient.Builder().retryOnConnectionFailure(false)
            .eventListener(object : okhttp3.EventListener() {
                override fun callStart(call: okhttp3.Call) { httpCalls.incrementAndGet() }
                override fun callFailed(call: okhttp3.Call, ioe: IOException) {
                    if (call.isCanceled()) cancelledHttp.countDown()
                }
            }).build()
        repository = UsageRepository(CodexApi(
            client = client, usageUrl = serverUrl("/usage"),
            tokenUsageUrl = serverUrl("/tokens"), now = { time },
        ), vault, dao, settings, now = { time })
        tokenResponse = { entered.countDown(); release.await(5, TimeUnit.SECONDS); MockResponse().setBody(TOKENS) }
        val pending = async { repository.refreshTokenUsage() }
        try {
            await(entered)
            pending.cancelAndJoin()
            // Coroutine cancellation resumes before OkHttp has released its cancelled connection.
            // Keep the repository clock unchanged, but wait for that transport cleanup before retrying.
            await(cancelledHttp)
            assertFalse(repository.state.value.tokenLoading)
            assertNull(repository.state.value.tokenFailure)
            assertEquals(originalTokens, repository.state.value.snapshot!!.tokenUsage)
            tokenResponse = { MockResponse().setBody(TOKENS) }
            release.countDown()
            repository.refreshTokenUsage()
            assertEquals(2, httpCalls.get())
            assertNull(repository.state.value.tokenFailure)
            assertEquals(2, tokenRequests.get())
            assertEquals(500L, repository.state.value.snapshot!!.tokenUsage!!.lifetimeTokens)
        } finally { release.countDown(); pending.cancelAndJoin() }
    }

    @Test fun logoutCompletesWhileTokenRequestIsInFlightAndRejectsItsResponse() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        tokenResponse = { entered.countDown(); release.await(5, TimeUnit.SECONDS); MockResponse().setBody(TOKENS) }
        val pending = async { repository.refreshTokenUsage() }
        try {
            await(entered)
            withTimeout(3000) { repository.logout() }
            assertNull(dao.record)
            release.countDown()
            pending.await()
            assertNull(dao.record)
            assertNull(repository.state.value.snapshot)
            assertFalse(repository.state.value.connected)
            assertFalse(repository.state.value.tokenLoading)
            assertNull(repository.state.value.tokenFailure)
        } finally { release.countDown(); pending.cancelAndJoin() }
    }

    @Test fun changingAccountsRejectsLateTokenResponseAndResetsRateLimit() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        tokenResponse = { entered.countDown(); release.await(5, TimeUnit.SECONDS); MockResponse().setResponseCode(429).setHeader("Retry-After", "120") }
        val pending = async { repository.refreshTokenUsage() }
        try {
            await(entered)
            withTimeout(3000) { repository.connect(Session("new-access", "new-refresh", "account-b", 90_000)) }
            assertNull(repository.state.value.snapshot!!.tokenUsage)
            assertFalse(repository.state.value.tokenLoading)
            release.countDown()
            pending.await()
            assertNull(repository.state.value.tokenFailure)
            assertEquals("account-b", cached()!!.accountId)
            assertNull(cached()!!.tokenUsage)
            tokenResponse = { request ->
                assertEquals("account-b", request.getHeader("ChatGPT-Account-Id"))
                MockResponse().setBody(TOKENS)
            }
            repository.refreshTokenUsage()
            assertEquals(500L, cached()!!.tokenUsage!!.lifetimeTokens)
        } finally { release.countDown(); pending.cancelAndJoin() }
    }

    @Test fun failedAccountChangeRestoresOriginalTokenCache() = runBlocking {
        repository.initialize()
        val before = dao.record
        settings.failNextSync = true
        assertTrue(runCatching { repository.connect(Session("new-access", "new-refresh", "account-b", 90_000)) }.exceptionOrNull() is IOException)
        assertEquals(before, dao.record)
        assertEquals("account-a", vault.session!!.accountId)
        assertEquals(originalTokens, repository.state.value.snapshot!!.tokenUsage)
        val restarted = newRepository().also { it.initialize() }
        assertEquals(originalTokens, restarted.state.value.snapshot!!.tokenUsage)
    }

    @Test fun sameAccountReconnectKeepsTokenCacheAndDemoClearsIt() = runBlocking {
        repository.connect(Session("new-access", "new-refresh", "account-a", 90_000))
        assertEquals(originalTokens, cached()!!.tokenUsage)
        repository.demo()
        repository.refreshTokenUsage(force = true)
        assertNull(dao.record)
        assertNotEquals(originalTokens, repository.state.value.snapshot!!.tokenUsage)
        assertEquals(0, tokenRequests.get())
    }

    @Test fun cancellingLoginClearsInvalidatedTokenSpinnerWithoutClearingAccount() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        tokenResponse = { entered.countDown(); release.await(5, TimeUnit.SECONDS); MockResponse().setBody(TOKENS) }
        val pending = async { repository.refreshTokenUsage() }
        try {
            await(entered)
            repository.cancelPendingConnection()
            assertFalse(repository.state.value.tokenLoading)
            assertTrue(repository.state.value.connected)
            release.countDown()
            pending.await()
            assertEquals(originalTokens, repository.state.value.snapshot!!.tokenUsage)
        } finally { release.countDown(); pending.cancelAndJoin() }
    }

    @Test fun tokenCacheWriteFailurePreservesQuotaAndCanRecover() = runBlocking {
        repository.initialize()
        val before = dao.record
        dao.failWrite = true
        repository.refreshTokenUsage()
        assertEquals(before, dao.record)
        assertEquals(originalTokens, repository.state.value.snapshot!!.tokenUsage)
        assertNull(repository.state.value.failure)
        assertEquals(FailureKind.STORAGE, repository.state.value.tokenFailure)
        dao.failWrite = false
        repository.refreshTokenUsage(force = true)
        assertNull(repository.state.value.tokenFailure)
        assertEquals(500L, cached()!!.tokenUsage!!.lifetimeTokens)
    }

    @Test fun clockRollbackDoesNotFreezeFutureTokenCache() = runBlocking {
        dao.record = record(snapshot(tokens = originalTokens.copy(fetchedAt = time + 1000)))
        repository.refreshTokenUsage()
        assertEquals(1, tokenRequests.get())
        time -= 100
        repository.refreshTokenUsage()
        assertEquals(2, tokenRequests.get())
    }

    private suspend fun await(latch: CountDownLatch) = withContext(Dispatchers.IO) { assertTrue(latch.await(5, TimeUnit.SECONDS)) }

    private class MemoryVault : CredentialStore {
        var session: Session? = Session("access", "refresh", "account-a", 90_000)
        var writes = 0
        override fun read() = session
        override fun write(session: Session) { writes++; this.session = session }
        override fun clear() { session = null }
    }

    private class MemoryDao : SnapshotDao {
        var record: SnapshotRecord? = null
        var failWrite = false
        override suspend fun read() = record
        override suspend fun write(record: SnapshotRecord) {
            if (failWrite) throw IOException("Unavailable storage")
            this.record = record
        }
        override suspend fun clear() { record = null }
    }

    private class MemorySettings : SyncSettings {
        var value = PreferencesState()
        var failNextSync = false
        override suspend fun read() = value
        override suspend fun demo(value: Boolean) { this.value = this.value.copy(demo = value) }
        override suspend fun syncResult(kind: FailureKind?, retryAt: Long, lastAttemptAt: Long?) {
            if (failNextSync) { failNextSync = false; throw IOException("Settings write failed") }
            value = value.copy(lastFailure = kind, retryAt = retryAt, lastAttemptAt = lastAttemptAt ?: value.lastAttemptAt)
        }
    }

    companion object {
        private const val TOKENS = """{"stats":{"lifetime_tokens":500,"peak_daily_tokens":300,"daily_usage_buckets":[{"start_date":"2026-10-20","tokens":200},{"start_date":"2026-10-21","tokens":300}]}}"""
    }
}
