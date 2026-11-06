package com.tsonglew.quodex.subscriptions

import com.tsonglew.quodex.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

class SubscriptionRepository(
    private val source: SubscriptionSource,
    private val store: SubscriptionStore,
    private val clearBrowserProfile: suspend (SubscriptionProvider) -> Unit = {},
    private val now: () -> Long = { Instant.now().epochSecond },
) {
    private val storageLock = Mutex()
    private val locks = SubscriptionProvider.entries.associateWith { Mutex() }
    private val generations = SubscriptionProvider.entries.associateWith { AtomicLong() }
    private var accounts: Map<SubscriptionProvider, SubscriptionAccount> = emptyMap()
    private val mutableInitialized = MutableStateFlow(false)
    val initialized = mutableInitialized.asStateFlow()
    private var storageFailed = false
    private val mutableStorageUnavailable = MutableStateFlow(false)
    val storageUnavailable = mutableStorageUnavailable.asStateFlow()
    private val mutable = MutableStateFlow(SubscriptionProvider.entries.associateWith { SubscriptionState() })
    val state = mutable.asStateFlow()

    suspend fun initialize() = withContext(Dispatchers.IO) {
        storageLock.withLock {
            if (initialized.value) return@withLock
            try {
                accounts = store.read()
                require(accounts.all { (provider, account) -> account.snapshot.provider == provider })
                mutable.value = SubscriptionProvider.entries.associateWith { provider ->
                    SubscriptionState(accounts.containsKey(provider), accounts[provider]?.snapshot, imported = accounts[provider]?.imported == true, browser = accounts[provider]?.browser == true)
                }
                storageFailed = false
                mutableStorageUnavailable.value = false
                mutableInitialized.value = true
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                storageFailed = true
                mutableStorageUnavailable.value = true
                mutable.update { it.mapValues { (_, value) -> value.copy(failure = FailureKind.STORAGE) } }
            }
        }
    }

    /** Only called after the user confirms recovery. A readable store is never reset. */
    suspend fun resetUnreadableStorage() = withContext(Dispatchers.IO) {
        initialize() // Retry first: a transient Keystore/storage failure may have recovered.
        storageLock.withLock {
            if (initialized.value || !storageFailed) return@withLock
            try {
                browserProviders.forEach { clearBrowserProfile(it) }
                store.reset()
                accounts = emptyMap()
                generations.values.forEach { it.incrementAndGet() }
                storageFailed = false
                mutableStorageUnavailable.value = false
                mutable.value = SubscriptionProvider.entries.associateWith { SubscriptionState() }
                mutableInitialized.value = true
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                mutable.update { it.mapValues { (_, value) -> value.copy(failure = FailureKind.STORAGE) } }
            }
        }
    }

    suspend fun connect(provider: SubscriptionProvider, input: String) {
        val credential = input.trim()
        if (!SubscriptionApi.validCredential(provider, credential)) {
            update(provider) { it.copy(failure = FailureKind.PROTOCOL) }
            return
        }
        val ticket = storageLock.withLock {
            currentCoroutineContext().ensureActive()
            generations.getValue(provider).incrementAndGet()
        }
        update(provider) { it.copy(busy = false) }
        query(provider, ticket, credential, true)
    }

    /** Imported website data never causes an automatic request with an empty or copied credential. */
    suspend fun importGrok(raw: String) = withContext(Dispatchers.IO) {
        val provider = SubscriptionProvider.GROK
        val snapshot = try {
            if (raw.length > 256 * 1024) throw ApiFailure(FailureKind.PROTOCOL)
            SubscriptionParser.parse(provider, raw, now())
        } catch (failure: ApiFailure) {
            update(provider) { it.copy(failure = failure.kind) }
            return@withContext
        }
        initialize()
        if (storageFailed) return@withContext
        storageLock.withLock {
            currentCoroutineContext().ensureActive()
            generations.getValue(provider).incrementAndGet()
            try {
                val next = accounts + (provider to SubscriptionAccount("", snapshot, imported = true))
                store.write(next)
                accounts = next
                update(provider) { SubscriptionState(true, snapshot, imported = true) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { update(provider) { it.copy(busy = false, failure = FailureKind.STORAGE) } }
        }
    }

    /** The browser keeps its login; only parsed quota is persisted in the encrypted App store. */
    suspend fun acceptBrowser(provider: SubscriptionProvider, raw: String) = withContext(Dispatchers.IO) {
        require(provider in browserProviders && raw.length <= 131072)
        val snapshot = SubscriptionParser.parse(provider, raw, now())
        initialize()
        if (storageFailed) return@withContext
        storageLock.withLock {
            currentCoroutineContext().ensureActive()
            // Generation changes share the commit lock with disconnect. A cancelled writer
            // cannot invalidate a removal after it has already claimed its generation.
            generations.getValue(provider).incrementAndGet()
            try {
                val next = accounts + (provider to SubscriptionAccount("", snapshot, browser = true))
                store.write(next)
                accounts = next
                update(provider) { SubscriptionState(true, snapshot, browser = true) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { update(provider) { it.copy(busy = false, failure = FailureKind.STORAGE) } }
        }
    }

    fun browserFailure(provider: SubscriptionProvider, error: Exception) {
        update(provider) { it.copy(busy = false, failure = (error as? ApiFailure)?.kind ?: FailureKind.PROTOCOL,
            retryAt = (error as? ApiFailure)?.retryAt ?: 0) }
    }

    suspend fun refresh(provider: SubscriptionProvider, force: Boolean = false) {
        query(provider, generations.getValue(provider).get(), null, force)
    }

    private suspend fun query(provider: SubscriptionProvider, ticket: Long, replacement: String?, force: Boolean) = withContext(Dispatchers.IO) {
        initialize()
        if (storageFailed) return@withContext
        locks.getValue(provider).withLock {
            fun current() = ticket == generations.getValue(provider).get()
            if (!current()) return@withLock
            val previous = state.value.getValue(provider)
            // Even a manual refresh must respect Retry-After for this account.
            if (replacement == null && now() < previous.retryAt) return@withLock
            if (replacement == null && !force && previous.failure == null &&
                previous.snapshot?.let { now() - it.fetchedAt in 0L until 300L } == true) return@withLock
            val account = storageLock.withLock { accounts[provider] }
            if (replacement == null && (account?.imported == true || account?.browser == true)) return@withLock
            val credential = replacement ?: account?.credential ?: return@withLock
            update(provider) { it.copy(busy = true, failure = null) }
            try {
                val snapshot = source.fetch(provider, credential)
                require(snapshot.provider == provider)
                currentCoroutineContext().ensureActive()
                storageLock.withLock commit@ {
                    if (!current()) return@commit
                    val next = accounts + (provider to SubscriptionAccount(credential, snapshot))
                    store.write(next)
                    accounts = next
                    update(provider) { SubscriptionState(true, snapshot) }
                }
            } catch (cancel: CancellationException) {
                if (current()) update(provider) { previous.copy(busy = false) }
                throw cancel
            } catch (failure: Exception) {
                if (current()) update(provider) { it.copy(busy = false,
                    failure = (failure as? ApiFailure)?.kind ?: FailureKind.STORAGE,
                    retryAt = (failure as? ApiFailure)?.retryAt ?: 0) }
            } finally {
                if (current()) update(provider) { it.copy(busy = false) }
            }
        }
    }

    suspend fun disconnect(provider: SubscriptionProvider) = withContext(Dispatchers.IO) {
        initialize()
        if (storageFailed) return@withContext
        val ticket = storageLock.withLock {
            currentCoroutineContext().ensureActive()
            generations.getValue(provider).incrementAndGet()
        }
        try {
            if (provider in browserProviders) clearBrowserProfile(provider)
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) {
            update(provider) { it.copy(busy = false, failure = FailureKind.STORAGE) }
            return@withContext
        }
        // Do not wait for the HTTP request; invalidate it before removing credentials.
        storageLock.withLock {
            try {
                if (ticket != generations.getValue(provider).get()) return@withLock
                val next = accounts - provider
                store.write(next)
                accounts = next
                update(provider) { SubscriptionState() }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { update(provider) { it.copy(busy = false, failure = FailureKind.STORAGE) } }
        }
    }

    companion object {
        val browserProviders = setOf(SubscriptionProvider.KIMI, SubscriptionProvider.WORKBUDDY, SubscriptionProvider.GROK)
    }

    private fun update(provider: SubscriptionProvider, block: (SubscriptionState) -> SubscriptionState) {
        mutable.update { it + (provider to block(it.getValue(provider))) }
    }
}
