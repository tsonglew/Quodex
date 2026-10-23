package com.tsonglew.quodex.data

import com.tsonglew.quodex.diagnostics.Diagnostics
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class TokenUsageApiTest {
    private val server = MockWebServer()
    private val session = Session("secret-access", "secret-refresh", "test-account", 2000)
    private lateinit var api: CodexApi
    @Before fun start() {
        server.start()
        api = CodexApi(tokenUsageUrl = server.url("/backend-api/wham/profiles/me").toString(), now = { 1000 })
    }
    @After fun stop() { server.shutdown() }

    @Test fun profileReadUsesGetAndCredentialsOnlyInHeaders() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"stats":{"lifetime_tokens":9000000000,"daily_usage_buckets":[]}}"""))
        val result = api.tokenUsage(session)
        assertEquals(9000000000L, result.lifetimeTokens)
        assertEquals(1000L, result.fetchedAt)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/backend-api/wham/profiles/me", request.path)
        assertEquals(0L, request.bodySize)
        assertEquals("Bearer secret-access", request.getHeader("Authorization"))
        assertEquals("test-account", request.getHeader("ChatGPT-Account-Id"))
        assertEquals("application/json", request.getHeader("Accept"))
    }

    @Test fun profileFailuresRemainTypedAndDoNotLeakResponse() = runBlocking {
        for ((status, kind) in listOf(401 to FailureKind.AUTH, 403 to FailureKind.FORBIDDEN, 404 to FailureKind.SERVER, 503 to FailureKind.SERVER)) {
            server.enqueue(MockResponse().setResponseCode(status).setBody("private profile response"))
            try { api.tokenUsage(session); fail() }
            catch (failure: ApiFailure) {
                assertEquals(kind, failure.kind)
                assertFalse(failure.toString().contains("private"))
            }
        }
    }

    @Test fun profileRateLimitRetainsRetryAfter() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "120"))
        try { api.tokenUsage(session); fail() }
        catch (failure: ApiFailure) {
            assertEquals(FailureKind.LIMITED, failure.kind)
            assertEquals(1120L, failure.retryAt)
        }
    }

    @Test fun malformedProfileAndOversizeResponsesAreProtocolFailures() = runBlocking {
        for (body in listOf("{\"profile\":{\"display_name\":\"private\"}}", "not JSON", " ".repeat(512 * 1024 + 1))) {
            server.enqueue(MockResponse().setBody(body))
            try { api.tokenUsage(session); fail() }
            catch (failure: ApiFailure) { assertEquals(FailureKind.PROTOCOL, failure.kind) }
        }
    }

    @Test fun redirectsDoNotSendProfileCredentialsElsewhere() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/unexpected")))
        try { api.tokenUsage(session); fail() }
        catch (failure: ApiFailure) { assertEquals(FailureKind.SERVER, failure.kind) }
        assertEquals(1, server.requestCount)
    }

    @Test fun tokenDiagnosticsUseTheirOwnCategoryAndExcludeProfileDetails() = runBlocking {
        val directory = Files.createTempDirectory("token-api-test").toFile()
        try {
            val diagnostics = Diagnostics(File(directory, "events.log"))
            val instrumented = CodexApi(tokenUsageUrl = server.url("/private-profile-path").toString(), diagnostics = diagnostics)
            server.enqueue(MockResponse().setBody("""{"profile":{"display_name":"private-name"},"stats":{"lifetime_tokens":123456789}}"""))
            instrumented.tokenUsage(session)
            val report = diagnostics.report()
            assertTrue(report.contains("HTTP_START 2"))
            assertTrue(report.contains("HTTP_END 2 200"))
            listOf("private-name", "private-profile-path", "secret-access", "secret-refresh", "test-account", "123456789").forEach {
                assertFalse(report.contains(it))
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun cancellationPropagatesAndStopsTheHttpCall() = runBlocking {
        val client = OkHttpClient()
        val instrumented = CodexApi(client = client, tokenUsageUrl = server.url("/profiles/me").toString())
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val pending = async { instrumented.tokenUsage(session) }
        withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
        pending.cancelAndJoin()
        assertTrue(pending.isCancelled)
        withTimeout(5_000) { while (client.dispatcher.runningCallsCount() != 0) delay(10) }
    }

    @Test fun internalTenSecondTimeoutIsNetworkFailureInsteadOfUserCancellation() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        withTimeout(15_000) {
            try { api.tokenUsage(session); fail() }
            catch (failure: ApiFailure) { assertEquals(FailureKind.NETWORK, failure.kind) }
        }
    }
}
