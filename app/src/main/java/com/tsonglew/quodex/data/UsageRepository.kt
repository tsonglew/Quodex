package com.tsonglew.quodex.data

import com.tsonglew.quodex.diagnostics.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

data class AppState(
    val initialized: Boolean = false,
    val connected: Boolean = false,
    val snapshot: UsageSnapshot? = null,
    val syncing: Boolean = false,
    val failure: FailureKind? = null,
    val demo: Boolean = false,
    val tokenLoading: Boolean = false,
    val tokenFailure: FailureKind? = null,
)

enum class SyncResult { SUCCESS, RETRY, AUTH_REQUIRED, DEFERRED, NO_ACCOUNT }

class UsageRepository(
    private val api: CodexApi,
    private val vault: CredentialStore,
    private val dao: SnapshotDao,
    private val settings: SyncSettings,
    private val now: () -> Long = { Instant.now().epochSecond },
    private val diagnostics: Diagnostics? = null,
) {
    private val lock = Mutex()
    private val tokenLock = Mutex()
    private val generation = AtomicLong(0)
    // Accessed under lock; generation prevents a deadline crossing account changes.
    private var retryGuard: Pair<Long, Long>? = null
    // Token statistics have independent throttling and never change quota retry state.
    private data class TokenAttempt(val accountId: String, val attemptedAt: Long, val retryAt: Long = 0)
    private data class TokenRequest(val session: Session, val previousAttempt: TokenAttempt?, val previousFailure: FailureKind?)
    private var tokenAttempt: TokenAttempt? = null
    private val mutableState = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = mutableState

    suspend fun initialize() = withContext(Dispatchers.IO) {
        // Rendering the existing snapshot must not wait for an in-flight network request.
        if (mutableState.value.initialized) return@withContext
        lock.withLock {
            if (mutableState.value.initialized) return@withLock
            var session: Session? = null
            try {
                val prefs = settings.read()
                if (prefs.demo) {
                    mutableState.value = AppState(initialized = true, snapshot = demoSnapshot(now()), demo = true)
                    return@withLock
                }
                session = vault.read()
                val snapshot = dao.read()?.takeIf { it.accountId == session?.accountId }?.let {
                    runCatching { AppJson.decodeFromString<UsageSnapshot>(it.json) }.getOrNull()
                }?.takeIf { it.accountId == session?.accountId }
                mutableState.value = AppState(true, session != null, snapshot, failure = prefs.lastFailure)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                mutableState.value = AppState(initialized = true, connected = session != null, failure = FailureKind.STORAGE)
            }
        }
    }

    suspend fun connect(session: Session) = withContext(Dispatchers.IO) {
        val ticket = nextGeneration()
        lock.withLock {
            suspend fun checkConnection() {
                currentCoroutineContext().ensureActive()
                if (ticket != generation.get()) throw CancellationException("Connection superseded")
            }
            checkConnection()
            val fetched = api.usage(session)
            checkConnection()
            // An unreadable encrypted session is precisely one reason to reconnect.
            val previousSession = try { vault.read() } catch (failure: ApiFailure) {
                if (failure.kind != FailureKind.STORAGE) throw failure
                null
            }
            val previousSnapshot = dao.read()
            val previousSettings = settings.read()
            val previousTokens = previousSnapshot?.takeIf {
                previousSession?.accountId == session.accountId && it.accountId == session.accountId
            }?.let { runCatching { AppJson.decodeFromString<UsageSnapshot>(it.json) }.getOrNull() }
                ?.takeIf { it.accountId == session.accountId }?.tokenUsage
            val snapshot = fetched.copy(tokenUsage = previousTokens)
            checkConnection()
            var snapshotTouched = false
            var settingsTouched = false
            var credentialsTouched = false
            try {
                snapshotTouched = true
                dao.write(SnapshotRecord(accountId = session.accountId, json = AppJson.encodeToString(snapshot)))
                checkConnection()
                settingsTouched = true
                settings.demo(false)
                settings.syncResult(null, lastAttemptAt = now())
                checkConnection()
                // Commit credentials last so a cancelled cache/preferences write cannot
                // silently reconnect this account the next time the process starts.
                credentialsTouched = true
                vault.write(session)
                checkConnection()
            } catch (failure: Exception) {
                withContext(NonCancellable) {
                    var rollbackFailed = false
                    suspend fun restore(block: suspend () -> Unit): Boolean = try {
                        block(); true
                    } catch (error: Exception) {
                        rollbackFailed = true
                        if (error !== failure) failure.addSuppressed(error)
                        false
                    }
                    if (credentialsTouched && !restore {
                            if (previousSession == null) vault.clear() else vault.write(previousSession)
                        }) restore { vault.clear() }
                    if (snapshotTouched) restore {
                        if (previousSnapshot == null) dao.clear() else dao.write(previousSnapshot)
                    }
                    if (settingsTouched) {
                        restore { settings.demo(previousSettings.demo) }
                        restore { settings.syncResult(previousSettings.lastFailure, previousSettings.retryAt, previousSettings.lastAttemptAt) }
                    }
                    if (rollbackFailed) {
                        // Do not advertise a valid connection after an incomplete rollback.
                        mutableState.value = AppState(initialized = true, failure = FailureKind.STORAGE)
                    }
                }
                throw failure
            }
            diagnostics?.record(DiagnosticEvent.SNAPSHOT_SAVED)
            if (previousSession?.accountId != session.accountId) tokenAttempt = null
            mutableState.value = AppState(true, true, snapshot)
        }
    }

    suspend fun refresh(force: Boolean = false): SyncResult = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        diagnostics?.record(DiagnosticEvent.REFRESH_START, if (force) 1 else 0)
        initialize()
        val ticket = generation.get()
        val outcome = lock.withLock {
            if (ticket != generation.get()) return@withLock SyncResult.NO_ACCOUNT
            if (mutableState.value.demo) { diagnostics?.record(DiagnosticEvent.SKIP_DEMO); return@withLock SyncResult.SUCCESS }
            val prefs = try { settings.read() } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                mutableState.update { it.copy(failure = FailureKind.STORAGE) }
                return@withLock SyncResult.RETRY
            }
            val session = try { vault.read() } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: ApiFailure) {
                mutableState.update { it.copy(failure = FailureKind.STORAGE) }; return@withLock SyncResult.AUTH_REQUIRED
            } catch (_: Exception) {
                mutableState.update { it.copy(failure = FailureKind.STORAGE) }
                return@withLock SyncResult.RETRY
            } ?: run { diagnostics?.record(DiagnosticEvent.SKIP_ACCOUNT); return@withLock SyncResult.NO_ACCOUNT }
            if (prefs.lastFailure == FailureKind.AUTH && !force) { diagnostics?.record(DiagnosticEvent.SKIP_AUTH); return@withLock SyncResult.AUTH_REQUIRED }
            val retryAt = maxOf(prefs.retryAt, retryGuard?.takeIf { it.first == ticket }?.second ?: 0)
            if (now() < retryAt) { diagnostics?.record(DiagnosticEvent.SKIP_BACKOFF, retryAt - now()); return@withLock SyncResult.DEFERRED }
            if (now() - prefs.lastAttemptAt in 0L until 10L) {
                diagnostics?.record(DiagnosticEvent.SKIP_RECENT)
                // A concurrent caller can use the just-completed snapshot instead of retrying a worker later.
                return@withLock if (prefs.lastFailure == null && mutableState.value.snapshot != null) SyncResult.SUCCESS else SyncResult.DEFERRED
            }
            if (!force && prefs.lastFailure == null && mutableState.value.snapshot?.let { now() - it.fetchedAt in 0L until prefs.refreshMinutes * 60 } == true)
                { diagnostics?.record(DiagnosticEvent.SKIP_FRESH); return@withLock SyncResult.DEFERRED }
            mutableState.update { it.copy(syncing = true) }
            try {
                settings.syncResult(prefs.lastFailure, prefs.retryAt, now())
                var active = session
                if (active.expiresAt <= now() + 60) {
                    diagnostics?.record(DiagnosticEvent.RENEW)
                    active = api.renew(active)
                    if (ticket != generation.get()) return@withLock SyncResult.NO_ACCOUNT
                    vault.write(active)
                }
                val fetched = try { api.usage(active) } catch (failure: ApiFailure) {
                    if (failure.kind != FailureKind.AUTH) throw failure
                    diagnostics?.record(DiagnosticEvent.RENEW)
                    active = api.renew(active)
                    if (ticket != generation.get()) return@withLock SyncResult.NO_ACCOUNT
                    vault.write(active)
                    api.usage(active)
                }
                if (ticket != generation.get()) return@withLock SyncResult.NO_ACCOUNT
                val snapshot = fetched.copy(tokenUsage = mutableState.value.snapshot
                    ?.takeIf { it.accountId == active.accountId }?.tokenUsage)
                dao.write(SnapshotRecord(accountId = active.accountId, json = AppJson.encodeToString(snapshot)))
                settings.syncResult(null)
                retryGuard = null
                diagnostics?.record(DiagnosticEvent.SNAPSHOT_SAVED)
                mutableState.update { it.copy(initialized = true, connected = true, snapshot = snapshot, syncing = false, failure = null, demo = false) }
                SyncResult.SUCCESS
            } catch (cancel: CancellationException) {
                diagnostics?.record(DiagnosticEvent.REFRESH_CANCELLED)
                // A bounded widget attempt may hand off to a worker. An interrupted request
                // must not look like a successful recent attempt and suppress that continuation.
                if (ticket == generation.get()) withContext(kotlinx.coroutines.NonCancellable) {
                    // Preserve the original cancellation even if rollback storage is unavailable.
                    try { settings.syncResult(prefs.lastFailure, prefs.retryAt, prefs.lastAttemptAt) }
                    catch (_: Exception) { /* The request remains cancelled; no success is published. */ }
                }
                throw cancel
            }
            catch (failure: ApiFailure) {
                if (ticket == generation.get()) {
                    failure.retryAt?.let { deadline -> retryGuard = ticket to deadline }
                    try { settings.syncResult(failure.kind, failure.retryAt ?: 0) }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) {
                        mutableState.update { it.copy(failure = FailureKind.STORAGE) }
                        return@withLock SyncResult.RETRY
                    }
                    mutableState.update { it.copy(failure = failure.kind) }
                }
                if (failure.kind in listOf(FailureKind.AUTH, FailureKind.FORBIDDEN, FailureKind.PROTOCOL)) SyncResult.AUTH_REQUIRED else SyncResult.RETRY
            } catch (_: Exception) {
                if (ticket == generation.get()) mutableState.update { it.copy(failure = FailureKind.STORAGE) }
                SyncResult.RETRY
            } finally { mutableState.update { it.copy(syncing = false) } }
        }
        diagnostics?.record(DiagnosticEvent.REFRESH_END, outcome.ordinal.toLong(),
            (System.nanoTime() - started) / 1_000_000, mutableState.value.failure?.ordinal?.toLong() ?: -1,
            mutableState.value.snapshot?.let { now() - it.fetchedAt } ?: -1)
        outcome
    }

    /** Foreground token activity is optional and must not delay quota/widget requests. */
    suspend fun refreshTokenUsage(force: Boolean = false) = withContext(Dispatchers.IO) {
        initialize()
        val ticket = generation.get()
        tokenLock.withLock tokenRefresh@ {
            val request = lock.withLock prepare@ {
                val state = mutableState.value
                val snapshot = state.snapshot
                if (ticket != generation.get() || !state.connected || state.demo || snapshot == null) return@prepare null
                val session = try { vault.read() } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    mutableState.update { it.copy(tokenFailure = FailureKind.STORAGE) }
                    return@prepare null
                }
                if (session == null || session.accountId != snapshot.accountId) return@prepare null
                val time = now()
                val previous = tokenAttempt?.takeIf { it.accountId == session.accountId }
                // Even explicit retries obey a token service Retry-After deadline.
                if (previous != null && time < previous.retryAt) {
                    mutableState.update { it.copy(tokenFailure = FailureKind.LIMITED) }
                    return@prepare null
                }
                if (!force) {
                    if (state.tokenFailure == null && snapshot.tokenUsage?.let { time - it.fetchedAt in 0L until 3600L } == true) return@prepare null
                    if (previous != null && time - previous.attemptedAt in 0L until 60L) return@prepare null
                }
                tokenAttempt = TokenAttempt(session.accountId, time)
                mutableState.update { it.copy(tokenLoading = true, tokenFailure = null) }
                TokenRequest(session, previous, state.tokenFailure)
            } ?: return@tokenRefresh
            fun isCurrent() = ticket == generation.get() && mutableState.value.connected && !mutableState.value.demo &&
                mutableState.value.snapshot?.accountId == request.session.accountId
            try {
                // Do not independently rotate refresh credentials alongside the quota request.
                val tokens = api.tokenUsage(request.session)
                lock.withLock commit@ {
                    if (!isCurrent()) return@commit
                    // A quota request may have completed while the token request was in flight.
                    val snapshot = mutableState.value.snapshot!!.copy(tokenUsage = tokens)
                    dao.write(SnapshotRecord(accountId = snapshot.accountId, json = AppJson.encodeToString(snapshot)))
                    if (isCurrent()) mutableState.update { it.copy(snapshot = snapshot, tokenFailure = null) }
                }
            } catch (cancel: CancellationException) {
                withContext(NonCancellable) {
                    lock.withLock {
                        if (isCurrent()) {
                            tokenAttempt = request.previousAttempt
                            mutableState.update { it.copy(tokenFailure = request.previousFailure) }
                        }
                    }
                }
                throw cancel
            } catch (failure: ApiFailure) {
                lock.withLock {
                    if (isCurrent()) {
                        if (failure.kind == FailureKind.LIMITED) tokenAttempt = tokenAttempt?.copy(retryAt = failure.retryAt ?: now() + 60)
                        mutableState.update { it.copy(tokenFailure = failure.kind) }
                    }
                }
            } catch (_: Exception) {
                lock.withLock {
                    if (isCurrent()) mutableState.update { it.copy(tokenFailure = FailureKind.STORAGE) }
                }
            } finally {
                withContext(NonCancellable) {
                    lock.withLock { if (isCurrent()) mutableState.update { it.copy(tokenLoading = false) } }
                }
            }
        }
    }

    suspend fun demo(snapshot: UsageSnapshot = demoSnapshot(now())) = withContext(Dispatchers.IO) {
        require(snapshot.accountId == "demo")
        nextGeneration()
        lock.withLock {
            vault.clear(); dao.clear(); settings.demo(true); settings.syncResult(null, lastAttemptAt = 0)
            tokenAttempt = null
            mutableState.value = AppState(true, snapshot = snapshot, demo = true)
        }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        nextGeneration()
        mutableState.value = AppState(initialized = true)
        lock.withLock {
            vault.clear(); dao.clear(); settings.demo(false); settings.syncResult(null, lastAttemptAt = 0)
            tokenAttempt = null
            mutableState.value = AppState(initialized = true)
        }
    }

    private fun nextGeneration(): Long {
        val ticket = generation.incrementAndGet()
        // Cancelling a login may keep the previous account; its invalidated request must not leave a spinner.
        mutableState.update { it.copy(tokenLoading = false) }
        return ticket
    }

    fun cancelPendingConnection() { nextGeneration() }
}
