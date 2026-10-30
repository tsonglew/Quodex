package com.tsonglew.quodex.network

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import javax.net.ssl.SSLHandshakeException

class RequestRetryTest {
    @Test fun transientFailuresHaveThreeTotalAttemptsAndIncreasingDelay() = runTest {
        var attempts = 0
        val waits = mutableListOf<Long>()
        val retry = RequestRetry(pause = { waits += it })
        val failure = runCatching { retry.run(classify = ::networkRetry) { attempts++; throw TransportFailure(IOException()) } }.exceptionOrNull()
        assertTrue(failure is TransportFailure)
        assertEquals(3, attempts)
        assertEquals(listOf(1000L, 2000L), waits)
    }

    @Test fun certificateAndOrdinaryIoFailuresAreNotRetried() = runTest {
        for (failure in listOf(TransportFailure(SSLHandshakeException("certificate")),
            IOException("disk full"), HttpFailure(401), HttpFailure(403), HttpFailure(404), HttpFailure(501))) {
            var attempts = 0
            assertSame(failure, runCatching { RequestRetry().run(classify = ::networkRetry) { attempts++; throw failure } }.exceptionOrNull())
            assertEquals(1, attempts)
        }
    }

    @Test fun respectsShortServerWaitAndNeverShortensLongWait() = runTest {
        var time = 1000L
        val waits = mutableListOf<Long>()
        val retry = RequestRetry(now = { time }, pause = { waits += it; time += it / 1000 })
        var attempts = 0
        assertEquals("done", retry.run(classify = ::networkRetry) { if (++attempts == 1) throw HttpFailure(503, 1005); "done" })
        assertEquals(listOf(5000L), waits)
        attempts = 0
        val failure = HttpFailure(429, time + 120)
        assertSame(failure, runCatching { retry.run(classify = ::networkRetry) { attempts++; throw failure } }.exceptionOrNull())
        assertEquals(1, attempts)
        assertEquals(listOf(5000L), waits)
    }

    @Test fun cancellationDuringBackoffDoesNotRunAnotherAttempt() = runTest {
        val entered = CompletableDeferred<Unit>()
        var attempts = 0
        val retry = RequestRetry(pause = { entered.complete(Unit); awaitCancellation() })
        val job = launch { retry.run(classify = ::networkRetry) { attempts++; throw HttpFailure(503) } }
        entered.await()
        job.cancelAndJoin()
        assertEquals(1, attempts)
    }

    @Test fun workerCanOwnTheRetryBudget() = runTest {
        var attempts = 0
        runCatching { RequestRetry().run(enabled = false, classify = ::networkRetry) { attempts++; throw HttpFailure(503) } }
        assertEquals(1, attempts)
    }

    @Test fun parsesSecondsHttpDatesAndInvalidRetryAfterSafely() {
        assertEquals(1120L, retryAfterAt("120", 1000))
        assertEquals(1445412480L, retryAfterAt("Wed, 21 Oct 2015 07:28:00 GMT", 1000))
        assertEquals(Long.MAX_VALUE, retryAfterAt(Long.MAX_VALUE.toString(), 1000))
        listOf(null, "-1", "tomorrow", "1.5").forEach { assertNull(retryAfterAt(it, 1000)) }
    }
}
