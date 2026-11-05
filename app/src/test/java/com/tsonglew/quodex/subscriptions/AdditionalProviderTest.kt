package com.tsonglew.quodex.subscriptions

import com.tsonglew.quodex.data.*
import com.tsonglew.quodex.network.RequestRetry
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class AdditionalProviderTest {
    private fun parse(provider: SubscriptionProvider, raw: String) = SubscriptionParser.parse(provider, raw, 1000)
    private fun api(server: MockWebServer) = SubscriptionApi(
        endpoints = SubscriptionProvider.entries.associateWith { server.url("/").toString() },
        now = { 1000 }, retry = RequestRetry(now = { 1000 }, pause = {}))

    @Test fun kimiBalancesKeepDebtAndServerAvailabilityWithoutAddingAmounts() {
        val raw = """{"code":0,"status":true,"data":{"available_balance":4.123456,"cash_balance":-2,"voucher_balance":4.123456}}"""
        val domestic = parse(SubscriptionProvider.KIMI_API, raw)
        val global = parse(SubscriptionProvider.KIMI_API_GLOBAL, raw)
        assertEquals(listOf("4.123456", "4.123456", "-2"), domestic.metrics.map { it.value })
        assertEquals(setOf("CNY"), domestic.metrics.map { it.currency }.toSet())
        assertEquals(setOf("USD"), global.metrics.map { it.currency }.toSet())
        assertEquals(true, domestic.available)
    }

    @Test fun kimiCodeUsesExplicitWindowsAndDoesNotInventWeeklyPeriod() {
        val result = parse(SubscriptionProvider.KIMI_CODE, """{"usage":{"limit":"120","remaining":"90"},"limits":[
            {"window":{"duration":300,"timeUnit":"TIME_UNIT_MINUTE"},"detail":{"limit":"100","used":"42","resetTime":"2027-01-01T00:00:00Z"}},
            {"window":{"duration":7,"timeUnit":"TIME_UNIT_DAY"},"detail":{"limit":"100","used":"110"}}]}""")
        assertEquals(SubscriptionMetricKind.TOTAL, result.metrics[0].kind)
        assertEquals(listOf("25", "42", "110"), result.metrics.map { it.value })
        assertEquals(listOf(null, 18000L, 604800L), result.metrics.map { it.windowSeconds })
        assertEquals(1798761600L, result.metrics[1].resetsAt)
    }

    @Test fun zeroAllocationDoesNotBecomeZeroPercentUsed() {
        val result = parse(SubscriptionProvider.KIMI_CODE, """{"usage":{"limit":0,"used":0}}""")
        assertNull(result.metrics.single().value)
    }

    @Test fun grokSignedLedgerConvertsCentsAndNeverTurnsDebtIntoCredit() {
        assertEquals("10.00", parse(SubscriptionProvider.GROK_API, """{"total":{"val":"-1000"}}""").metrics.single().value)
        assertEquals("-2.50", parse(SubscriptionProvider.GROK_API, """{"total":{"val":"250"}}""").metrics.single().value)
        assertEquals("0.00", parse(SubscriptionProvider.GROK_API, """{"total":{"val":"0"}}""").metrics.single().value)
    }

    @Test fun cursorCostsKeepSubCentPrecisionAndAreNotBalances() {
        val result = parse(SubscriptionProvider.CURSOR_BILLING, """{"teamMemberSpend":[
            {"spendCents":2450.125487,"overallSpendCents":3000.125487},
            {"spendCents":1875.500123,"overallSpendCents":3200.750456}]}""")
        assertEquals("43.2562561", result.metrics[0].value)
        assertEquals("62.00875943", result.metrics[1].value)
        assertEquals(SubscriptionMetricKind.API_SPEND, result.metrics[1].kind)
        assertFalse(result.metrics.any { it.kind in setOf(SubscriptionMetricKind.BALANCE, SubscriptionMetricKind.AVAILABLE) })
    }

    @Test fun malformedNewProviderResponsesNeverBecomePlausibleBalances() {
        val cases = listOf(
            SubscriptionProvider.KIMI_API to """{"code":1,"status":true,"data":{"available_balance":5,"cash_balance":5,"voucher_balance":0}}""",
            SubscriptionProvider.KIMI_API to """{"code":0,"status":true,"data":{"available_balance":5,"voucher_balance":0}}""",
            SubscriptionProvider.KIMI_CODE to """{"usage":{"limit":100,"remaining":101}}""",
            SubscriptionProvider.KIMI_CODE to """{"usage":{"limit":100}}""",
            SubscriptionProvider.GROK_API to """{"total":{"val":"NaN"}}""",
            SubscriptionProvider.GROK_API to """{"total":{"val":"1.2"}}""",
            SubscriptionProvider.CURSOR_BILLING to """{"teamMemberSpend":[{"spendCents":-1,"overallSpendCents":2}]}"""
        )
        cases.forEach { (provider, raw) ->
            assertEquals(provider.name, FailureKind.PROTOCOL, (runCatching { parse(provider, raw) }.exceptionOrNull() as? ApiFailure)?.kind)
        }
    }

    @Test fun grokUsesTeamValidationThenReadOnlyBalanceOnSameOrigin() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"scope":"SCOPE_TEAM","scopeId":"team-1","teamId":"old-team"}"""))
            server.enqueue(MockResponse().setBody("""{"total":{"val":"-1000"}}"""))
            val snapshot = api(server).fetch(SubscriptionProvider.GROK_API, "management-key")
            assertEquals("10.00", snapshot.metrics.single().value)
            val identity = server.takeRequest()
            val balance = server.takeRequest()
            assertEquals("/auth/management-keys/validation", identity.path)
            assertEquals("/v1/billing/teams/team-1/prepaid/balance", balance.path)
            listOf(identity, balance).forEach {
                assertEquals("GET", it.method)
                assertEquals(0L, it.bodySize)
                assertEquals("Bearer management-key", it.getHeader("Authorization"))
                assertNull(it.getHeader("Cookie"))
            }
        }
    }

    @Test fun grokDoesNotChooseATeamForOrganizationKeysOrAcceptPathInjection() = runBlocking {
        listOf("""{"scope":"SCOPE_ORGANIZATION","scopeId":"org-1"}""",
            """{"scope":"SCOPE_TEAM","scopeId":"../another-team"}""").forEach { raw ->
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(raw))
                assertTrue(runCatching { api(server).fetch(SubscriptionProvider.GROK_API, "key") }.exceptionOrNull() is ApiFailure)
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun cursorCollectsAllPagesWithoutPersistingMembersOrLimits() = runBlocking {
        MockWebServer().use { server ->
            listOf("one", "two").forEach { id -> server.enqueue(MockResponse().setBody("""{
                "totalPages":2,"totalMembers":2,"subscriptionCycleStart":123456,
                "teamMemberSpend":[{"userId":"$id","name":"Private Name","email":"private@example.com",
                "spendCents":100.125,"overallSpendCents":200.125,"monthlyLimitDollars":1000}]}""")) }
            val result = api(server).fetch(SubscriptionProvider.CURSOR_BILLING, "admin-key")
            assertEquals("2.0025", result.metrics[0].value)
            assertEquals("4.0025", result.metrics[1].value)
            assertFalse(result.toString().contains("Private"))
            (1..2).forEach { page ->
                val request = server.takeRequest()
                assertEquals("POST", request.method)
                assertEquals("{\"page\":$page,\"pageSize\":500}", request.body.readUtf8())
                assertEquals(Credentials.basic("admin-key", ""), request.getHeader("Authorization"))
                assertNull(request.getHeader("Cookie"))
            }
        }
    }

    @Test fun cursorRejectsDuplicatePagesAndIncompleteTotals() = runBlocking {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setBody("""{"totalPages":2,"totalMembers":2,"subscriptionCycleStart":123,
                "teamMemberSpend":[{"userId":"same","spendCents":100,"overallSpendCents":200}]}""")) }
            val failure = runCatching { api(server).fetch(SubscriptionProvider.CURSOR_BILLING, "key") }.exceptionOrNull()
            assertEquals(FailureKind.PROTOCOL, (failure as? ApiFailure)?.kind)
        }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"totalPages":1,"totalMembers":1,"subscriptionCycleStart":123,"teamMemberSpend":[]}"""))
            assertTrue(runCatching { api(server).fetch(SubscriptionProvider.CURSOR_BILLING, "key") }.exceptionOrNull() is ApiFailure)
        }
    }
}
