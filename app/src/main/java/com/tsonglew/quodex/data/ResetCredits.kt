package com.tsonglew.quodex.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.OffsetDateTime

@Serializable
data class ResetCredit(
    val id: String,
    val resetType: String?,
    val status: String?,
    val grantedAt: Long?,
    val expiresAt: Long?,
    val title: String?,
    val description: String?,
)

@Serializable
data class ResetCreditsSnapshot(
    val fetchedAt: Long,
    val availableCount: Int,
    val credits: List<ResetCredit>,
)

/** Details use a separate read endpoint; its ISO timestamps differ from App Server's seconds. */
object ResetCreditsParser {
    fun parse(raw: String, accountId: String, now: Long): ResetCreditsSnapshot {
        val root = try { AppJson.parseToJsonElement(raw) as? JsonObject }
            catch (_: Exception) { null } ?: throw ApiFailure(FailureKind.PROTOCOL)
        root.str("account_id")?.let { if (it != accountId) throw ApiFailure(FailureKind.FORBIDDEN) }
        val count = root.long("available_count")?.takeIf { it in 0..Int.MAX_VALUE.toLong() }
            ?: throw ApiFailure(FailureKind.PROTOCOL)
        val rows = root["credits"] as? JsonArray ?: throw ApiFailure(FailureKind.PROTOCOL)
        fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)
            ?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        fun JsonObject.time(key: String): Long? = text(key)?.let { value ->
            runCatching { OffsetDateTime.parse(value).toInstant().epochSecond }.getOrNull()
                ?.takeIf { it in 1..253402300799L }
        }
        val credits = rows.map { element ->
            val row = element as? JsonObject ?: throw ApiFailure(FailureKind.PROTOCOL)
            ResetCredit(row.text("id") ?: throw ApiFailure(FailureKind.PROTOCOL),
                row.text("reset_type"), row.text("status"), row.time("granted_at"), row.time("expires_at"),
                row.text("title"), row.text("description"))
        }
        if (credits.distinctBy { it.id }.size != credits.size) throw ApiFailure(FailureKind.PROTOCOL)
        // The service may cap detail rows. Never derive availability from the list length or local clock.
        return ResetCreditsSnapshot(now, count.toInt(), credits)
    }
}

fun demoResetCredits(now: Long) = ResetCreditsSnapshot(now, 2, listOf(
    ResetCredit("demo-reset-1", "codex_rate_limits", "available", now - 2 * 86400, now + 7 * 86400, null, null),
    ResetCredit("demo-reset-2", "codex_rate_limits", "available", now - 86400, now + 21 * 86400, null, null),
))

/** Keep the count and its timestamp from the same, most recent source. */
fun UsageSnapshot.resetCreditAvailability(): Pair<Int?, Long> = resetCreditDetails
    ?.takeIf { resetCredits == null || it.fetchedAt >= fetchedAt }
    ?.let { it.availableCount to it.fetchedAt } ?: (resetCredits to fetchedAt)
