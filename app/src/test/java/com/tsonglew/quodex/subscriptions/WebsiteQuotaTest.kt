package com.tsonglew.quodex.subscriptions

import com.tsonglew.quodex.data.*
import com.tsonglew.quodex.network.RequestRetry
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class WebsiteQuotaTest {
    private val grok = """{"availability":"available","weeklyIncludedUsage":{"usedCents":37,"budgetCents":100,"resetsAt":"2027-01-01T00:00:00Z"},"monthlyUsage":{"usedCents":123.4,"limitCents":999999}}"""
    private val workbuddy = """{"code":0,"data":{"Response":{"Data":{"TotalCount":1,"Accounts":[{"AccountId":"fixture-1","CapacityUnit":"credits","CycleCapacityRemainPrecise":"70.125","CycleCapacitySizePrecise":"100","CycleEndTime":"2027-01-01 23:59:59","PackageName":"Synthetic Pack","email":"private@example.com"}]}}}}"""
    private val kimi = """{"subscriptionBalance":{"unit":"UNIT_CREDIT","amountUsedRatio":0.28,"expireTime":"2027-01-01T00:00:00Z"},"boosterWallets":[{"status":"STATUS_ACTIVE","moneyLeft":{"currency":"CNY","priceInCents":"1850"}},{"status":"STATUS_INACTIVE","moneyLeft":{"currency":"CNY","priceInCents":"9999"}}]}"""
    private fun api(server: MockWebServer) = SubscriptionApi(
        endpoints = SubscriptionProvider.entries.associateWith { server.url("/").toString() },
        now = { 1000 }, retry = RequestRetry(now = { 1000 }, pause = {}))

    @Test fun kimiMembershipDoesNotMixInactiveWalletsOrOpenPlatformCredit() {
        val snapshot = SubscriptionParser.parse(SubscriptionProvider.KIMI, kimi, 1000)
        assertEquals(listOf("28", "18.50"), snapshot.metrics.map { it.value })
        assertEquals(SubscriptionMetricKind.EXTRA_BALANCE, snapshot.metrics[1].kind)
        assertEquals("CNY", snapshot.metrics[1].currency)
    }

    @Test fun unifiedGrokUsageNeverTreatsSpendingCapAsBalance() {
        val snapshot = SubscriptionParser.parse(SubscriptionProvider.GROK, grok, 1000)
        assertEquals(listOf("37", "1.234", null), snapshot.metrics.map { it.value })
        assertEquals(SubscriptionMetricKind.EXTRA_BALANCE, snapshot.metrics.last().kind)
        assertEquals("USD", snapshot.metrics.last().currency)
    }

    @Test fun workbuddyPreservesPackagePrecisionAndUnzonedServerPeriod() {
        val snapshot = SubscriptionParser.parse(SubscriptionProvider.WORKBUDDY, workbuddy, 1000)
        val metric = snapshot.metrics.single()
        assertEquals("70.125", metric.value)
        assertEquals("100", metric.limit)
        assertEquals(SubscriptionMetricUnit.CREDITS, metric.unit)
        assertEquals("2027-01-01 23:59:59", metric.serverPeriodEnd)
        assertNull(metric.resetsAt)
        assertFalse(snapshot.toString().contains("private@example.com"))
        assertFalse(snapshot.toString().contains("fixture-1"))
    }

    @Test fun workbuddySessionHasExplicitFieldsAndRejectsHeaderInjection() {
        val session = WorkBuddySession("secret-session", "secret-chunk", "Synthetic Browser/1.0")
        assertNotNull(WorkBuddySession.parse(session.encode()))
        assertFalse(session.toString().contains("secret"))
        listOf(
            WorkBuddySession("token; injected=value", "chunk", "Browser/1.0").encode(),
            WorkBuddySession("token", "chunk", "Browser/1.0\r\nHost: attacker").encode(),
            """{"session":"token","session_2":"chunk","userAgent":"Browser/1.0","extraCookie":"secret"}"""
        ).forEach { assertFalse(SubscriptionApi.validCredential(SubscriptionProvider.WORKBUDDY, it)) }
    }

    @Test fun workbuddyReadUsesOnlyAllowlistedCookiesAndOriginalBrowserAgent() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(workbuddy))
            val session = WorkBuddySession("secret-session", "secret-chunk", "Synthetic Browser/1.0")
            val snapshot = api(server).fetch(SubscriptionProvider.WORKBUDDY, session.encode())
            assertEquals("70.125", snapshot.metrics.single().value)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("session=secret-session; session_2=secret-chunk", request.getHeader("Cookie"))
            assertEquals("Synthetic Browser/1.0", request.getHeader("User-Agent"))
            assertNull(request.getHeader("Authorization"))
            assertTrue(request.body.readUtf8().contains("\"OnlyValidPeriod\":true"))
        }
    }

    @Test fun kimiUsesBearerReadWithNoCookieOrUnrelatedSessionFields() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(kimi))
            api(server).fetch(SubscriptionProvider.KIMI, "private-access-token")
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("{}", request.body.readUtf8())
            assertEquals("Bearer private-access-token", request.getHeader("Authorization"))
            assertEquals("1", request.getHeader("connect-protocol-version"))
            assertNull(request.getHeader("Cookie"))
        }
    }

    @Test fun unifiedGrokReadKeepsItsWalletSeparateFromDeveloperApi() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(grok))
            server.enqueue(MockResponse().setBody("""{"usdRemainingCents":"850"}"""))
            val result = api(server).fetch(SubscriptionProvider.GROK, "private-session")
            assertEquals("8.50", result.metrics.last().value)
            assertEquals(SubscriptionProvider.GROK, result.provider)
            val usage = server.takeRequest()
            val wallet = server.takeRequest()
            assertEquals("/v2/usage-status?scope=user", usage.path)
            assertEquals("GET", usage.method)
            assertEquals("/get-complat-credit-balance", wallet.path)
            assertEquals("POST", wallet.method)
            listOf(usage, wallet).forEach {
                assertEquals("WorkosCursorSessionToken=private-session", it.getHeader("Cookie"))
                assertNull(it.getHeader("Authorization"))
            }
        }
    }

    private class Store : SubscriptionStore {
        var accounts = emptyMap<SubscriptionProvider, SubscriptionAccount>()
        override fun read() = accounts
        override fun write(accounts: Map<SubscriptionProvider, SubscriptionAccount>) { this.accounts = accounts }
        override fun reset() { accounts = emptyMap() }
    }

    @Test fun importedQuotaSurvivesRestartAndNeverUsesAutomaticNetworkRefresh() = runBlocking {
        val store = Store()
        val repository = SubscriptionRepository({ _, _ -> error("import must not make requests") }, store, now = { 2000 })
        repository.importGrok(grok)
        repository.refresh(SubscriptionProvider.GROK, true)
        assertEquals("", store.accounts.getValue(SubscriptionProvider.GROK).credential)
        val restored = SubscriptionRepository({ _, _ -> error("import must not refresh") }, store, now = { 9999 })
        restored.initialize()
        restored.refresh(SubscriptionProvider.GROK, true)
        assertTrue(restored.state.value.getValue(SubscriptionProvider.GROK).imported)
        assertEquals(2000L, restored.state.value.getValue(SubscriptionProvider.GROK).snapshot?.fetchedAt)
        restored.importGrok("{}")
        assertEquals(FailureKind.PROTOCOL, restored.state.value.getValue(SubscriptionProvider.GROK).failure)
        assertEquals("37", restored.state.value.getValue(SubscriptionProvider.GROK).snapshot?.metrics?.first()?.value)
    }

    @Test fun manualImportInvalidatesAnOlderRemoteResponse() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val repository = SubscriptionRepository({ p, _ ->
            started.complete(Unit); finish.await()
            SubscriptionSnapshot(p, 1000, listOf(SubscriptionMetric(SubscriptionMetricKind.WEEKLY, "99")))
        }, Store(), now = { 2000 })
        val pending = launch { repository.connect(SubscriptionProvider.GROK, "private-session") }
        started.await()
        repository.importGrok(grok)
        finish.complete(Unit)
        pending.join()
        val state = repository.state.value.getValue(SubscriptionProvider.GROK)
        assertTrue(state.imported)
        assertEquals("37", state.snapshot?.metrics?.first()?.value)
    }
}
