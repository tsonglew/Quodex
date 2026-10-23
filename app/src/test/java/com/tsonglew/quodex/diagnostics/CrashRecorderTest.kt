package com.tsonglew.quodex.diagnostics

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

class CrashRecorderTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun preservesOriginalHandlerAndRedactsExceptionMessageAndStack() {
        val diagnostics = Diagnostics(File(temp.root, "events.log"))
        val failure = IllegalStateException("secret-token-in-exception")
        failure.stackTrace = arrayOf(StackTraceElement("secret-account-class", "secret-method", "secret-file", 1))
        var delegated = false
        val thread = Thread.currentThread()
        CrashRecorder(Thread.UncaughtExceptionHandler { actualThread, actualFailure ->
            assertSame(thread, actualThread)
            assertSame(failure, actualFailure)
            delegated = true
        }, diagnostics, thread).uncaughtException(thread, failure)
        assertTrue(delegated)
        val report = diagnostics.report()
        assertTrue(report.contains("UNCAUGHT_EXCEPTION 0 1"))
        assertFalse(report.contains("secret-"))
    }
    @Test fun loggingFailureCannotSwallowOriginalCrash() {
        val parent = temp.newFile()
        var delegated = false
        CrashRecorder(Thread.UncaughtExceptionHandler { _, _ -> delegated = true },
            Diagnostics(File(parent, "events.log")), Thread.currentThread())
            .uncaughtException(Thread.currentThread(), OutOfMemoryError("private-message"))
        assertTrue(delegated)
    }
}
