package com.tsonglew.quodex.data

import org.junit.Assert.*
import org.junit.Test

class ApiCreditsTest {
    private val offer = """{"period_credit_amount_usd":"100.00","claimable_credit_amount_usd":"75.125","currency":"USD","cadence":"monthly","expires_at":"2026-12-01T00:00:00Z"}"""
    private fun parse(value: String) = ApiCreditsParser.parse(value, 1)

    @Test fun observedIneligibleResponseDoesNotInventZeroBalance() {
        val result = parse("""{"eligible":false,"ineligibility_reason":"not_qualified","offer":null}""")
        assertFalse(result.eligible)
        assertNull(result.offer)
        assertNull(result.benefit)
    }

    @Test fun proratedOfferPreservesDecimalAmountsAndExpiry() {
        val result = parse("""{"eligible":true,"offer":$offer}""")
        assertEquals("100", result.offer!!.periodAmountUsd)
        assertEquals("75.125", result.offer.claimableAmountUsd)
        assertEquals(1796083200L, result.offer.expiresAt)
    }

    @Test fun existingBenefitAndUnclaimableStateAreIndependent() {
        val result = parse("""{"eligible":false,"offer":null,"benefit":$offer}""")
        assertFalse(result.eligible)
        assertEquals("100", result.benefit!!.periodAmountUsd)
        assertNull(result.offer)
    }

    @Test fun unknownOrMalformedAmountsNeverBecomeValidCredit() {
        val invalid = listOf("{}", "[]", """{"eligible":"false"}""", """{"eligible":true}""",
            """{"eligible":true,"ineligibility_reason":"unknown","offer":$offer}""",
            *listOf(offer.replace("100.00", "-1"), offer.replace("100.00", "NaN"),
                offer.replace("100.00", "1e99999"), offer.replace("75.125", "101"),
                offer.replace("USD", "EUR"), offer.replace("monthly", "weekly"),
                offer.replace("2026-12-01T00:00:00Z", "bad-date"))
                .map { """{"eligible":true,"offer":$it}""" }.toTypedArray())
        invalid.forEach { raw ->
            try { parse(raw); fail("Malformed preview accepted") }
            catch (failure: ApiFailure) { assertEquals(FailureKind.PROTOCOL, failure.kind) }
        }
    }
}
