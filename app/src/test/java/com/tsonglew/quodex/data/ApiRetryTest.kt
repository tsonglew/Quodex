package com.tsonglew.quodex.data

import com.tsonglew.quodex.network.RequestRetry
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.*
import org.junit.Assert.*
import java.net.InetAddress

class ApiRetryTest {
    private val server = MockWebServer()
    private val session = Session("test-access", "test-refresh", "test-account", 2000)
    private var time = 1000L
    private val waits = mutableListOf<Long>()
    private val quota = """{"plan_type":"plus","rate_limit":{"primary_window":{"used_percent":12,"limit_window_seconds":18000,"reset_at":2000}}}"""
    private val tokens = """{"stats":{"lifetime_tokens":123,"daily_usage_buckets":[]}}"""
    @Before fun start() { server.start(InetAddress.getByName("127.0.0.1"), 0) }
    @After fun stop() { server.shutdown() }
    private fun url(path: String) = server.url(path).newBuilder().host("127.0.0.1").build().toString()
    private fun api(pause: suspend (Long) -> Unit = { waits += it; time += it / 1000 }) = CodexApi(
        authBase = url("/").removeSuffix("/"),
        usageUrl = url("/usage"), tokenUsageUrl = url("/tokens"), now = { time },
        requestRetry = RequestRetry(now = { time }, pause = pause))

    @Test fun quotaAndTokenReadsRecoverFromTransientHttpFailures() = runBlocking {
        val api = api()
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody(quota))
        assertEquals("test-account", api.usage(session).accountId)
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "5"))
        server.enqueue(MockResponse().setBody(tokens))
        assertEquals(123L, api.tokenUsage(session).lifetimeTokens)
        assertEquals(listOf(1000L, 5000L), waits)
        assertEquals(4, server.requestCount)
    }

    @Test fun interruptedResponseRetriesButMalformedJsonDoesNot() = runBlocking {
        server.enqueue(MockResponse().setBody(quota).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        server.enqueue(MockResponse().setBody(quota))
        api().usage(session)
        assertEquals(2, server.requestCount)
        server.enqueue(MockResponse().setBody("not json"))
        assertEquals(FailureKind.PROTOCOL, (runCatching { api().usage(session) }.exceptionOrNull() as ApiFailure).kind)
        assertEquals(3, server.requestCount)
    }

    @Test fun exhaustedReadsStopAfterThreeButWorkerRequestsOnlyOnce() = runBlocking {
        val api = api()
        repeat(3) { server.enqueue(MockResponse().setResponseCode(502)) }
        assertEquals(FailureKind.SERVER, (runCatching { api.tokenUsage(session) }.exceptionOrNull() as ApiFailure).kind)
        assertEquals(3, server.requestCount)
        server.enqueue(MockResponse().setResponseCode(503))
        runCatching { api.usage(session, retryNetwork = false) }
        assertEquals(4, server.requestCount)
        assertEquals(listOf(1000L, 2000L), waits)
    }

    @Test fun permissionFailuresAndAuthPostsAreNotRetried() = runBlocking {
        val api = api()
        for (status in listOf(401, 403, 404, 302)) {
            server.enqueue(MockResponse().setResponseCode(status))
            assertFalse((runCatching { api.usage(session) }.exceptionOrNull() as ApiFailure).retryable)
        }
        server.enqueue(MockResponse().setResponseCode(503))
        runCatching { api.challenge() }
        server.enqueue(MockResponse().setResponseCode(503))
        runCatching { api.renew(session) }
        assertEquals(6, server.requestCount)
        assertTrue(waits.isEmpty())
    }

    @Test fun longLimitsStopImmediatelyAndEndpointsHaveIndependentGuards() = runBlocking {
        val api = api()
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120"))
        assertEquals(1120L, (runCatching { api.tokenUsage(session) }.exceptionOrNull() as ApiFailure).retryAt)
        runCatching { api.tokenUsage(session) }
        assertEquals(1, server.requestCount)
        server.enqueue(MockResponse().setBody(quota))
        api.usage(session)
        assertEquals(2, server.requestCount)
        time += 120
        server.enqueue(MockResponse().setBody(tokens))
        api.tokenUsage(session)
        assertEquals(3, server.requestCount)
        assertTrue(waits.isEmpty())
    }

    @Test fun cancelledBackoffRetainsServerDeadlineWithoutAnotherHttpRequest() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val api = api { entered.complete(Unit); awaitCancellation() }
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "10"))
        val job = launch { api.usage(session) }
        withTimeout(5000) { entered.await() }
        job.cancelAndJoin()
        assertEquals(1010L, (runCatching { api.usage(session, retryNetwork = false) }.exceptionOrNull() as ApiFailure).retryAt)
        assertEquals(1, server.requestCount)
        time += 10
        server.enqueue(MockResponse().setBody(quota))
        api.usage(session)
        assertEquals(2, server.requestCount)
    }
}
