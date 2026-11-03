package com.tsonglew.quodex.subscriptions

import com.tsonglew.quodex.data.*
import org.junit.Assert.*
import org.junit.Test

class SubscriptionParserTest {
    private fun parse(provider: SubscriptionProvider, body: String) = SubscriptionParser.parse(provider, body, 1000)

    @Test fun deepseekPreservesCurrencyAndPrecision() {
        val result = parse(SubscriptionProvider.DEEPSEEK, """{"is_available":true,"balance_infos":[
            {"currency":"CNY","total_balance":"110.123456","granted_balance":"10.123456","topped_up_balance":"100"},
            {"currency":"USD","total_balance":"0","granted_balance":"0","topped_up_balance":"0"}]}""")
        assertEquals("110.123456", result.metrics[0].value)
        assertEquals("CNY", result.metrics[0].currency)
        assertEquals("USD", result.metrics[3].currency)
        assertEquals("0", result.metrics[3].value)
        assertEquals(true, result.available)
        assertEquals(1000, result.fetchedAt)
    }

    @Test fun zenMicrocentsAreNotCentsAndNegativeBalanceIsPreserved() {
        val result = parse(SubscriptionProvider.OPENCODE_ZEN, """{"billingMode":"credit","balanceMicroCents":"-123456789","availableMicroCents":"987654321"}""")
        assertEquals("-1.23456789", result.metrics[0].value)
        assertEquals("9.87654321", result.metrics[1].value)
    }

    @Test fun goKeepsMissingPercentUnknownAndOverageAboveOneHundred() {
        val result = parse(SubscriptionProvider.OPENCODE_GO, """{"usage":{
            "rolling":{"status":"rate-limited","percent":110.5,"resetsAt":"2026-11-04T02:00:00Z"},
            "weekly":{"status":"ok","percent":null,"resetsAt":null},
            "monthly":{"status":"ok","percent":0,"resetsAt":"2026-12-01T00:00:00Z"}}}""")
        assertEquals("110.5", result.metrics[0].value)
        assertEquals(1793757600L, result.metrics[0].resetsAt)
        assertNull(result.metrics[1].value)
        assertEquals("0", result.metrics[2].value)
    }

    @Test fun cursorUsesServerPercentNotLegacyDollarRatio() {
        val result = parse(SubscriptionProvider.CURSOR, """{"membershipType":"pro","billingCycleEnd":"2026-12-01T00:00:00Z",
            "individualUsage":{"plan":{"used":1516,"limit":2000,"autoPercentUsed":3.29,"apiPercentUsed":0.71},
            "onDemand":{"enabled":true,"used":1234}}}""")
        assertEquals("3.29", result.metrics[0].value)
        assertEquals("0.71", result.metrics[1].value)
        assertNull(result.metrics[2].value)
        assertEquals("12.34", result.metrics[3].value)
        assertEquals("pro", result.plan)
    }

    @Test fun disabledOnDemandIsNotReportedAsABill() {
        val result = parse(SubscriptionProvider.CURSOR, """{"individualUsage":{"plan":{"totalPercentUsed":0},"onDemand":{"enabled":false,"used":1234}}}""")
        assertEquals(3, result.metrics.size)
    }

    @Test fun invalidAndUnrecognizedPayloadsFailInsteadOfShowingZero() {
        val cases = listOf(
            SubscriptionProvider.DEEPSEEK to """{"is_available":true,"balance_infos":[]}""",
            SubscriptionProvider.DEEPSEEK to """{"is_available":true,"balance_infos":[{"currency":"USD","total_balance":"NaN"}]}""",
            SubscriptionProvider.OPENCODE_GO to """{"usage":{}}""",
            SubscriptionProvider.CURSOR to """{"individualUsage":{"plan":{"used":1516,"limit":2000}}}""",
            SubscriptionProvider.CURSOR to """{"individualUsage":{"plan":{"autoPercentUsed":-1}}}""",
            SubscriptionProvider.OPENCODE_ZEN to """{"billingMode":"prepaid","balanceMicroCents":"1.5","availableMicroCents":"5"}""",
        )
        cases.forEach { (provider, body) ->
            val failure = runCatching { parse(provider, body) }.exceptionOrNull()
            assertEquals("$provider", FailureKind.PROTOCOL, (failure as? ApiFailure)?.kind)
        }
        SubscriptionProvider.entries.forEach { provider ->
            listOf("{}", "null", "<html>Sign in</html>").forEach { body ->
                assertTrue(runCatching { parse(provider, body) }.exceptionOrNull() is ApiFailure)
            }
        }
    }

    @Test fun credentialsCannotInjectHeadersOrAdditionalCookies() {
        listOf("", "abc\nInjected: yes", "value;other=secret", "two keys", "a".repeat(8193)).forEach {
            assertFalse(SubscriptionApi.validCredential(it))
        }
        assertTrue(SubscriptionApi.validCredential("user_123%3Atoken.example"))
        assertTrue(SubscriptionApi.validCredential("oc_sk_example"))
    }
}
