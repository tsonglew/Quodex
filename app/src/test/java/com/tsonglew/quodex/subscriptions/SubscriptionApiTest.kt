package com.tsonglew.quodex.subscriptions

import com.tsonglew.quodex.data.*
import com.tsonglew.quodex.network.RequestRetry
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class SubscriptionApiTest {
    private val balance = """{"is_available":true,"balance_infos":[{"currency":"USD","total_balance":"1","granted_balance":"0","topped_up_balance":"1"}]}"""
    private fun api(server: MockWebServer) = SubscriptionApi(endpoints = SubscriptionProvider.entries.associateWith { server.url("/usage").toString() },
        now = { 1000 }, retry = RequestRetry(now = { 1000 }, pause = {}))

    @Test fun transientFailureRetriesWithOnlySelectedCredential() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setBody(balance))
            api(server).fetch(SubscriptionProvider.DEEPSEEK, "private-key")
            assertEquals(2, server.requestCount)
            repeat(2) {
                val request = server.takeRequest()
                assertEquals("GET", request.method)
                assertEquals("Bearer private-key", request.getHeader("Authorization"))
                assertNull(request.getHeader("Cookie"))
            }
        }
    }

    @Test fun cursorSendsOneSessionCookieAndNoBearer() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"individualUsage":{"plan":{"totalPercentUsed":20}}}"""))
            api(server).fetch(SubscriptionProvider.CURSOR, "user%3Atoken")
            val request = server.takeRequest()
            assertEquals("WorkosCursorSessionToken=user%3Atoken", request.getHeader("Cookie"))
            assertNull(request.getHeader("Authorization"))
        }
    }

    @Test fun redirectCannotForwardCredentials() = runBlocking {
        MockWebServer().use { source -> MockWebServer().use { destination ->
            source.enqueue(MockResponse().setResponseCode(302).setHeader("Location", destination.url("/steal")))
            val result = runCatching { api(source).fetch(SubscriptionProvider.CURSOR, "private-token") }.exceptionOrNull()
            assertEquals(FailureKind.PROTOCOL, (result as ApiFailure).kind)
            assertEquals(1, source.requestCount)
            assertEquals(0, destination.requestCount)
        } }
    }

    @Test fun authAndLongCooldownDoNotRetry() = runBlocking {
        for (status in listOf(401,403,429)) MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(status).setHeader("Retry-After", "120"))
            val error = runCatching { api(server).fetch(SubscriptionProvider.DEEPSEEK, "key") }.exceptionOrNull() as ApiFailure
            assertEquals(1, server.requestCount)
            if (status == 429) assertEquals(1120L, error.retryAt)
        }
    }

    @Test fun cancellationStopsActiveSocketAndRetries() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            val job = launch(Dispatchers.IO) { api(server).fetch(SubscriptionProvider.DEEPSEEK, "key") }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
            job.cancelAndJoin()
            assertEquals(1, server.requestCount)
        }
    }
}
