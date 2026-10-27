package com.tsonglew.quodex.diagnostics

import java.io.File
import java.time.Instant

/** Only fixed event names and numeric/boolean metadata are accepted. Never pass API payloads. */
enum class DiagnosticEvent {
    UNCAUGHT_EXCEPTION, HTTP_START, HTTP_END, HTTP_NETWORK_FAILURE, SNAPSHOT_SAVED,
    FOREGROUND, MANUAL_REFRESH, WIDGET_CLICK, APP_START, WIDGET_STATE, UI_STATE, UI_ERROR, REFRESH_START, REFRESH_END, REFRESH_CANCELLED,
    SKIP_ACCOUNT, SKIP_DEMO, SKIP_AUTH, SKIP_BACKOFF, SKIP_RECENT, SKIP_FRESH,
    RENEW, WORK_START, WORK_END, SCHEDULE, CANCEL_WORK, WIDGET_START, WIDGET_END, WIDGET_ERROR, WIDGET_OPEN_APP,
    DESKTOP_ICON_ERROR,
}

class Diagnostics(private val file: File) {
    private val monitor = Any()
    private val observations = ObservationStore(File(file.parentFile, "observations.csv"))
    fun record(event: DiagnosticEvent, vararg values: Long) {
        // Diagnostics must never prevent a refresh, rendering, or app startup.
        runCatching {
            synchronized(monitor) {
                runCatching { observations.record(event, values, Instant.now().epochSecond) }
                val line = "${Instant.now()} ${event.name} ${values.joinToString(" ")}"
                val lines = if (file.exists()) file.readLines().takeLast(MAX_EVENTS - 1) else emptyList()
                file.parentFile?.mkdirs()
                file.writeText((lines + line).joinToString("\n", postfix = "\n"))
            }
        }
    }
    fun report(): String = synchronized(monitor) {
        observations.report(Instant.now().epochSecond) + "\nRecent events\n" +
            (if (file.exists()) file.readText() else "暂无诊断事件\n")
    }
    fun clear() = synchronized(monitor) {
        observations.clear()
        check(!file.exists() || file.delete()) { "Diagnostic clear failed" }
    }
    companion object { const val MAX_EVENTS = 200 }
}
