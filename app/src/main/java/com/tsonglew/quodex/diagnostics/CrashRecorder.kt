package com.tsonglew.quodex.diagnostics

/** Keep Android's termination/reporting behavior and never serialize exception messages or stacks. */
internal class CrashRecorder(
    private val previous: Thread.UncaughtExceptionHandler,
    private val diagnostics: Diagnostics,
    private val mainThread: Thread,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, failure: Throwable) {
        try {
            val category = when (failure) {
                is OutOfMemoryError -> 1L
                is StackOverflowError -> 2L
                else -> 0L
            }
            diagnostics.record(DiagnosticEvent.UNCAUGHT_EXCEPTION, category, if (thread === mainThread) 1 else 0)
        } finally {
            previous.uncaughtException(thread, failure)
        }
    }
}
