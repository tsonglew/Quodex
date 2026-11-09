package com.tsonglew.quodex.subscriptions

import kotlinx.coroutines.*

/** Application-owned removals must survive a cancelled request or a replaced ViewModel. */
class SubscriptionActions(
    private val repository: SubscriptionRepository,
    private val persistenceScope: CoroutineScope,
) {
    private val requests = mutableMapOf<SubscriptionProvider, Job>()
    private val removals = mutableMapOf<SubscriptionProvider, Job>()
    private var reset: Job? = null

    fun connect(provider: SubscriptionProvider, credential: String, scope: CoroutineScope) =
        query(provider, scope, replace = true) { repository.connect(provider, credential) }

    fun connectBrowser(provider: SubscriptionProvider, scope: CoroutineScope, read: suspend () -> String) =
        query(provider, scope, replace = true) {
            try {
                val raw = read()
                currentCoroutineContext().ensureActive()
                repository.acceptBrowser(provider, raw)
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                repository.browserFailure(provider, error)
            }
        }

    fun importGrok(raw: String, scope: CoroutineScope) =
        query(SubscriptionProvider.GROK, scope, replace = true) { repository.importGrok(raw) }

    fun refresh(provider: SubscriptionProvider, scope: CoroutineScope, force: Boolean = false) =
        query(provider, scope, replace = false) { repository.refresh(provider, force) }

    /** Keep browser memory bounded without making API accounts wait for a slow website. */
    suspend fun refreshAll() = coroutineScope {
        repository.initialize()
        val (browser, api) = SubscriptionProvider.entries.partition { repository.state.value.getValue(it).browser }
        api.forEach { refresh(it, this) }
        browser.forEach { refresh(it, this).join() }
    }

    @Synchronized private fun query(provider: SubscriptionProvider, scope: CoroutineScope,
        replace: Boolean, operation: suspend () -> Unit): Job {
        if (!replace) requests[provider]?.takeIf { it.isActive }?.let { return it }
        requests[provider]?.cancel()
        val pendingRemoval = removals[provider]
        val pendingReset = reset
        val job = scope.launch(start = CoroutineStart.LAZY) {
            pendingRemoval?.join()
            pendingReset?.join()
            operation()
        }
        requests[provider] = job
        job.invokeOnCompletion {
            synchronized(this) { if (requests[provider] === job) requests.remove(provider) }
        }
        job.start()
        return job
    }

    @Synchronized fun cancel(provider: SubscriptionProvider) { requests[provider]?.cancel() }

    @Synchronized fun disconnect(provider: SubscriptionProvider): Job {
        cancel(provider)
        removals[provider]?.takeIf { it.isActive }?.let { return it }
        val pendingReset = reset
        val job = persistenceScope.launch(start = CoroutineStart.LAZY) {
            pendingReset?.join()
            repository.disconnect(provider)
        }
        removals[provider] = job
        job.invokeOnCompletion {
            synchronized(this) { if (removals[provider] === job) removals.remove(provider) }
        }
        job.start()
        return job
    }

    @Synchronized fun resetUnreadableStorage(): Job {
        requests.values.toList().forEach { it.cancel() }
        reset?.takeIf { it.isActive }?.let { return it }
        val pendingRemovals = removals.values.toList()
        val job = persistenceScope.launch(start = CoroutineStart.LAZY) {
            pendingRemovals.joinAll()
            repository.resetUnreadableStorage()
        }
        reset = job
        job.invokeOnCompletion { synchronized(this) { if (reset === job) reset = null } }
        job.start()
        return job
    }
}
