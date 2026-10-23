package com.tsonglew.quodex.update

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class UpdateRepositoryTest {
    private val release = AppUpdate(21, "0.1.0-alpha21", "https://github.com/tsonglew/Quodex/releases/tag/v0.1.0-alpha21", "a".repeat(40))
    private var time = 100_000L
    private val store = MemoryStore()
    private val notifier = RecordingNotifier()
    private var requests = 0
    private var response: suspend () -> AppUpdate? = { release }

    private fun repository(installedCode: Long = 20) = UpdateRepository(
        UpdateSource { requests++; response() }, store, notifier, installedCode, now = { time },
    )

    @Test fun automaticChecksWaitTwelveHoursButRunAtTheBoundary() = runBlocking {
        val repository = repository()
        assertTrue(repository.check())
        val checkedAt = time
        time += UpdateRepository.CHECK_INTERVAL_SECONDS - 1
        assertTrue(repository.check())
        assertEquals(1, requests)
        assertEquals(checkedAt, store.value.lastCheckedAt)
        time++
        assertTrue(repository.check())
        assertEquals(2, requests)
        assertEquals(time, store.value.lastCheckedAt)
        assertEquals(listOf(release), notifier.delivered)
    }

    @Test fun concurrentAutomaticChecksShareOneRequest() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        response = { started.complete(Unit); finish.await(); release }
        val repository = repository()
        val first = async { repository.check() }
        withTimeout(5_000) { started.await() }
        assertTrue(repository.state.value.checking)
        val second = async { repository.check() }
        finish.complete(Unit)
        assertTrue(withTimeout(5_000) { first.await() })
        assertTrue(withTimeout(5_000) { second.await() })
        assertEquals(1, requests)
        assertEquals(listOf(release), notifier.delivered)
        assertFalse(repository.state.value.checking)
    }

    @Test fun manualChecksBypassBothAutomaticSettingAndThrottleWithoutNotifying() = runBlocking {
        store.value = SavedUpdates(enabled = false, lastAttemptAt = time, lastCheckedAt = time)
        val repository = repository()
        assertTrue(repository.check())
        assertEquals(0, requests)
        assertTrue(repository.check(force = true))
        assertEquals(1, requests)
        assertEquals(release, repository.state.value.saved.available)
        assertTrue(repository.check(force = true))
        assertEquals(2, requests)
        assertTrue(notifier.delivered.isEmpty())
        assertEquals(0L, store.value.notifiedVersionCode)
    }

    @Test fun disablingClearsNotificationAndStopsSubsequentAutomaticChecks() = runBlocking {
        val repository = repository()
        repository.check()
        val clears = notifier.clearCount
        repository.setEnabled(false)
        assertFalse(store.value.enabled)
        assertFalse(repository.state.value.saved.enabled)
        assertEquals(clears + 1, notifier.clearCount)
        time += UpdateRepository.CHECK_INTERVAL_SECONDS
        repository.check()
        assertEquals(1, requests)
        assertEquals(listOf(release), notifier.delivered)
    }

    @Test fun deniedPermissionDoesNotConsumeNotificationAndForegroundCanRetryFromCache() = runBlocking {
        notifier.permissionGranted = false
        val repository = repository()
        assertTrue(repository.check())
        assertEquals(0L, store.value.notifiedVersionCode)
        assertTrue(notifier.delivered.isEmpty())
        notifier.permissionGranted = true
        time++
        assertTrue(repository.check())
        assertEquals(1, requests)
        assertEquals(listOf(release), notifier.delivered)
        assertEquals(release.versionCode, store.value.notifiedVersionCode)
        repository.check()
        assertEquals(listOf(release), notifier.delivered)
    }

    @Test fun notifiedVersionSurvivesProcessRestartAndNewerVersionCanNotify() = runBlocking {
        repository().check()
        val restarted = repository()
        restarted.check()
        assertEquals(listOf(release), notifier.delivered)
        val newer = release.copy(versionCode = 22, versionName = "0.1.0-alpha22")
        response = { newer }
        time += UpdateRepository.CHECK_INTERVAL_SECONDS
        restarted.check()
        assertEquals(listOf(release, newer), notifier.delivered)
        assertEquals(22L, store.value.notifiedVersionCode)
    }

    @Test fun installationOfCachedUpdateClearsCandidateAndNotificationOnStartup() = runBlocking {
        store.value = SavedUpdates(available = release, lastAttemptAt = time, lastCheckedAt = time, notifiedVersionCode = release.versionCode)
        val upgraded = repository(installedCode = release.versionCode)
        upgraded.initialize()
        assertNull(store.value.available)
        assertNull(upgraded.state.value.saved.available)
        assertEquals(1, notifier.clearCount)
        assertEquals(0, requests)
    }

    @Test fun sourceCannotReintroduceInstalledOrOlderVersion() = runBlocking {
        store.value = SavedUpdates(available = release)
        response = { release.copy(versionCode = 20) }
        val repository = repository()
        assertTrue(repository.check(force = true))
        assertNull(store.value.available)
        assertTrue(notifier.delivered.isEmpty())
        assertEquals(1, notifier.clearCount)
    }

    @Test fun failedCheckPreservesKnownUpdateAndPreviousSuccessTime() = runBlocking {
        val repository = repository()
        repository.check()
        val previousSuccess = time
        time += UpdateRepository.CHECK_INTERVAL_SECONDS
        response = { throw IOException("network unavailable") }
        assertFalse(repository.check())
        assertEquals(release, store.value.available)
        assertEquals(previousSuccess, store.value.lastCheckedAt)
        assertEquals(time, store.value.lastAttemptAt)
        assertNotNull(repository.state.value.error)
        assertFalse(repository.state.value.checking)
        assertEquals(listOf(release), notifier.delivered)
        response = { null }
        assertTrue(repository.check(force = true))
        assertNull(repository.state.value.error)
        assertNull(store.value.available)
        assertEquals(time, store.value.lastCheckedAt)
    }

    @Test fun failedFirstCheckIsNotRecordedAsSuccessfulOrUpToDate() = runBlocking {
        response = { throw IOException("rate limited") }
        val repository = repository()
        assertFalse(repository.check())
        assertEquals(0L, store.value.lastCheckedAt)
        assertNotNull(repository.state.value.error)
        assertFalse(repository.state.value.checking)
    }

    @Test fun cancellationPropagatesAndReleasesCheckingStateWithoutErasingCache() = runBlocking {
        val previousAttempt = time - UpdateRepository.CHECK_INTERVAL_SECONDS
        store.value = SavedUpdates(available = release, lastAttemptAt = previousAttempt, lastCheckedAt = time - 100)
        val entered = CompletableDeferred<Unit>()
        response = { entered.complete(Unit); awaitCancellation() }
        val repository = repository()
        val check = launch { repository.check(force = true) }
        withTimeout(5_000) { entered.await() }
        assertTrue(repository.state.value.checking)
        assertEquals(time, store.value.lastAttemptAt)
        check.cancelAndJoin()
        assertTrue(check.isCancelled)
        assertFalse(repository.state.value.checking)
        assertNull(repository.state.value.error)
        assertEquals(release, store.value.available)
        assertEquals(time - 100, store.value.lastCheckedAt)
        assertEquals(previousAttempt, store.value.lastAttemptAt)
        assertEquals(previousAttempt, repository.state.value.saved.lastAttemptAt)
        assertTrue(notifier.delivered.isEmpty())
        response = { release }
        assertTrue(repository.check())
        assertEquals(2, requests)
        assertEquals(time, store.value.lastAttemptAt)
        assertEquals(time, store.value.lastCheckedAt)
    }

    @Test fun sourceCancellationIsNotConvertedIntoNetworkFailure() = runBlocking {
        response = { throw CancellationException("cancelled request") }
        val repository = repository()
        val failure = runCatching { repository.check(force = true) }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertNull(repository.state.value.error)
        assertFalse(repository.state.value.checking)
        assertEquals(0L, store.value.lastCheckedAt)
        assertEquals(0L, store.value.lastAttemptAt)
        assertEquals(0L, repository.state.value.saved.lastAttemptAt)
    }

    @Test fun backwardsClockDoesNotSuppressChecksUntilOldClockCatchesUp() = runBlocking {
        val repository = repository()
        repository.check()
        time -= 3_600
        assertTrue(repository.check())
        assertEquals(2, requests)
        assertEquals(time, store.value.lastCheckedAt)
        assertEquals(listOf(release), notifier.delivered)
    }

    @Test fun unreadablePreferencesDoNotIssueRequestsOrClaimSuccess() = runBlocking {
        store.readFailure = IOException("cannot read preferences")
        val repository = repository()
        assertFalse(repository.check())
        assertEquals(0, requests)
        assertFalse(repository.state.value.initialized)
        assertNotNull(repository.state.value.error)
        assertTrue(notifier.delivered.isEmpty())
    }

    @Test fun failedPreferenceWriteDoesNotChangeEnabledState() = runBlocking {
        val repository = repository()
        repository.initialize()
        store.writeFailure = IOException("full disk")
        assertTrue(runCatching { repository.setEnabled(false) }.exceptionOrNull() is IOException)
        assertTrue(repository.state.value.saved.enabled)
        assertTrue(store.value.enabled)
    }

    private class MemoryStore : UpdateStore {
        var value = SavedUpdates()
        var readFailure: Exception? = null
        var writeFailure: Exception? = null
        override suspend fun read(): SavedUpdates {
            readFailure?.let { throw it }
            return value
        }
        override suspend fun write(value: SavedUpdates) {
            writeFailure?.let { throw it }
            this.value = value
        }
    }

    private class RecordingNotifier : UpdateNotifier {
        var permissionGranted = true
        var clearCount = 0
        val delivered = mutableListOf<AppUpdate>()
        override fun show(update: AppUpdate): Boolean {
            if (!permissionGranted) return false
            delivered += update
            return true
        }
        override fun clear() { clearCount++ }
    }
}
