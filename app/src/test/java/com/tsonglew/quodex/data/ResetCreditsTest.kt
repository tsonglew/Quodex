package com.tsonglew.quodex.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ResetCreditsTest {
    @Test fun isoDatesAndCappedRowsKeepTheAuthoritativeCount() {
        val details = ResetCreditsParser.parse("""{"available_count":5,"credits":[
          {"id":"one","reset_type":"codex","status":"available","granted_at":"2026-11-01T08:00:00+08:00",
           "expires_at":"2026-12-01T00:00:00.123Z","title":"  Welcome  ","description":"A gift"},
          {"id":"two","status":"future-status","granted_at":"invalid","expires_at":null,"title":false}]}
        """, "a", 123)
        assertEquals(5, details.availableCount)
        assertEquals(123L, details.fetchedAt)
        assertEquals(2, details.credits.size)
        val first = details.credits.first()
        assertEquals("Welcome", first.title)
        assertEquals("A gift", first.description)
        assertEquals(Instant.parse("2026-11-01T00:00:00Z").epochSecond, first.grantedAt)
        assertEquals(Instant.parse("2026-12-01T00:00:00Z").epochSecond, first.expiresAt)
        val second = details.credits.last()
        assertEquals("future-status", second.status)
        assertNull(second.grantedAt)
        assertNull(second.expiresAt)
        assertNull(second.title)
    }

    @Test fun zeroAndMissingDetailsAreNotInterchangeable() {
        assertEquals(0, ResetCreditsParser.parse("""{"available_count":0,"credits":[]}""", "a", 1).availableCount)
        listOf("{}", "null", "[]", "broken", """{"credits":[]}""",
            """{"available_count":1,"credits":null}""", """{"available_count":-1,"credits":[]}""",
            """{"available_count":2147483648,"credits":[]}""", """{"available_count":1,"credits":[{}]}""",
            """{"available_count":2,"credits":[{"id":"x"},{"id":"x"}]}""").forEach { raw ->
            assertEquals(raw, FailureKind.PROTOCOL, (runCatching { ResetCreditsParser.parse(raw, "a", 1) }.exceptionOrNull() as? ApiFailure)?.kind)
        }
        assertEquals(FailureKind.FORBIDDEN, (runCatching {
            ResetCreditsParser.parse("""{"account_id":"other","available_count":0,"credits":[]}""", "a", 1)
        }.exceptionOrNull() as ApiFailure).kind)
    }

    @Test fun oldCacheLoadsAndNewCacheRoundTripsWithoutMigration() {
        val old = AppJson.decodeFromString<UsageSnapshot>("""{"accountId":"a","plan":"plus","windows":[],"fetchedAt":100}""")
        assertNull(old.resetCreditDetails)
        assertEquals(null to 100L, old.resetCreditAvailability())
        val details = ResetCreditsSnapshot(101, 3, emptyList())
        val fresh = old.copy(resetCredits = 2, resetCreditDetails = details)
        assertEquals(3 to 101L, fresh.resetCreditAvailability())
        assertEquals(1 to 102L, fresh.copy(fetchedAt = 102, resetCredits = 1).resetCreditAvailability())
        assertEquals(3 to 101L, fresh.copy(fetchedAt = 102, resetCredits = null).resetCreditAvailability())
        assertEquals(fresh, AppJson.decodeFromString<UsageSnapshot>(AppJson.encodeToString(fresh)))
    }

    @Test fun readUsesExistingCredentialsAndRetriesTransientFailureWithoutConsuming() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setBody("""{"available_count":0,"credits":[]}"""))
            val retry = com.tsonglew.quodex.network.RequestRetry(pause = { })
            val api = CodexApi(resetCreditsUrl = server.url("/resets").toString(), requestRetry = retry, now = { 100 })
            assertEquals(0, api.resetCredits(Session("test-access", "test-refresh", "a", 9999)).availableCount)
            repeat(2) {
                val request = server.takeRequest()
                assertEquals("GET", request.method)
                assertEquals("/resets", request.path)
                assertEquals("Bearer test-access", request.getHeader("Authorization"))
                assertEquals("a", request.getHeader("ChatGPT-Account-Id"))
                assertEquals(0L, request.bodySize)
            }
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }
}
