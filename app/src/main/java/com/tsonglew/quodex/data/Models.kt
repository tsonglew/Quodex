package com.tsonglew.quodex.data

import com.tsonglew.quodex.R
import com.tsonglew.quodex.i18n.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

val AppJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Serializable
data class QuotaWindow(
    val id: String,
    val name: String,
    val usedPercent: Double?,
    val durationSeconds: Long?,
    val resetsAt: Long?,
    val sourceLabel: String? = null,
) {
    fun displayedPercent(remaining: Boolean): Double? = usedPercent?.let { if (remaining) 100 - it else it }
    fun percentageLabel(remaining: Boolean, texts: TextResources): String = displayedPercent(remaining)?.let { String.format(Locale.ROOT, "%.0f", it) } ?: texts(R.string.unavailable)
}

@Serializable
data class UsageSnapshot(
    val accountId: String,
    val plan: String?,
    val windows: List<QuotaWindow>,
    val fetchedAt: Long,
    val resetCredits: Int? = null,
    val allowed: Boolean? = null,
    val limitReached: Boolean? = null,
    val tokenUsage: TokenUsageSnapshot? = null,
) {
    fun quotaNotice(texts: TextResources): QuotaNotice? {
        val mainWindows = windows.filter { it.id.startsWith("codex:") || it.id.startsWith("demo:") }
        return when {
            limitReached == true || allowed == false -> QuotaNotice(texts(R.string.usage_is_currently_restricted), texts(R.string.the_service_currently_does_not_allow_codex), texts(R.string.usage_is_currently_restricted))
            mainWindows.isEmpty() && allowed == true -> QuotaNotice(texts(R.string.available), texts(R.string.no_period_quota_was_provided_so_no), texts(R.string.no_period_quota_provided))
            mainWindows.isEmpty() -> QuotaNotice(texts(R.string.no_period_quota_provided), texts(R.string.this_account_did_not_return_period_based), texts(R.string.no_period_quota_provided))
            mainWindows.all { (it.durationSeconds ?: 0) >= 86400 } -> QuotaNotice(texts(R.string.no_short_period_quota_provided), texts(R.string.this_account_did_not_provide_hourly_quota), texts(R.string.no_short_period_data))
            else -> null
        }
    }
}

data class QuotaNotice(val title: String, val description: String, val widgetLabel: String)

@Serializable
data class Session(
    val accessToken: String,
    val refreshToken: String,
    val accountId: String,
    val expiresAt: Long,
    val clientId: String = CodexApi.CLIENT_ID,
) {
    override fun toString(): String = "Session(credentials=redacted)"
}

enum class FailureKind { AUTH, FORBIDDEN, LIMITED, NETWORK, SERVER, PROTOCOL, STORAGE }
class ApiFailure(val kind: FailureKind, val retryAt: Long? = null, val retryable: Boolean = kind == FailureKind.NETWORK) : Exception(kind.name)

fun FailureKind.userMessage(): UiText = when (this) {
    FailureKind.AUTH -> UiText(R.string.connection_expired_sign_in_again)
    FailureKind.FORBIDDEN -> UiText(R.string.this_account_or_workspace_does_not_currently)
    FailureKind.LIMITED -> UiText(R.string.too_many_requests_refresh_later)
    FailureKind.NETWORK -> UiText(R.string.network_connection_failed_previous_data_has_been)
    FailureKind.SERVER -> UiText(R.string.service_temporarily_unavailable_previous_data_has_been)
    FailureKind.PROTOCOL -> UiText(R.string.quota_format_not_yet_supported_check_for)
    FailureKind.STORAGE -> UiText(R.string.could_not_read_secure_credentials_reconnect_your)
}

fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

object UsageParser {
    fun parse(raw: String, accountId: String, now: Long): UsageSnapshot {
        val root = try { AppJson.parseToJsonElement(raw).jsonObject } catch (_: Exception) { throw ApiFailure(FailureKind.PROTOCOL) }
        val returnedId = root.str("account_id")
        if (returnedId != null && returnedId != accountId) throw ApiFailure(FailureKind.FORBIDDEN)
        val mainBucket = root.obj("rate_limit")
        if (root["rate_limit"] != null && root["rate_limit"] != JsonNull && mainBucket == null)
            throw ApiFailure(FailureKind.PROTOCOL)
        val windows = mutableListOf<QuotaWindow>()
        fun addBucket(bucket: JsonObject?, prefix: String, label: String? = null) {
            listOf("primary_window", "secondary_window").forEach { key ->
                val element = bucket?.get(key)
                if (element != null && element != JsonNull && element !is JsonObject)
                    throw ApiFailure(FailureKind.PROTOCOL)
                val window = bucket?.obj(key) ?: return@forEach
                val used = (window["used_percent"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it in 0.0..100.0 }
                val duration = window.long("limit_window_seconds")?.takeIf { it > 0 }
                val name = when (duration) {
                    604800L -> "Weekly quota"
                    86400L -> "Daily quota"
                    null -> "Quota window"
                    else -> if (duration % 3600L == 0L) "${duration / 3600}-hour quota" else "${duration / 60}-minute quota"
                }
                windows += QuotaWindow("$prefix:$key", if (label.isNullOrBlank()) name else "$label · $name", used, duration,
                    window.long("reset_at")?.takeIf { it > 0 } ?: window.long("reset_after_seconds")?.takeIf { it >= 0 }?.let { now + it }, label)
            }
        }
        addBucket(mainBucket, "codex")
        (root["additional_rate_limits"] as? JsonArray)?.forEachIndexed { index, item ->
            val extra = item as? JsonObject ?: return@forEachIndexed
            addBucket(extra.obj("rate_limit"), extra.str("metered_feature") ?: "extra-$index", extra.str("limit_name"))
        }
        // Optional windows may legitimately be absent. A missing/malformed payload is still a failure.
        val knownEmptyQuota = mainBucket?.bool("allowed") != null || mainBucket?.bool("limit_reached") != null ||
            (root.containsKey("rate_limit") && !root.str("plan_type").isNullOrBlank())
        if (windows.isEmpty() && !knownEmptyQuota) throw ApiFailure(FailureKind.PROTOCOL)
        return UsageSnapshot(accountId, root.str("plan_type"), windows.sortedByDescending { it.durationSeconds ?: 0 }, now,
            root.obj("rate_limit_reset_credits")?.long("available_count")?.takeIf { it >= 0 && it <= Int.MAX_VALUE }?.toInt(),
            mainBucket?.bool("allowed"), mainBucket?.bool("limit_reached"))
    }
}

fun absoluteTime(epoch: Long?, includeDate: Boolean = false): String {
    if (epoch == null) return "—"
    val format = if (includeDate) "MM/dd HH:mm" else "HH:mm"
    return DateTimeFormatter.ofPattern(format, Locale.ROOT).withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(epoch))
}

fun resetLabel(window: QuotaWindow, texts: TextResources, now: Long = Instant.now().epochSecond): String = when {
    window.resetsAt == null -> texts(R.string.reset_time_unavailable)
    window.resetsAt <= now -> texts(R.string.reset_unconfirmed_refresh_to_check)
    else -> texts(R.string.resets_value, absoluteTime(window.resetsAt, true))
}

fun demoSnapshot(now: Long) = UsageSnapshot("demo", "plus", listOf(
    QuotaWindow("demo:weekly", "Weekly quota", 38.0, 604800, now + 3 * 86400 + 18 * 3600),
    QuotaWindow("demo:session", "5-hour quota", 64.0, 18000, now + 2 * 3600 + 14 * 60),
), now, 2)

/** Reconstruct built-in labels so old snapshots follow the newly selected language. */
fun QuotaWindow.displayName(texts: TextResources): String {
    val period = when (durationSeconds) {
        604800L -> texts(R.string.weekly_quota)
        86400L -> texts(R.string.daily_quota)
        null -> texts(R.string.quota_window)
        else -> if (durationSeconds % 3600L == 0L) texts(R.string.value_hour_quota, durationSeconds / 3600)
            else texts(R.string.value_minute_quota, durationSeconds / 60)
    }
    val label = sourceLabel ?: if (!id.startsWith("codex:") && !id.startsWith("demo:") && " · " in name)
        name.substringBeforeLast(" · ") else null
    return if (label.isNullOrBlank()) period else "$label · $period"
}
