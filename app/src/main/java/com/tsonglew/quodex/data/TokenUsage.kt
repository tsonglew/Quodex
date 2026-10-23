package com.tsonglew.quodex.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.LocalDate
import java.time.OffsetDateTime

@Serializable
data class TokenUsageSnapshot(
    val fetchedAt: Long,
    val lifetimeTokens: Long? = null,
    val peakDailyTokens: Long? = null,
    val dailyBuckets: List<DailyTokenUsage>? = null,
    val statsAsOf: String? = null,
    val partial: Boolean = false,
)

@Serializable
data class DailyTokenUsage(val date: String, val tokens: Long)

/** Decode only aggregate usage. Profile identity and raw backend error messages are never retained. */
object TokenUsageParser {
    fun parse(raw: String, now: Long): TokenUsageSnapshot {
        val root = try { AppJson.parseToJsonElement(raw) as? JsonObject }
            catch (_: Exception) { null } ?: throw ApiFailure(FailureKind.PROTOCOL)
        val stats = root["stats"] as? JsonObject ?: throw ApiFailure(FailureKind.PROTOCOL)
        var partial = false

        fun count(value: JsonElement?): Long? {
            if (value == null || value == JsonNull) return null
            val number = (value as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it >= 0 }
            if (number == null) partial = true
            return number
        }

        val lifetime = count(stats["lifetime_tokens"])
        val peak = count(stats["peak_daily_tokens"])
        val daily = when (val value = stats["daily_usage_buckets"]) {
            null, JsonNull -> null
            is JsonArray -> {
                val buckets = sortedMapOf<String, Long>()
                val unusableDates = mutableSetOf<String>()
                for (item in value) {
                    val bucket = item as? JsonObject
                    val date = (bucket?.get("start_date") as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?.takeIf(::validDate)
                    if (date == null) { partial = true; continue }
                    val tokens = count(bucket?.get("tokens"))
                    if (tokens == null) {
                        partial = true
                        unusableDates += date
                        buckets.remove(date)
                        continue
                    }
                    if (date in unusableDates) continue
                    try {
                        // The official chart combines duplicate dates. Overflow makes that date unknown.
                        buckets[date] = Math.addExact(buckets[date] ?: 0L, tokens)
                    } catch (_: ArithmeticException) {
                        partial = true
                        unusableDates += date
                        buckets.remove(date)
                    }
                }
                // An unusable nonempty payload must not become a fabricated zero-usage period.
                if (value.isNotEmpty() && buckets.isEmpty()) null
                else buckets.map { (date, tokens) -> DailyTokenUsage(date, tokens) }
            }
            else -> { partial = true; null }
        }

        val metadataValue = root["metadata"]
        val metadata = metadataValue as? JsonObject
        if (metadataValue != null && metadataValue != JsonNull && metadata == null) partial = true
        if (metadata?.get("stats_error")?.let { it != JsonNull && it != JsonPrimitive("") } == true) partial = true
        val asOfValue = metadata?.get("stats_as_of")
        val asOf = (asOfValue as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { text ->
            text.length <= 64 && (validDate(text) || runCatching { OffsetDateTime.parse(text) }.isSuccess)
        }
        if (asOfValue != null && asOfValue != JsonNull && asOf == null) partial = true
        return TokenUsageSnapshot(now, lifetime, peak, daily, asOf, partial)
    }

    private val datePattern = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
    private fun validDate(value: String): Boolean = datePattern.matches(value) &&
        runCatching { LocalDate.parse(value) }.isSuccess
}
