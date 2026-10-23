package com.tsonglew.quodex.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.Base64

class UsageRepositoryTest {
    private val server = MockWebServer()
    private val vault = FakeVault()
    private val dao = FakeDao()
    private val settings = FakeSettings()
    private lateinit var repository: UsageRepository
    @Before fun start() {
        server.start()
        vault.session = Session("access", "refresh", "test-account", 9000)
        repository = newRepository()
    }
    @After fun stop() { server.shutdown() }
    private fun newRepository() = UsageRepository(CodexApi(authBase = server.url("/").toString().trimEnd('/'), usageUrl = server.url("/usage").toString(), now = { 1000 }), vault, dao, settings, now = { 1000 })

    private suspend fun seedPreviousConnection(): AppState {
        dao.record = SnapshotRecord(accountId = "test-account", json = AppJson.encodeToString(demoSnapshot(500).copy(accountId = "test-account")))
        settings.value = settings.value.copy(lastFailure = FailureKind.LIMITED, retryAt = 1200, lastAttemptAt = 950)
        repository.initialize()
        return repository.state.value
    }

    @Test fun cancelledFirstConnectionDoesNotReappearAfterRestart() = runBlocking {
        vault.session = null
        repository.initialize()
        val writing = CompletableDeferred<Unit>()
        dao.afterWrite = { writing.complete(Unit); awaitCancellation() }
        server.enqueue(MockResponse().setBody(body))
        val connection = launch { repository.connect(Session("new-access", "new-refresh", "test-account", 9000)) }
        withTimeout(5000) { writing.await() }
        repository.cancelPendingConnection()
        connection.cancelAndJoin()
        assertNull(vault.session)
        assertNull(dao.record)
        val restarted = newRepository().also { it.initialize() }
        assertFalse(restarted.state.value.connected)
        assertNull(restarted.state.value.snapshot)
    }

    @Test fun cancelledReconnectRestoresPreviousAccountCacheAndBackoff() = runBlocking {
        val previousState = seedPreviousConnection()
        val previousSession = vault.session
        val previousSnapshot = dao.record
        val previousSettings = settings.value
        val writing = CompletableDeferred<Unit>()
        settings.afterSyncResult = { writing.complete(Unit); awaitCancellation() }
        server.enqueue(MockResponse().setBody(body.replace("test-account", "second-account")))
        val connection = launch { repository.connect(Session("new-access", "new-refresh", "second-account", 9000)) }
        withTimeout(5000) { writing.await() }
        repository.cancelPendingConnection()
        connection.cancelAndJoin()
        assertEquals(previousSession, vault.session)
        assertEquals(previousSnapshot, dao.record)
        assertEquals(previousSettings, settings.value)
        assertEquals(previousState, repository.state.value)
        assertEquals(previousState, newRepository().also { it.initialize() }.state.value)
    }

    @Test fun partialSettingsWriteFailureRestoresPreviousConnection() = runBlocking {
        val previousState = seedPreviousConnection()
        val previousSession = vault.session
        val previousSnapshot = dao.record
        val previousSettings = settings.value
        settings.afterSyncResult = { throw java.io.IOException("Failed after preferences changed") }
        server.enqueue(MockResponse().setBody(body.replace("test-account", "second-account")))
        val failure = runCatching { repository.connect(Session("new-access", "new-refresh", "second-account", 9000)) }.exceptionOrNull()
        assertTrue(failure is java.io.IOException)
        assertEquals(previousSession, vault.session)
        assertEquals(previousSnapshot, dao.record)
        assertEquals(previousSettings, settings.value)
        assertEquals(previousState, newRepository().also { it.initialize() }.state.value)
    }

    @Test fun supersededConnectionRestoresCredentialsEvenAfterVaultWrite() = runBlocking {
        val previousState = seedPreviousConnection()
        val previousSession = vault.session
        val previousSettings = settings.value
        vault.afterWrite = { repository.cancelPendingConnection() }
        server.enqueue(MockResponse().setBody(body.replace("test-account", "second-account")))
        val failure = runCatching { repository.connect(Session("new-access", "new-refresh", "second-account", 9000)) }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(previousSession, vault.session)
        assertEquals(previousSettings, settings.value)
        assertEquals(previousState, newRepository().also { it.initialize() }.state.value)
    }

    @Test fun logoutDuringConnectionCommitWinsAfterRollback() = runBlocking {
        seedPreviousConnection()
        val writing = CompletableDeferred<Unit>()
        val finishWrite = CompletableDeferred<Unit>()
        dao.afterWrite = { writing.complete(Unit); finishWrite.await() }
        server.enqueue(MockResponse().setBody(body.replace("test-account", "second-account")))
        val connection = async { runCatching { repository.connect(Session("new-access", "new-refresh", "second-account", 9000)) } }
        withTimeout(5000) { writing.await() }
        val logout = async { repository.logout() }
        withTimeout(5000) { repository.state.first { !it.connected } }
        finishWrite.complete(Unit)
        assertTrue(connection.await().exceptionOrNull() is CancellationException)
        logout.await()
        assertNull(vault.session)
        assertNull(dao.record)
        assertEquals(PreferencesState(), settings.value)
        assertFalse(newRepository().also { it.initialize() }.state.value.connected)
    }

    @Test fun failedCredentialRollbackClearsConnectionAndReportsStorageFailure() = runBlocking {
        seedPreviousConnection()
        var restoreAttempted = false
        vault.afterWrite = {
            vault.afterWrite = {
                restoreAttempted = true
                throw java.io.IOException("Previous credential restore failed")
            }
            throw java.io.IOException("New credential commit failed")
        }
        server.enqueue(MockResponse().setBody(body.replace("test-account", "second-account")))
        val failure = runCatching { repository.connect(Session("new-access", "new-refresh", "second-account", 9000)) }.exceptionOrNull()
        assertTrue(failure is java.io.IOException)
        assertTrue(restoreAttempted)
        assertNull(vault.session)
        assertFalse(repository.state.value.connected)
        assertNull(repository.state.value.snapshot)
        assertEquals(FailureKind.STORAGE, repository.state.value.failure)
        assertFalse(newRepository().also { it.initialize() }.state.value.connected)
    }

    @Test fun reconnectCanReplaceAnUnreadableEncryptedSession() = runBlocking {
        vault.readFailure = ApiFailure(FailureKind.STORAGE)
        repository.initialize()
        server.enqueue(MockResponse().setBody(body))
        val replacement = Session("new-access", "new-refresh", "test-account", 9000)
        repository.connect(replacement)
        vault.readFailure = null
        assertEquals(replacement, vault.session)
        assertTrue(repository.state.value.connected)
        assertNull(repository.state.value.failure)
        assertEquals(repository.state.value, newRepository().also { it.initialize() }.state.value)
    }
    @Test fun unreadableDatabaseShowsStorageFailureKeepsSessionAndCanRecover() = runBlocking {
        val session = vault.session
        dao.readFailure = IllegalStateException("Database directory unavailable")
        repository.initialize()
        assertTrue(repository.state.value.initialized)
        assertTrue(repository.state.value.connected)
        assertEquals(FailureKind.STORAGE, repository.state.value.failure)
        assertSame(session, vault.session)
        assertEquals(0, server.requestCount)
        dao.readFailure = null
        server.enqueue(MockResponse().setBody(body))
        assertEquals(SyncResult.SUCCESS, repository.refresh(true))
        assertNull(repository.state.value.failure)
        assertNotNull(repository.state.value.snapshot)
    }
    @Test fun unreadablePreferencesDoNotCrashStartupOrDeleteCredentials() = runBlocking {
        val session = vault.session
        settings.readFailure = java.io.IOException("Preferences unavailable")
        repository.initialize()
        assertTrue(repository.state.value.initialized)
        assertEquals(FailureKind.STORAGE, repository.state.value.failure)
        assertSame(session, vault.session)
        assertEquals(0, server.requestCount)
    }
    @Test fun cancelledInitializationIsNotReportedAsStorageFailure() = runBlocking {
        dao.readFailure = CancellationException("Cancelled initialization")
        val failure = runCatching { repository.initialize() }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertFalse(repository.state.value.initialized)
        assertNull(repository.state.value.failure)
    }
    @Test fun preferencesReadFailureDuringRefreshKeepsSnapshotAndCanRecover() = runBlocking {
        server.enqueue(MockResponse().setBody(body))
        repository.refresh(true)
        val cached = repository.state.value.snapshot
        val session = vault.session
        settings.readFailure = java.io.IOException("Preferences unavailable")
        assertEquals(SyncResult.RETRY, repository.refresh(true))
        assertEquals(FailureKind.STORAGE, repository.state.value.failure)
        assertEquals(cached, repository.state.value.snapshot)
        assertSame(session, vault.session)
        assertEquals(1, server.requestCount)
        settings.readFailure = null
        settings.value = settings.value.copy(lastAttemptAt = 0)
        server.enqueue(MockResponse().setBody(body))
        assertEquals(SyncResult.SUCCESS, repository.refresh(true))
        assertNull(repository.state.value.failure)
    }
    @Test fun cancelledRefreshPreferenceReadStillPropagatesCancellation() = runBlocking {
        repository.initialize()
        settings.readFailure = CancellationException("Refresh cancelled")
        assertTrue(runCatching { repository.refresh(true) }.exceptionOrNull() is CancellationException)
        assertNull(repository.state.value.failure)
        assertEquals(0, server.requestCount)
    }
    @Test fun vaultReadIoFailureDuringRefreshKeepsCachedAccount() = runBlocking {
        server.enqueue(MockResponse().setBody(body))
        repository.refresh(true)
        val cached = repository.state.value.snapshot
        vault.readFailure = java.io.IOException("Vault unavailable")
        assertEquals(SyncResult.RETRY, repository.refresh(true))
        assertEquals(FailureKind.STORAGE, repository.state.value.failure)
        assertEquals(cached, repository.state.value.snapshot)
        assertTrue(repository.state.value.connected)
        assertEquals(1, server.requestCount)
    }
    @Test fun recordingServerFailureCannotEscapeAsStorageException() = runBlocking {
        server.enqueue(MockResponse().setBody(body))
        repository.refresh(true)
        val cached = repository.state.value.snapshot
        settings.value = settings.value.copy(lastAttemptAt = 0)
        settings.writeFailureKind = FailureKind.SERVER
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(SyncResult.RETRY, repository.refresh(true))
        assertEquals(FailureKind.STORAGE, repository.state.value.failure)
        assertEquals(cached, repository.state.value.snapshot)
        assertFalse(repository.state.value.syncing)
        assertEquals(2, server.requestCount)
    }
    @Test fun retryAfterStillBlocksRequestsWhenPersistingLimitFails() = runBlocking {
        server.enqueue(MockResponse().setBody(body))
        repository.refresh(true)
        val cached = repository.state.value.snapshot
        settings.value = settings.value.copy(lastAttemptAt = 0)
        settings.writeFailureKind = FailureKind.LIMITED
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120"))
        assertEquals(SyncResult.RETRY, repository.refresh(true))
        assertEquals(FailureKind.STORAGE, repository.state.value.failure)
        // Clear only the short attempt guard; the server deadline must still apply.
        settings.value = settings.value.copy(lastAttemptAt = 0)
        server.enqueue(MockResponse().setResponseCode(503)) // Must remain unconsumed.
        assertEquals(SyncResult.DEFERRED, repository.refresh(true))
        assertEquals(2, server.requestCount)
        assertEquals(cached, repository.state.value.snapshot)
    }
    @Test fun inMemoryLimitDoesNotCrossAccountGeneration() = runBlocking {
        server.enqueue(MockResponse().setBody(body))
        repository.refresh(true)
        settings.value = settings.value.copy(lastAttemptAt = 0)
        settings.writeFailureKind = FailureKind.LIMITED
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120"))
        assertEquals(SyncResult.RETRY, repository.refresh(true))
        repository.logout()
        val otherBody = body.replace("test-account", "second-account")
        server.enqueue(MockResponse().setBody(otherBody))
        repository.connect(Session("second-access", "second-refresh", "second-account", 9000))
        settings.value = settings.value.copy(lastAttemptAt = 0)
        server.enqueue(MockResponse().setBody(otherBody))
        assertEquals(SyncResult.SUCCESS, repository.refresh(true))
        assertEquals("second-account", repository.state.value.snapshot?.accountId)
        assertEquals(4, server.requestCount)
    }
    @Test fun concurrentRefreshesShareOneRequest() = runBlocking {
        server.enqueue(MockResponse().setBody(body).setBodyDelay(100, TimeUnit.MILLISECONDS))
        val outcomes = coroutineScope { listOf(async { repository.refresh(true) }, async { repository.refresh(true) }).awaitAll() }
        assertEquals(listOf(SyncResult.SUCCESS, SyncResult.SUCCESS), outcomes)
        assertEquals(1, server.requestCount)
        assertEquals("88", repository.state.value.snapshot!!.windows.single().percentageLabel(true))
    }
    @Test fun renderingAnInitializedSnapshotDoesNotWaitForSlowRefresh() = runBlocking {
        dao.record = SnapshotRecord(accountId = "test-account", json = AppJson.encodeToString(demoSnapshot(500).copy(accountId = "test-account")))
        repository.initialize()
        server.enqueue(MockResponse().setBody(body).setBodyDelay(2, TimeUnit.SECONDS))
        val request = async { repository.refresh(true) }
        try {
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
            withTimeout(1000) { repository.initialize() }
            assertTrue(repository.state.value.syncing)
            assertEquals(500L, repository.state.value.snapshot!!.fetchedAt)
        } finally { request.cancelAndJoin() }
    }
    @Test fun cancelledWidgetAttemptCanImmediatelyContinueWithoutReturningOldCacheAsSuccess() = runBlocking {
        dao.record = SnapshotRecord(accountId = "test-account", json = AppJson.encodeToString(demoSnapshot(500).copy(accountId = "test-account")))
        val readingBody = java.util.concurrent.CountDownLatch(1)
        val client = okhttp3.OkHttpClient.Builder().retryOnConnectionFailure(false).eventListener(object : okhttp3.EventListener() {
            override fun responseHeadersEnd(call: okhttp3.Call, response: okhttp3.Response) { readingBody.countDown() }
        }).build()
        repository = UsageRepository(CodexApi(client = client, usageUrl = server.url("/usage").toString(), now = { 1000 }), vault, dao, settings, now = { 1000 })
        server.enqueue(MockResponse().setHeader("Connection", "close").setBody(body).setBodyDelay(2, TimeUnit.SECONDS))
        val attempt = async { repository.refresh(true) }
        // Cancel while reading an assigned response, not while the test server is still
        // deciding which connection should receive its first queued response.
        withContext(Dispatchers.IO) { assertTrue(readingBody.await(5, TimeUnit.SECONDS)) }
        attempt.cancelAndJoin()
        assertEquals(0L, settings.value.lastAttemptAt)
        assertFalse(repository.state.value.syncing)
        server.enqueue(MockResponse().setBody(body))
        val continued = repository.refresh(true)
        // OkHttp may reject a just-cancelled pooled connection before a new wire request.
        // That must be a retryable failure, never SUCCESS with the old snapshot.
        if (continued == SyncResult.SUCCESS) {
            assertEquals(2, server.requestCount)
            assertEquals(1000L, repository.state.value.snapshot!!.fetchedAt)
        } else {
            assertEquals(SyncResult.RETRY, continued)
            assertEquals(FailureKind.NETWORK, repository.state.value.failure)
            assertEquals(500L, repository.state.value.snapshot!!.fetchedAt)
        }
        assertEquals(1000L, settings.value.lastAttemptAt)
    }
    @Test fun failedRequestPreservesSnapshotAndSuccessfulTimestamp() = runBlocking {
        dao.record = SnapshotRecord(accountId = "test-account", json = AppJson.encodeToString(demoSnapshot(500).copy(accountId = "test-account")))
        server.enqueue(MockResponse().setResponseCode(503))
        repository.refresh(true)
        assertEquals(500L, repository.state.value.snapshot!!.fetchedAt)
        assertEquals(FailureKind.SERVER, repository.state.value.failure)
        assertEquals(1000L, settings.value.lastAttemptAt)
    }
    @Test fun validWindowlessResponseReplacesOldMeteredCache() = runBlocking {
        dao.record = SnapshotRecord(accountId = "test-account", json = AppJson.encodeToString(demoSnapshot(500).copy(accountId = "test-account")))
        server.enqueue(MockResponse().setBody("""{"plan_type":"pro","rate_limit":{"allowed":true,"limit_reached":false}}"""))
        assertEquals(SyncResult.SUCCESS, repository.refresh(true))
        val snapshot = repository.state.value.snapshot!!
        assertTrue(snapshot.windows.isEmpty())
        assertNull(repository.state.value.failure)
        assertEquals("当前可用", snapshot.quotaNotice()!!.title)
        assertEquals(snapshot, AppJson.decodeFromString<UsageSnapshot>(dao.record!!.json))
    }
    @Test fun logoutRejectsAnInflightResponse() = runBlocking {
        server.enqueue(MockResponse().setBody(body).setBodyDelay(200, TimeUnit.MILLISECONDS))
        coroutineScope {
            val refresh = async { repository.refresh(true) }
            withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }
            repository.logout()
            refresh.await()
        }
        assertNull(repository.state.value.snapshot)
        assertFalse(repository.state.value.connected)
        assertNull(vault.session); assertNull(dao.record)
    }
    @Test fun cachedDataFromAnotherAccountIsNeverDisplayed() = runBlocking {
        dao.record = SnapshotRecord(accountId = "other", json = AppJson.encodeToString(demoSnapshot(500)))
        repository.initialize()
        assertNull(repository.state.value.snapshot)
    }
    @Test fun serverBackoffSurvivesRepositoryInitialization() = runBlocking {
        settings.value = settings.value.copy(retryAt = 1200, lastFailure = FailureKind.LIMITED)
        assertEquals(SyncResult.DEFERRED, repository.refresh(true))
        assertEquals(0, server.requestCount)
    }
    @Test fun clockMovedBackwardsDoesNotKeepFutureSnapshotFresh() = runBlocking {
        settings.value = settings.value.copy(lastAttemptAt = 2000)
        dao.record = SnapshotRecord(accountId = "test-account", json = AppJson.encodeToString(demoSnapshot(2000).copy(accountId = "test-account")))
        server.enqueue(MockResponse().setBody(body))
        assertEquals(SyncResult.SUCCESS, repository.refresh())
        assertEquals(1, server.requestCount)
        assertEquals(1000L, repository.state.value.snapshot!!.fetchedAt)
    }
    @Test fun clockMovedBackwardsStillHonorsServerRetryDeadline() = runBlocking {
        settings.value = settings.value.copy(lastAttemptAt = 2000, retryAt = 2200, lastFailure = FailureKind.LIMITED)
        assertEquals(SyncResult.DEFERRED, repository.refresh(true))
        assertEquals(0, server.requestCount)
    }
    @Test fun concurrentExpiredSessionsRenewOnlyOnceAndSaveRotation() = runBlocking {
        vault.session = vault.session!!.copy(expiresAt = 900)
        server.enqueue(MockResponse().setBody(renewed).setBodyDelay(100, TimeUnit.MILLISECONDS))
        server.enqueue(MockResponse().setBody(body))
        coroutineScope { listOf(async { repository.refresh(true) }, async { repository.refresh(true) }).awaitAll() }
        assertEquals(2, server.requestCount)
        assertEquals("/oauth/token", server.takeRequest().path)
        assertEquals("/usage", server.takeRequest().path)
        assertEquals("rotated-refresh", vault.session!!.refreshToken)
        assertEquals(9000L, vault.session!!.expiresAt)
        assertNotNull(repository.state.value.snapshot)
    }
    @Test fun unauthorizedUsageRenewsAndRetriesExactlyOnce() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody(renewed))
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(SyncResult.AUTH_REQUIRED, repository.refresh(true))
        assertEquals(3, server.requestCount)
        assertEquals(FailureKind.AUTH, repository.state.value.failure)
        assertEquals("rotated-refresh", vault.session!!.refreshToken)
        assertNull(repository.state.value.snapshot)
    }
    private class FakeVault : CredentialStore {
        var session: Session? = null
        var readFailure: Exception? = null
        var afterWrite: (() -> Unit)? = null
        override fun read(): Session? { readFailure?.let { throw it }; return session }
        override fun write(session: Session) {
            this.session = session
            val callback = afterWrite.also { afterWrite = null }
            callback?.invoke()
        }
        override fun clear() { session = null }
    }
    private class FakeDao : SnapshotDao {
        var record: SnapshotRecord? = null
        var readFailure: Exception? = null
        var afterWrite: (suspend () -> Unit)? = null
        override suspend fun read(): SnapshotRecord? { readFailure?.let { throw it }; return record }
        override suspend fun write(record: SnapshotRecord) {
            this.record = record
            val callback = afterWrite.also { afterWrite = null }
            callback?.invoke()
        }
        override suspend fun clear() { record = null }
    }
    private class FakeSettings : SyncSettings {
        var value = PreferencesState()
        var writeFailureKind: FailureKind? = null
        var readFailure: Exception? = null
        var afterSyncResult: (suspend () -> Unit)? = null
        override suspend fun read(): PreferencesState { readFailure?.let { throw it }; return value }
        override suspend fun demo(value: Boolean) { this.value = this.value.copy(demo = value) }
        override suspend fun syncResult(kind: FailureKind?, retryAt: Long, lastAttemptAt: Long?) {
            if (kind != null && kind == writeFailureKind) throw java.io.IOException("Preferences write unavailable")
            value = value.copy(lastFailure = kind, retryAt = retryAt, lastAttemptAt = lastAttemptAt ?: value.lastAttemptAt)
            val callback = afterSyncResult.also { afterSyncResult = null }
            callback?.invoke()
        }
    }
    companion object {
        const val body = """{"account_id":"test-account","rate_limit":{"primary_window":{"used_percent":12,"limit_window_seconds":604800}}}"""
        private val claims = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"https://api.openai.com/auth":{"chatgpt_account_id":"test-account"},"exp":9000}""".toByteArray())
        val renewed = """{"access_token":"header.$claims.signature","refresh_token":"rotated-refresh"}"""
    }
}
