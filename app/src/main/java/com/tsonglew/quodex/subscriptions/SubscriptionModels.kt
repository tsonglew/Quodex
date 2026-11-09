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
    KIMI("Kimi", "https://www.kimi.com/settings/subscription"),
    WORKBUDDY("WorkBuddy", "https://www.workbuddy.ai/profile/plans-usage"),
    GROK("Grok / Cursor", "https://grok.com/dashboard/usage"),
    KIMI_CODE("Kimi Code", "https://www.kimi.com/code/console"),
    KIMI_API("Kimi API · CN", "https://platform.kimi.com/console"),
    KIMI_API_GLOBAL("Kimi API · Global", "https://platform.kimi.ai/console"),
    GROK_API("Grok API", "https://console.x.ai"),
    CURSOR_BILLING("Cursor Admin API", "https://cursor.com/dashboard"),
}

@Serializable
enum class SubscriptionMetricKind { BALANCE, AVAILABLE, GRANTED, TOPPED_UP, ROLLING, WEEKLY, MONTHLY, CURSOR_MODELS, OTHER_MODELS, TOTAL, ON_DEMAND, QUOTA_WINDOW, API_SPEND, EXTRA_BALANCE, CREDITS_REMAINING }

@Serializable
enum class SubscriptionMetricUnit { PERCENT, CREDITS }

@Serializable
data class SubscriptionMetric(
    val kind: SubscriptionMetricKind,
    val value: String?,
    val currency: String? = null,
    val resetsAt: Long? = null,
    val windowSeconds: Long? = null,
    val unit: SubscriptionMetricUnit = SubscriptionMetricUnit.PERCENT,
    val limit: String? = null,
    val label: String? = null,
    val serverPeriodEnd: String? = null,
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
data class SubscriptionAccount(val credential: String, val snapshot: SubscriptionSnapshot, val imported: Boolean = false, val browser: Boolean = false) {
    override fun toString() = "SubscriptionAccount(credentials=redacted)"
}

data class SubscriptionState(
    val connected: Boolean = false,
    val snapshot: SubscriptionSnapshot? = null,
    val busy: Boolean = false,
    val failure: FailureKind? = null,
    val retryAt: Long = 0,
    val imported: Boolean = false,
    val browser: Boolean = false,
    val automaticRetryAt: Long = 0,
)

object SubscriptionParser {
    fun parse(provider: SubscriptionProvider, raw: String, now: Long): SubscriptionSnapshot = try {
        val root = AppJson.parseToJsonElement(raw).jsonObject
        when (provider) {
            SubscriptionProvider.DEEPSEEK -> deepseek(root, now)
            SubscriptionProvider.OPENCODE_GO -> go(root, now)
            SubscriptionProvider.OPENCODE_ZEN -> zen(root, now)
            SubscriptionProvider.CURSOR -> cursor(root, now)
            SubscriptionProvider.KIMI -> kimiMembership(root, now)
            SubscriptionProvider.WORKBUDDY -> workbuddy(root, now)
            SubscriptionProvider.GROK -> grokMembership(root, now)
            SubscriptionProvider.KIMI_CODE -> kimiCode(root, now)
            SubscriptionProvider.KIMI_API, SubscriptionProvider.KIMI_API_GLOBAL -> kimiBalance(provider, root, now)
            SubscriptionProvider.GROK_API -> grokBalance(root, now)
            SubscriptionProvider.CURSOR_BILLING -> cursorBilling(root, now)
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

    private fun kimiMembership(root: JsonObject, now: Long): SubscriptionSnapshot {
        val balance = root.obj("subscriptionBalance") ?: error("subscription balance")
        require(balance.str("unit") == "UNIT_CREDIT")
        val ratio = BigDecimal(percent(balance["amountUsedRatio"]) ?: error("usage ratio"))
        val metrics = mutableListOf(SubscriptionMetric(SubscriptionMetricKind.TOTAL,
            ratio.multiply(BigDecimal(100)).stripTrailingZeros().toPlainString(), resetsAt = timestamp(balance["expireTime"])))
        val wallets = root["boosterWallets"] as? JsonArray ?: JsonArray(emptyList())
        require(wallets.size <= 8)
        wallets.forEach { element ->
            val wallet = element.jsonObject
            if (wallet.str("status") == "STATUS_ACTIVE") {
                val money = wallet.obj("moneyLeft") ?: error("wallet")
                val currency = money.str("currency") ?: error("currency")
                require(currency in setOf("CNY", "USD"))
                val cents = BigDecimal(decimal(money["priceInCents"]) ?: error("wallet balance"))
                require(cents.stripTrailingZeros().scale() <= 0)
                metrics += SubscriptionMetric(SubscriptionMetricKind.EXTRA_BALANCE, cents.movePointLeft(2).toPlainString(), currency)
            }
        }
        return SubscriptionSnapshot(SubscriptionProvider.KIMI, now, metrics)
    }

    private fun grokMembership(root: JsonObject, now: Long): SubscriptionSnapshot {
        require(root.str("availability") == "available")
        val metrics = mutableListOf<SubscriptionMetric>()
        root.obj("weeklyIncludedUsage")?.let { weekly ->
            val used = BigDecimal(percent(weekly["usedCents"]) ?: error("usage"))
            val limit = BigDecimal(percent(weekly["budgetCents"]) ?: error("budget"))
            val value = if (limit.signum() == 0) null else used.multiply(BigDecimal(100))
                .divide(limit, 6, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
            metrics += SubscriptionMetric(SubscriptionMetricKind.WEEKLY, value, resetsAt = timestamp(weekly["resetsAt"]))
        }
        root.obj("monthlyUsage")?.let { monthly ->
            val used = BigDecimal(percent(monthly["usedCents"]) ?: error("usage"))
            metrics += SubscriptionMetric(SubscriptionMetricKind.ON_DEMAND, used.movePointLeft(2).toPlainString(), "USD",
                resetsAt = timestamp(monthly["resetsAt"]))
        }
        // This wallet belongs to the unified subscription; it is not xAI API credit.
        require(metrics.isNotEmpty())
        val accountBalance = root.obj("accountBalance")
        accountBalance?.let { wallet ->
            val cents = BigDecimal(decimal(wallet["usdRemainingCents"]) ?: error("balance"))
            metrics += SubscriptionMetric(SubscriptionMetricKind.EXTRA_BALANCE, cents.movePointLeft(2).toPlainString(), "USD")
        }
        if (accountBalance == null) metrics += SubscriptionMetric(SubscriptionMetricKind.EXTRA_BALANCE, null, "USD")
        return SubscriptionSnapshot(SubscriptionProvider.GROK, now, metrics)
    }

    private fun workbuddy(root: JsonObject, now: Long): SubscriptionSnapshot {
        require(root["code"]?.jsonPrimitive?.intOrNull == 0)
        val data = root.obj("data")?.obj("Response")?.obj("Data") ?: error("resource data")
        val rows = data["Accounts"] as? JsonArray ?: error("resources")
        require(data["TotalCount"]?.jsonPrimitive?.intOrNull == rows.size && rows.size <= 4000)
        val metrics = rows.mapNotNull { element ->
            val row = element.jsonObject
            if (row.str("CapacityUnit") !in setOf("credit", "credits")) return@mapNotNull null
            fun quantity(field: String): String = percent(row["${field}Precise"] ?: row[field]) ?: error("quantity")
            val remaining = quantity("CycleCapacityRemain")
            val limit = quantity("CycleCapacitySize")
            require(BigDecimal(remaining) <= BigDecimal(limit))
            val label = row.str("PackageName")?.take(80)?.takeIf { value -> value.none { it.isISOControl() } }
            // This API returns a wall-clock cycle end without a UTC offset. Keep it
            // explicitly labelled as server time instead of guessing a device zone.
            val period = row.str("CycleEndTime")?.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")) }
            SubscriptionMetric(SubscriptionMetricKind.CREDITS_REMAINING, remaining,
                unit = SubscriptionMetricUnit.CREDITS, limit = limit, label = label, serverPeriodEnd = period)
        }
        require(metrics.isNotEmpty() || rows.isEmpty())
        return SubscriptionSnapshot(SubscriptionProvider.WORKBUDDY, now,
            metrics.ifEmpty { listOf(SubscriptionMetric(SubscriptionMetricKind.CREDITS_REMAINING, "0", unit = SubscriptionMetricUnit.CREDITS)) })
    }

    private fun kimiBalance(provider: SubscriptionProvider, root: JsonObject, now: Long): SubscriptionSnapshot {
        require(root["code"]?.jsonPrimitive?.intOrNull == 0 && root.bool("status") == true)
        val data = root.obj("data") ?: error("balance")
        val currency = if (provider == SubscriptionProvider.KIMI_API) "CNY" else "USD"
        val metrics = listOf("available_balance" to SubscriptionMetricKind.AVAILABLE,
            "voucher_balance" to SubscriptionMetricKind.GRANTED, "cash_balance" to SubscriptionMetricKind.TOPPED_UP)
            .map { (field, kind) ->
                val value = decimal(data[field]) ?: error("balance")
                if (field == "voucher_balance") require(BigDecimal(value).signum() >= 0)
                SubscriptionMetric(kind, value, currency)
            }
        // Cash may be negative; the server's available balance is authoritative.
        return SubscriptionSnapshot(provider, now, metrics, available = BigDecimal(metrics.first().value!!).signum() > 0)
    }

    private fun kimiCode(root: JsonObject, now: Long): SubscriptionSnapshot {
        fun quota(detail: JsonObject, kind: SubscriptionMetricKind, seconds: Long? = null): SubscriptionMetric {
            val limit = decimal(detail["limit"])?.toBigDecimal() ?: error("limit")
            val used = decimal(detail["used"])?.toBigDecimal()
            val remaining = decimal(detail["remaining"])?.toBigDecimal()
            require(limit.signum() >= 0 && used?.signum() != -1 && remaining?.signum() != -1)
            require(remaining == null || remaining <= limit)
            val consumed = used ?: remaining?.let { limit - it } ?: error("usage")
            val value = if (limit.signum() == 0) null else consumed.multiply(BigDecimal(100))
                .divide(limit, 6, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
            return SubscriptionMetric(kind, value, resetsAt = timestamp(detail["resetTime"]), windowSeconds = seconds)
        }
        val metrics = mutableListOf<SubscriptionMetric>()
        root.obj("usage")?.let { metrics += quota(it, SubscriptionMetricKind.TOTAL) }
        val windows = root["limits"] as? JsonArray
        require(windows == null || windows.size <= 16)
        windows?.forEach { element ->
            val row = element.jsonObject
            val window = row.obj("window") ?: error("window")
            val duration = window["duration"]?.jsonPrimitive?.longOrNull ?: error("duration")
            require(duration in 1..366L * 24 * 60 * 60)
            val multiplier = when (window.str("timeUnit")) {
                "TIME_UNIT_SECOND" -> 1L
                "TIME_UNIT_MINUTE" -> 60L
                "TIME_UNIT_HOUR" -> 3600L
                "TIME_UNIT_DAY" -> 86400L
                else -> error("unit")
            }
            val seconds = duration * multiplier
            require(seconds <= 366L * 86400)
            metrics += quota(row.obj("detail") ?: error("detail"), SubscriptionMetricKind.QUOTA_WINDOW, seconds)
        }
        require(metrics.isNotEmpty())
        return SubscriptionSnapshot(SubscriptionProvider.KIMI_CODE, now, metrics)
    }

    private fun grokBalance(root: JsonObject, now: Long): SubscriptionSnapshot {
        // xAI's prepaid ledger stores purchases as negative cents. Negate the signed
        // total; abs() would incorrectly turn debt into available credit.
        val cents = BigDecimal(decimal(root.obj("total")?.get("val")) ?: error("balance"))
        require(cents.stripTrailingZeros().scale() <= 0)
        val balance = cents.negate().movePointLeft(2).toPlainString()
        return SubscriptionSnapshot(SubscriptionProvider.GROK_API, now,
            listOf(SubscriptionMetric(SubscriptionMetricKind.BALANCE, balance, "USD")))
    }

    private fun cursorBilling(root: JsonObject, now: Long): SubscriptionSnapshot {
        // The transport gathers all pages before parsing. This is spending, not a
        // remaining API wallet, and is deliberately never subtracted from a cap.
        val rows = root["teamMemberSpend"] as? JsonArray ?: error("spend")
        require(rows.size <= 10_000)
        fun sum(field: String): String = rows.fold(BigDecimal.ZERO) { sum, element ->
            val value = BigDecimal(decimal(element.jsonObject[field]) ?: error("spend"))
            require(value.signum() >= 0)
            sum + value
        }.movePointLeft(2).stripTrailingZeros().toPlainString()
        return SubscriptionSnapshot(SubscriptionProvider.CURSOR_BILLING, now, listOf(
            SubscriptionMetric(SubscriptionMetricKind.ON_DEMAND, sum("spendCents"), "USD"),
            SubscriptionMetric(SubscriptionMetricKind.API_SPEND, sum("overallSpendCents"), "USD")))
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
