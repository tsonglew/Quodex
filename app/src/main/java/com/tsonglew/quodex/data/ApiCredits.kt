package com.tsonglew.quodex.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.time.OffsetDateTime

/** Subscription benefit amounts, not an API organization's spend or remaining balance. */
@Serializable
data class ApiCreditBenefit(
    val periodAmountUsd: String,
    val claimableAmountUsd: String? = null,
    val cadence: String,
    val expiresAt: Long,
)

@Serializable
data class ApiCreditsSnapshot(
    val fetchedAt: Long,
    val eligible: Boolean,
    val ineligibilityReason: String? = null,
    val offer: ApiCreditBenefit? = null,
    val benefit: ApiCreditBenefit? = null,
)

/** Fields observed in ChatGPT Billing's read-only benefit preview; unknown values fail closed. */
object ApiCreditsParser {
    fun parse(raw: String, now: Long): ApiCreditsSnapshot {
        fun invalid(): Nothing = throw ApiFailure(FailureKind.PROTOCOL)
        val root = try { AppJson.parseToJsonElement(raw) as? JsonObject }
            catch (_: Exception) { null } ?: invalid()
        fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)
            ?.takeIf { it.isString }?.contentOrNull
        fun amount(row: JsonObject, key: String, required: Boolean): String? {
            val value = row[key]
            if (!required && (value == null || value == JsonNull)) return null
            val text = (value as? JsonPrimitive)?.contentOrNull ?: invalid()
            if (text.length > 40) invalid()
            val decimal = text.toBigDecimalOrNull() ?: invalid()
            if (decimal < BigDecimal.ZERO || decimal > BigDecimal("1000000000") || decimal.scale() > 12 || decimal.scale() < -12) invalid()
            return decimal.stripTrailingZeros().toPlainString()
        }
        fun benefit(key: String): ApiCreditBenefit? {
            val value = root[key]
            if (value == null || value == JsonNull) return null
            val row = value as? JsonObject ?: invalid()
            if (row.text("currency") != "USD") invalid()
            val period = amount(row, "period_credit_amount_usd", true)!!
            val claimable = amount(row, "claimable_credit_amount_usd", false)
            if (claimable != null && claimable.toBigDecimal() > period.toBigDecimal()) invalid()
            val cadence = row.text("cadence")?.takeIf { it == "monthly" || it == "annual" } ?: invalid()
            val expiry = runCatching { OffsetDateTime.parse(row.text("expires_at")).toInstant().epochSecond }
                .getOrNull()?.takeIf { it in 1..253402300799L } ?: invalid()
            return ApiCreditBenefit(period, claimable, cadence, expiry)
        }
        val eligible = (root["eligible"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: invalid()
        val reason = root.text("ineligibility_reason")
        if (eligible && reason != null) invalid()
        val offer = benefit("offer")
        if (eligible && offer == null) invalid()
        return ApiCreditsSnapshot(now, eligible, reason, offer, benefit("benefit"))
    }
}

fun demoApiCredits(now: Long) = ApiCreditsSnapshot(now, true,
    offer = ApiCreditBenefit("100", "75", "monthly", now + 21 * 86400))
