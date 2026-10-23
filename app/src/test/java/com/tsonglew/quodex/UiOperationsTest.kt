package com.tsonglew.quodex

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UiOperationsTest {
    @Test fun settingsReadRecoversWithoutEmittingFakeDefaults() = runTest {
        var attempts = 0
        var errors = 0
        val values = flow {
            when (attempts++) {
                0 -> { emit("dark"); throw IOException("unavailable") }
                1 -> throw IOException("still unavailable")
                else -> emit("light")
            }
        }.retrySettings { errors++ }.take(2).toList()
        assertEquals(listOf("dark", "light"), values)
        assertEquals(2, errors)
        assertEquals(3_000L, testScheduler.currentTime)
    }

    @Test fun failedWriteReportsFailureAndCanBeRetried() = runTest {
        var errors = 0
        var saved = false
        runUiOperation({ errors++ }) { throw IOException("full") }
        runUiOperation({ errors++ }) { saved = true }
        assertEquals(1, errors)
        assertTrue(saved)
    }

    @Test fun cancellationIsNeverConvertedToAnError() = runTest {
        var errors = 0
        try {
            runUiOperation({ errors++ }) { throw CancellationException("cancelled") }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(0, errors)
    }

    @Test fun programmingErrorsInPreferencesDoNotLoopForever() = runTest {
        var attempts = 0
        try {
            flow<String> { attempts++; throw IllegalArgumentException("invalid") }
                .retrySettings { fail("Only IO failures should retry") }.toList()
            fail("Expected invalid data to propagate")
        } catch (_: IllegalArgumentException) { }
        assertEquals(1, attempts)
    }
}
