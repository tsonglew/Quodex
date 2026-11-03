package com.tsonglew.quodex.update

import com.tsonglew.quodex.i18n.*
import com.tsonglew.quodex.R
import com.tsonglew.quodex.network.HttpFailure
import com.tsonglew.quodex.diagnostics.Diagnostics
import com.tsonglew.quodex.diagnostics.DiagnosticEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class SavedUpdates(
    val enabled: Boolean = true,
    val available: AppUpdate? = null,
    val lastAttemptAt: Long = 0,
    val lastCheckedAt: Long = 0,
    val notifiedVersionCode: Long = 0,
    val retryAt: Long = 0,
    val sourceGeneration: Int = 0,
)

data class UpdateState(
    val saved: SavedUpdates = SavedUpdates(),
    val initialized: Boolean = false,
    val checking: Boolean = false,
    val error: UiText? = null,
)

interface UpdateStore {
    suspend fun read(): SavedUpdates
    suspend fun write(value: SavedUpdates)
}

interface UpdateNotifier {
    /** False means permission/channel disabled or delivery failed; do not mark as notified. */
    fun show(update: AppUpdate): Boolean
    fun clear()
}

class UpdateRepository(
    private val source: UpdateSource,
    private val store: UpdateStore,
    private val notifier: UpdateNotifier,
    private val installedVersionCode: Long,
    private val now: () -> Long = { Instant.now().epochSecond },
    private val diagnostics: Diagnostics? = null,
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(UpdateState())
    val state = mutableState.asStateFlow()

    private suspend fun load() {
        if (state.value.initialized) return
        val saved = store.read()
        // REST API cooldowns do not apply to the new static index origin. Once
        // migrated, retain all cooldowns from this source across process restarts.
        val legacy = saved.sourceGeneration < SOURCE_GENERATION
        val current = saved.copy(available = saved.available?.takeIf { it.versionCode > installedVersionCode },
            retryAt = if (legacy) 0 else saved.retryAt,
            lastAttemptAt = if (legacy && saved.retryAt > 0) 0 else saved.lastAttemptAt,
            sourceGeneration = SOURCE_GENERATION)
        if (current != saved) store.write(current)
        if (current.available == null || !current.enabled) notifier.clear()
        mutableState.value = UpdateState(saved = current, initialized = true)
    }

    suspend fun initialize() = withContext(Dispatchers.IO) { mutex.withLock { load() } }

    suspend fun setEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            load()
            val saved = state.value.saved.copy(enabled = enabled)
            store.write(saved)
            mutableState.value = state.value.copy(saved = saved)
            if (!enabled) notifier.clear()
        }
    }

    /** Manual checks remain available with automatic checks disabled. */
    suspend fun check(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            var previousAttempt: Long? = null
            try {
                load()
                var saved = state.value.saved
                if (!force && !saved.enabled) return@withLock true
                val time = now()
                // A manual tap must also respect a server-provided cooldown.
                if (time < saved.retryAt) {
                    diagnostics?.record(DiagnosticEvent.UPDATE_WAIT, saved.retryAt)
                    mutableState.value = state.value.copy(error = UiText(R.string.network_retry_after))
                    return@withLock false
                }
                val age = time - saved.lastAttemptAt
                if (!force && saved.lastAttemptAt > 0 && age in 0 until CHECK_INTERVAL_SECONDS) {
                    notifyAvailable(saved)
                    return@withLock true
                }
                mutableState.value = state.value.copy(checking = true, error = null)
                diagnostics?.record(DiagnosticEvent.UPDATE_CHECK_START, if (force) 1 else 0)
                previousAttempt = saved.lastAttemptAt
                saved = saved.copy(lastAttemptAt = time)
                store.write(saved)
                mutableState.value = state.value.copy(saved = saved)
                val available = source.latest()?.takeIf { it.versionCode > installedVersionCode }
                saved = saved.copy(available = available, lastCheckedAt = now(), retryAt = 0)
                store.write(saved)
                mutableState.value = state.value.copy(saved = saved)
                if (available == null) notifier.clear()
                notifyAvailable(saved)
                diagnostics?.record(DiagnosticEvent.UPDATE_CHECK_END, 1, 0, 0)
                true
            } catch (cancel: CancellationException) {
                diagnostics?.record(DiagnosticEvent.UPDATE_CANCELLED)
                // Leaving the screen/cancelling a worker must not suppress checks for 12 hours.
                previousAttempt?.let { previous ->
                    withContext(NonCancellable) {
                        val saved = state.value.saved.copy(lastAttemptAt = previous)
                        try {
                            store.write(saved)
                            mutableState.value = state.value.copy(saved = saved)
                        } catch (_: Exception) { /* Keep the last durable state if storage is unavailable. */ }
                    }
                }
                throw cancel
            } catch (failure: Exception) {
                val deadline = (failure as? HttpFailure)?.retryAt?.takeIf { it > now() }
                diagnostics?.record(DiagnosticEvent.UPDATE_CHECK_END, 0,
                    (failure as? HttpFailure)?.status?.toLong() ?: 0, deadline ?: 0)
                if (deadline != null) {
                    val saved = state.value.saved.copy(retryAt = deadline)
                    // Keep the in-memory guard even if persisting settings fails.
                    mutableState.value = state.value.copy(saved = saved)
                    try { store.write(saved) } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { }
                }
                mutableState.value = state.value.copy(error = UiText(if (deadline != null) R.string.network_retry_after else R.string.could_not_check_for_updates_check_your))
                false
            } finally {
                mutableState.value = state.value.copy(checking = false)
            }
        }
    }

    private suspend fun notifyAvailable(saved: SavedUpdates) {
        val update = saved.available ?: return
        if (!saved.enabled || update.versionCode <= saved.notifiedVersionCode) return
        if (notifier.show(update)) {
            val notified = saved.copy(notifiedVersionCode = update.versionCode)
            store.write(notified)
            mutableState.value = state.value.copy(saved = notified)
        }
    }

    companion object {
        const val CHECK_INTERVAL_SECONDS = 12 * 60 * 60L
        const val SOURCE_GENERATION = 1
    }
}
