package com.tsonglew.quodex.subscriptions

import com.tsonglew.quodex.data.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.time.Instant

@Serializable
enum class SubscriptionProvider(val displayName: String, val dashboard: String) {
    DEEPSEEK("DeepSeek", "https://platform.deepseek.com/usage"),
    OPENCODE_GO("OpenCode Go", "https://opencode.ai/console"),
    OPENCODE_ZEN("OpenCode Zen", "https://opencode.ai/console"),
    CURSOR("Cursor", "https://cursor.com/dashboard"),
}

@Serializable
enum class SubscriptionMetricKind { BALANCE, AVAILABLE, GRANTED, TOPPED_UP, ROLLING, WEEKLY, MONTHLY, CURSOR_MODELS, OTHER_MODELS, TOTAL, ON_DEMAND }

@Serializable
data class SubscriptionMetric(
    val kind: SubscriptionMetricKind,
    val value: String?,
    val currency: String? = null,
    val resetsAt: Long? = null,
)

@Serializable
data class SubscriptionSnapshot(
    val provider: SubscriptionProvider,
    val fetchedAt: Long,
    val metrics: List<SubscriptionMetric>,
    val plan: String? = null,
    val available: Boolean? = null,
)

/** Credentials never enter UI state, diagnostics, snapshots or toString output. */
@Serializable
data class SubscriptionAccount(val credential: String, val snapshot: SubscriptionSnapshot) {
    override fun toString() = "SubscriptionAccount(credentials=redacted)"
}

data class SubscriptionState(
    val connected: Boolean = false,
    val snapshot: SubscriptionSnapshot? = null,
    val busy: Boolean = false,
    val failure: FailureKind? = null,
    val retryAt: Long = 0,
)

object SubscriptionParser {
    fun parse(provider: SubscriptionProvider, raw: String, now: Long): SubscriptionSnapshot = try {
        val root = AppJson.parseToJsonElement(raw).jsonObject
        when (provider) {
            SubscriptionProvider.DEEPSEEK -> deepseek(root, now)
            SubscriptionProvider.OPENCODE_GO -> go(root, now)
            SubscriptionProvider.OPENCODE_ZEN -> zen(root, now)
            SubscriptionProvider.CURSOR -> cursor(root, now)
        }
    } catch (failure: ApiFailure) { throw failure }
    catch (_: Exception) { throw ApiFailure(FailureKind.PROTOCOL) }

    private fun decimal(value: JsonElement?): String? {
        if (value == null || value == JsonNull) return null
        val text = (value as? JsonPrimitive)?.contentOrNull ?: error("number")
        require(text.length <= 64)
        val number = text.toBigDecimalOrNull() ?: error("number")
        require(number.precision() <= 30 && number.scale() in -12..12)
        return number.stripTrailingZeros().toPlainString()
    }
    private fun percent(value: JsonElement?): String? = decimal(value)?.also { require(BigDecimal(it).signum() >= 0) }
    private fun timestamp(value: JsonElement?): Long? {
        if (value == null || value == JsonNull) return null
        return Instant.parse((value as JsonPrimitive).content).epochSecond.also { require(it > 0) }
    }

    private fun deepseek(root: JsonObject, now: Long): SubscriptionSnapshot {
        val available = root.bool("is_available") ?: error("availability")
        val rows = root["balance_infos"] as? JsonArray ?: error("balances")
        require(rows.isNotEmpty() && rows.size <= 8)
        val currencies = mutableSetOf<String>()
        val metrics = rows.flatMap { element ->
            val row = element.jsonObject
            val currency = row.str("currency") ?: error("currency")
            require(currency in setOf("CNY", "USD") && currencies.add(currency))
            listOf("total_balance" to SubscriptionMetricKind.BALANCE,
                "granted_balance" to SubscriptionMetricKind.GRANTED,
                "topped_up_balance" to SubscriptionMetricKind.TOPPED_UP).map { (field, kind) ->
                SubscriptionMetric(kind, decimal(row[field]) ?: error("balance"), currency)
            }
        }
        return SubscriptionSnapshot(SubscriptionProvider.DEEPSEEK, now, metrics, available = available)
    }

    private fun go(root: JsonObject, now: Long): SubscriptionSnapshot {
        val usage = root.obj("usage") ?: error("usage")
        val metrics = listOf("rolling" to SubscriptionMetricKind.ROLLING, "weekly" to SubscriptionMetricKind.WEEKLY,
            "monthly" to SubscriptionMetricKind.MONTHLY).map { (field, kind) ->
            val row = usage.obj(field) ?: error("window")
            require(row.str("status") in setOf("ok", "rate-limited"))
            SubscriptionMetric(kind, percent(row["percent"]), resetsAt = timestamp(row["resetsAt"]))
        }
        require(metrics.any { it.value != null })
        return SubscriptionSnapshot(SubscriptionProvider.OPENCODE_GO, now, metrics)
    }

    private fun cursor(root: JsonObject, now: Long): SubscriptionSnapshot {
        val individual = root.obj("individualUsage") ?: error("individual usage")
        val plan = individual.obj("plan") ?: error("plan")
        val reset = timestamp(root["billingCycleEnd"])
        // Server percentages are authoritative. Dollar allowances do not describe
        // the separate Cursor / third-party pools and must not generate percentages.
        val metrics = listOf("autoPercentUsed" to SubscriptionMetricKind.CURSOR_MODELS,
            "apiPercentUsed" to SubscriptionMetricKind.OTHER_MODELS,
            "totalPercentUsed" to SubscriptionMetricKind.TOTAL).map { (field, kind) ->
            SubscriptionMetric(kind, percent(plan[field]), resetsAt = reset)
        }.toMutableList()
        individual.obj("onDemand")?.takeIf { it.bool("enabled") == true }?.let { demand ->
            decimal(demand["used"])?.let {
                require(BigDecimal(it).signum() >= 0)
                metrics += SubscriptionMetric(SubscriptionMetricKind.ON_DEMAND,
                    BigDecimal(it).movePointLeft(2).toPlainString(), "USD")
            }
        }
        require(metrics.any { it.value != null })
        return SubscriptionSnapshot(SubscriptionProvider.CURSOR, now, metrics,
            plan = root.str("membershipType")?.take(80))
    }

    private fun zen(root: JsonObject, now: Long): SubscriptionSnapshot {
        val mode = root.str("billingMode") ?: error("billing mode")
        require(mode in setOf("legacy", "prepaid", "seat", "credit"))
        // The Console schema encodes integer microcents as strings: 1 USD = 10^8.
        val metrics = listOf("balanceMicroCents" to SubscriptionMetricKind.BALANCE,
            "availableMicroCents" to SubscriptionMetricKind.AVAILABLE).map { (field, kind) ->
            val number = BigDecimal(decimal(root[field]) ?: error("balance"))
            require(number.stripTrailingZeros().scale() <= 0)
            SubscriptionMetric(kind, number.movePointLeft(8).toPlainString(), "USD")
        }
        return SubscriptionSnapshot(SubscriptionProvider.OPENCODE_ZEN, now, metrics, plan = mode)
    }
}
