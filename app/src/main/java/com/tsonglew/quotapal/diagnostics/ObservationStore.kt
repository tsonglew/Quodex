package com.tsonglew.quotapal.diagnostics

import java.io.File
import java.time.Instant

/** Bounded hourly aggregates; contains no account identifiers, URLs or response payloads. */
internal class ObservationStore(private val file: File) {
    private data class Hour(
        val epoch: Long, var usage: Long = 0, var auth: Long = 0,
        var nonSuccess: Long = 0, var networkFailure: Long = 0,
        var saved: Long = 0, var lastSavedAt: Long = 0, var maxGap: Long = 0,
    ) {
        fun encode() = listOf(epoch, usage, auth, nonSuccess, networkFailure, saved, lastSavedAt, maxGap).joinToString(",")
    }

    fun record(event: DiagnosticEvent, values: LongArray, now: Long) {
        if (event !in EVENTS) return
        // These historical counters measure quota/auth requests only. Token profile
        // requests (operation 2) remain in the bounded event log, not auth totals.
        if (event != DiagnosticEvent.SNAPSHOT_SAVED && values.firstOrNull() !in listOf(0L, 1L)) return
        val hour = Math.floorDiv(now, 3600)
        val hours = read().filter { it.epoch in (hour - 167)..hour }.toMutableList()
        val previousSavedAt = hours.maxOfOrNull { it.lastSavedAt } ?: 0
        val row = hours.find { it.epoch == hour } ?: Hour(hour).also { hours.add(it) }
        when (event) {
            DiagnosticEvent.HTTP_START -> if (values.firstOrNull() == 0L) row.usage++ else row.auth++
            DiagnosticEvent.HTTP_END -> if ((values.getOrNull(1) ?: 0) !in 200L..299L) row.nonSuccess++
            DiagnosticEvent.HTTP_NETWORK_FAILURE -> row.networkFailure++
            DiagnosticEvent.SNAPSHOT_SAVED -> {
                row.saved++
                if (previousSavedAt > 0 && now >= previousSavedAt) row.maxGap = maxOf(row.maxGap, now - previousSavedAt)
                row.lastSavedAt = now
            }
            else -> Unit
        }
        file.parentFile?.mkdirs()
        val pending = File(file.path + ".tmp")
        pending.writeText(hours.sortedBy { it.epoch }.joinToString("\n", postfix = "\n") { it.encode() })
        check(pending.renameTo(file)) { "Observation write failed" }
    }

    fun report(now: Long): String {
        val hour = Math.floorDiv(now, 3600)
        val hours = read().filter { it.epoch in (hour - 167)..hour }.sortedBy { it.epoch }
        val summaries = listOf(48, 168).joinToString("\n", postfix = "\n") { count ->
            val window = hours.filter { it.epoch >= hour - count + 1 }
            "hourBuckets=$count eventHourBuckets=${window.size}" +
                " usageRequests=${window.sumOf { it.usage }} authRequests=${window.sumOf { it.auth }}" +
                " httpNon2xx=${window.sumOf { it.nonSuccess }} networkFailures=${window.sumOf { it.networkFailure }}" +
                " snapshotsSaved=${window.sumOf { it.saved }}" +
                " maxCompletedSuccessGapSeconds=${window.filter { it.saved > 0 }.maxOfOrNull { it.maxGap } ?: 0}"
        }
        val lastSavedAt = hours.filter { it.saved > 0 }.maxOfOrNull { it.lastSavedAt }?.takeIf { it in 1..now }
        return "Observation summaries schema=1 (UTC hour buckets, including the current partial hour)\n" +
            "Counts do not establish observation duration, uptime or battery usage; absent hours have no recorded events.\n" +
            "Completed gaps end in the selected window and may start before it; ongoing gaps are reported separately.\n" +
            summaries +
            "lastSavedAt=${lastSavedAt ?: "unknown"} secondsSinceLastSuccess=${lastSavedAt?.let { now - it } ?: "unknown"}\n\n" +
            "Hourly observations schema=1 (UTC, last 168 hour buckets; absent hours have no recorded events)\n" +
            "hour usageRequests authRequests httpNon2xx networkFailures snapshotsSaved lastSavedAt maxSuccessGapSeconds\n" +
            hours.joinToString("\n", postfix = "\n") {
                "${Instant.ofEpochSecond(it.epoch * 3600)} ${it.usage} ${it.auth} ${it.nonSuccess} ${it.networkFailure} ${it.saved} ${it.lastSavedAt} ${it.maxGap}"
            }
    }

    fun clear() {
        check(!file.exists() || file.delete()) { "Observation clear failed" }
        val pending = File(file.path + ".tmp")
        check(!pending.exists() || pending.delete()) { "Observation temporary clear failed" }
    }

    private fun read(): List<Hour> = if (!file.exists()) emptyList() else file.readLines().takeLast(168).mapNotNull { line ->
        val v = line.split(',').map { it.toLongOrNull() }
        if (v.size != 8 || v.any { it == null || it < 0 }) null
        else Hour(v[0]!!, v[1]!!, v[2]!!, v[3]!!, v[4]!!, v[5]!!, v[6]!!, v[7]!!)
    }

    companion object {
        private val EVENTS = setOf(DiagnosticEvent.HTTP_START, DiagnosticEvent.HTTP_END,
            DiagnosticEvent.HTTP_NETWORK_FAILURE, DiagnosticEvent.SNAPSHOT_SAVED)
    }
}
