package com.tsonglew.quodex.data

import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ApiCreditsRepositoryTest {
    private val server = MockWebServer()
    private val vault = MemoryVault()
    private val dao = MemoryDao()
    private val settings = MemorySettings()
    private val requests = AtomicInteger()
    @Volatile private var time = 10_000L
    @Volatile private var response: () -> MockResponse = { MockResponse().setBody(DETAILS) }
    private lateinit var repository: UsageRepository
    private val original = ApiCreditsSnapshot(9000, false)
    private val tokens = TokenUsageSnapshot(9000, lifetimeTokens = 100)

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/credits/a/benefits/chatgpt_api_credits_promo?destination_type=api_org",
                "/credits/b/benefits/chatgpt_api_credits_promo?destination_type=api_org" -> { requests.incrementAndGet(); response() }
                "/usage" -> MockResponse().setBody("""{"account_id":"${request.getHeader("ChatGPT-Account-Id")}","rate_limit_reset_credits":{"available_count":3},"rate_limit":{"primary_window":{"used_percent":23,"limit_window_seconds":604800}}}""")
                else -> MockResponse().setResponseCode(500)
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        dao.record = SnapshotRecord(accountId = "a", json = AppJson.encodeToString(
            UsageSnapshot("a", "plus", emptyList(), 9000, resetCredits = 2, tokenUsage = tokens, apiCreditDetails = original)))
        repository = newRepository()
    }
    @After fun tearDown() { server.shutdown() }
    private fun url(path: String) = server.url(path).newBuilder().host("127.0.0.1").build().toString()
    private fun newRepository() = UsageRepository(CodexApi(usageUrl = url("/usage"), apiCreditsBaseUrl = url("/credits"),
        requestRetry = com.tsonglew.quodex.network.RequestRetry(maxAttempts = 1), now = { time }), vault, dao, settings, now = { time })
    private fun cached() = dao.record?.let { AppJson.decodeFromString<UsageSnapshot>(it.json) }

    @Test fun detailsAreExplicitAndCacheSurvivesRestartWithoutChangingQuotaOrTokenData() = runBlocking {
        repository.initialize()
        assertEquals(0, requests.get())
        settings.value = settings.value.copy(lastFailure = FailureKind.NETWORK, retryAt = 10_500)
        val before = settings.value
        repository.refreshApiCredits()
        assertEquals(9000L, cached()!!.fetchedAt)
        assertEquals(tokens, cached()!!.tokenUsage)
        assertTrue(cached()!!.apiCreditDetails!!.eligible)
        assertEquals(before, settings.value)
        assertEquals(cached(), repository.state.value.snapshot)
        repository = newRepository()
        repository.initialize()
        // Quota refresh preserves the independently cached API benefit.
        time += 10
        settings.value = PreferencesState()
        repository.refresh(force = true)
        repository.refreshApiCredits()
        assertEquals(1, requests.get())
        assertTrue(repository.state.value.snapshot!!.apiCreditDetails!!.eligible)
    }

    @Test fun failureAndStorageErrorPreserveQuotaAndIndependentCaches() = runBlocking {
        repository.initialize()
        val before = cached()
        response = { MockResponse().setResponseCode(401) }
        repository.refreshApiCredits()
        assertEquals(FailureKind.AUTH, repository.state.value.apiCreditsFailure)
        assertNull(repository.state.value.failure)
        assertTrue(repository.state.value.connected)
        assertEquals(before, cached())
        assertEquals(1, server.requestCount)
        time += 10
        response = { MockResponse().setBody(DETAILS) }
        dao.failWrite = true
        repository.refreshApiCredits(force = true)
        assertEquals(FailureKind.STORAGE, repository.state.value.apiCreditsFailure)
        assertEquals(before, cached())
        assertEquals(before, repository.state.value.snapshot)
        dao.failWrite = false
        time += 10
        repository.refreshApiCredits(force = true)
        assertNull(repository.state.value.apiCreditsFailure)
        assertFalse(repository.state.value.apiCreditsLoading)
    }

    @Test fun retryAfterSurvivesForcedChecksAndSameAccountReconnectWithoutThrottlingQuota() = runBlocking {
        response = { MockResponse().setResponseCode(429).setHeader("Retry-After", "120") }
        repository.refreshApiCredits()
        assertEquals(10_120L, repository.state.value.apiCreditsRetryAt)
        assertEquals(SyncResult.SUCCESS, repository.refresh(force = true))
        assertEquals(original, cached()!!.apiCreditDetails)
        assertEquals(FailureKind.LIMITED, repository.state.value.apiCreditsFailure)
        repository.cancelPendingConnection()
        repository.connect(Session("new", "refresh", "a", 90000))
        time += 119
        repository.refreshApiCredits(force = true)
        assertEquals(1, requests.get())
        assertEquals(10_120L, repository.state.value.apiCreditsRetryAt)
        time++
        response = { MockResponse().setBody(DETAILS) }
        repository.refreshApiCredits(force = true)
        assertEquals(2, requests.get())
        assertEquals(0L, repository.state.value.apiCreditsRetryAt)
    }

    @Test fun concurrentReadsDebounceAndFreshDetailsUseFiveMinuteCache() = runBlocking {
        repository.refresh(force = true)
        coroutineScope { listOf(async { repository.refreshApiCredits() }, async { repository.refreshApiCredits() }).awaitAll() }
        assertEquals(1, requests.get())
        repository.refreshApiCredits(force = true)
        assertEquals(1, requests.get())
        time += 299
        repository.refreshApiCredits()
        assertEquals(1, requests.get())
        time++
        repository.refreshApiCredits()
        assertEquals(2, requests.get())
        time += 10
        repository.refreshApiCredits(force = true)
        assertEquals(3, requests.get())
    }

    @Test fun slowDetailsDoNotBlockQuotaAndMergeIntoTheLatestSnapshot() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        response = { entered.countDown(); release.await(5, TimeUnit.SECONDS); MockResponse().setBody(DETAILS) }
        val pending = async { repository.refreshApiCredits() }
        try {
            await(entered)
            withTimeout(3000) { assertEquals(SyncResult.SUCCESS, repository.refresh(force = true)) }
            release.countDown(); pending.await()
            assertEquals(23.0, cached()!!.windows.single().usedPercent!!, 0.0)
            assertEquals(time, cached()!!.fetchedAt)
            assertEquals(time, cached()!!.apiCreditDetails!!.fetchedAt)
            assertEquals(tokens, cached()!!.tokenUsage)
        } finally { release.countDown(); pending.cancelAndJoin() }
    }

    @Test fun logoutRejectsLateDetailsAndDemoNeverCallsEndpoint() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        response = { entered.countDown(); release.await(5, TimeUnit.SECONDS); MockResponse().setBody(DETAILS) }
        val pending = async { repository.refreshApiCredits() }
        try {
            await(entered)
            withTimeout(3000) { repository.logout() }
            release.countDown(); pending.await()
            assertNull(cached()); assertNull(repository.state.value.snapshot)
            assertFalse(repository.state.value.apiCreditsLoading)
            repository.demo()
            repository.refreshApiCredits(force = true)
            assertEquals(1, requests.get())
        } finally { release.countDown(); pending.cancelAndJoin() }
    }

    @Test fun changingAccountsRejectsLateResponseAndClearsPreviousCards() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        response = { entered.countDown(); release.await(5, TimeUnit.SECONDS); MockResponse().setBody(DETAILS) }
        val pending = async { repository.refreshApiCredits() }
        try {
            await(entered)
            withTimeout(3000) { repository.connect(Session("new", "refresh", "b", 90000)) }
            release.countDown(); pending.await()
            assertEquals("b", cached()!!.accountId)
            assertNull(cached()!!.apiCreditDetails)
            repository.refreshApiCredits()
            assertEquals(2, requests.get())
            assertTrue(cached()!!.apiCreditDetails!!.eligible)
        } finally { release.countDown(); pending.cancelAndJoin() }
    }

    @Test fun cancellingDetailsRestoresStateWithoutSavingALateResponse() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        response = { entered.countDown(); release.await(5, TimeUnit.SECONDS); MockResponse().setBody(DETAILS) }
        val pending = async { repository.refreshApiCredits() }
        try {
            await(entered)
            assertTrue(repository.state.value.apiCreditsLoading)
            pending.cancelAndJoin()
            assertFalse(repository.state.value.apiCreditsLoading)
            assertNull(repository.state.value.apiCreditsFailure)
            assertEquals(original, cached()!!.apiCreditDetails)
            release.countDown()
            assertEquals(original, repository.state.value.snapshot!!.apiCreditDetails)
        } finally { release.countDown(); pending.cancelAndJoin() }
    }

    private suspend fun await(latch: CountDownLatch) = withContext(Dispatchers.IO) { assertTrue(latch.await(5, TimeUnit.SECONDS)) }
    private class MemoryVault : CredentialStore {
        var session: Session? = Session("access", "refresh", "a", 90000)
        override fun read() = session
        override fun write(session: Session) { this.session = session }
        override fun clear() { session = null }
    }
    private class MemoryDao : SnapshotDao {
        var record: SnapshotRecord? = null
        var failWrite = false
        override suspend fun read() = record
        override suspend fun write(record: SnapshotRecord) { if (failWrite) throw IOException(); this.record = record }
        override suspend fun clear() { record = null }
    }
    private class MemorySettings : SyncSettings {
        var value = PreferencesState()
        override suspend fun read() = value
        override suspend fun demo(value: Boolean) { this.value = this.value.copy(demo = value) }
        override suspend fun syncResult(kind: FailureKind?, retryAt: Long, lastAttemptAt: Long?) {
            value = value.copy(lastFailure = kind, retryAt = retryAt, lastAttemptAt = lastAttemptAt ?: value.lastAttemptAt)
        }
    }
    companion object { private const val DETAILS = """{"eligible":true,"offer":{"period_credit_amount_usd":"100","claimable_credit_amount_usd":"75","currency":"USD","cadence":"monthly","expires_at":"2026-12-01T00:00:00Z"}}""" }
}
