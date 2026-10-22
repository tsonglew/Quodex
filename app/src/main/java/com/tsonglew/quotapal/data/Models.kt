package com.tsonglew.quotapal.data

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
) {
    fun displayedPercent(remaining: Boolean): Double? = usedPercent?.let { if (remaining) 100 - it else it }
    fun percentageLabel(remaining: Boolean): String = displayedPercent(remaining)?.let { String.format(Locale.ROOT, "%.0f", it) } ?: "暂不可用"
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
    fun quotaNotice(): QuotaNotice? {
        val mainWindows = windows.filter { it.id.startsWith("codex:") || it.id.startsWith("demo:") }
        return when {
            limitReached == true || allowed == false -> QuotaNotice("当前使用受限", "服务端暂不允许使用 Codex，请以账号实际权益与限制为准。", "当前使用受限")
            mainWindows.isEmpty() && allowed == true -> QuotaNotice("当前可用", "账号未提供周期额度，无需显示百分比。实际使用仍以账号权益为准。", "未提供周期额度")
            mainWindows.isEmpty() -> QuotaNotice("未提供周期额度", "当前账号未返回按周期计算的额度，请以账号实际权益为准。", "未提供周期额度")
            mainWindows.all { (it.durationSeconds ?: 0) >= 86400 } -> QuotaNotice("未提供短周期额度", "当前账号未提供按小时计算的额度；已返回的长期额度仍正常显示。", "无短周期数据")
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
class ApiFailure(val kind: FailureKind, val retryAt: Long? = null) : Exception(kind.name)

fun FailureKind.userMessage(): String = when (this) {
    FailureKind.AUTH -> "连接已失效，请重新登录"
    FailureKind.FORBIDDEN -> "当前账号或工作区暂不允许访问"
    FailureKind.LIMITED -> "请求过于频繁，请稍后刷新"
    FailureKind.NETWORK -> "网络连接失败，已保留上次数据"
    FailureKind.SERVER -> "服务暂时不可用，已保留上次数据"
    FailureKind.PROTOCOL -> "额度格式暂不兼容，请检查更新"
    FailureKind.STORAGE -> "无法读取安全凭据，请重新连接账号"
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
                    604800L -> "每周额度"
                    86400L -> "每日额度"
                    null -> "额度窗口"
                    else -> if (duration % 3600L == 0L) "${duration / 3600} 小时额度" else "${duration / 60} 分钟额度"
                }
                windows += QuotaWindow("$prefix:$key", if (label.isNullOrBlank()) name else "$label · $name", used, duration,
                    window.long("reset_at")?.takeIf { it > 0 } ?: window.long("reset_after_seconds")?.takeIf { it >= 0 }?.let { now + it })
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
    if (epoch == null) return "暂不可用"
    val format = if (includeDate) "MM/dd HH:mm" else "HH:mm"
    return DateTimeFormatter.ofPattern(format, Locale.CHINA).withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(epoch))
}

fun resetLabel(window: QuotaWindow, now: Long = Instant.now().epochSecond): String = when {
    window.resetsAt == null -> "重置时间暂不可用"
    window.resetsAt <= now -> "重置待确认 · 请刷新"
    else -> "${absoluteTime(window.resetsAt, true)} 重置"
}

fun demoSnapshot(now: Long) = UsageSnapshot("demo", "plus", listOf(
    QuotaWindow("demo:weekly", "每周额度", 38.0, 604800, now + 3 * 86400 + 18 * 3600),
    QuotaWindow("demo:session", "5 小时额度", 64.0, 18000, now + 2 * 3600 + 14 * 60),
), now, 2)
